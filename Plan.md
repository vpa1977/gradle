# Lenient Gradle Mode — Implementation Plan

Goal: implement a lenient build mode, guarded by the `GRADLE_LENIENT` environment
variable. When enabled, unknown plugins, unresolvable dependencies, unknown
extensions and statements referencing unknown types are skipped with a warning
instead of failing the build.

One commit per step. Each step is implemented by a dedicated subagent.

## Step 1 — Lenient mode flag infrastructure

Add a tiny shared helper, visible to all modules that need it (base-services is
on the classpath of core, plugin-use, dependency-management, model-core and
kotlin-dsl).

- New file: `platforms/core-runtime/base-services/src/main/java/org/gradle/internal/LenientMode.java`
  - `public static boolean isEnabled()` — `true` when `System.getenv("GRADLE_LENIENT")`
    is set to a value other than `""`, `"0"`, `"false"`, `"no"` (case-insensitive).
    Cache the result in a static field (env is fixed for the daemon JVM lifetime).
  - Keep parsing logic in a separate testable static method, e.g.
    `static boolean isEnabledValue(@Nullable String value)`.
- Unit test: `.../src/test/groovy/org/gradle/internal/LenientModeTest.groovy`
  covering the parsing (enabled/disabled values, null).

Commit: `Add GRADLE_LENIENT lenient mode flag`

## Step 2 — Skip unknown plugins

When a `plugins { id("foo") version "bar" }` request cannot be resolved because
the plugin is unknown, emit a warning and skip the plugin instead of failing.

Hook point: `DefaultPluginRequestApplicator.resolvePluginRequest(...)`
(`platforms/extensibility/plugin-use/.../DefaultPluginRequestApplicator.java`).
`PluginResolutionResult.getFound(request)` throws a `LocationAwareException`
wrapping `org.gradle.api.plugins.UnknownPluginException` when the plugin was not
found in any source.

- In `resolvePluginRequest`, when `LenientMode.isEnabled()` and
  `result.getFound(request)` fails with an `UnknownPluginException` in the cause
  chain: log `LOGGER.warn("Plugin {} is unknown, skipping it", request.getDisplayName())`
  and return a no-op `PluginResolution` (new private static nested class):
  `getPluginId()` returns `request.getId()`, `applyTo()` is a no-op,
  `getPluginVersion()` returns null (so the plugin version tracker is skipped).
- Do NOT swallow the "Error resolving plugin ..." branch (repository/network
  errors while contacting plugin repos stay hard failures); only *not found* is
  lenient.
- Verify: `./gradlew :plugin-use:compileJava` (time-boxed).

Commit: `Skip unknown plugins in lenient mode`

## Step 3 — Skip unknown dependencies

When a dependency cannot be resolved/fetched, emit a warning and continue with
whatever resolved instead of failing the build.

Choke point: `ResolutionHost.consolidateFailures(...)` — the single place where
collected resolution failures are turned into a thrown `TypedResolveException`
(used by `rethrowFailuresAndReportProblems` and by
`ResolutionBackedFileCollection.visitDependencies`). Files/artifacts that failed
are collected by the visitors and simply dropped when no exception is thrown.

Two implementations to patch:

- `DefaultConfiguration.DefaultResolutionHost.consolidateFailures`
  (`platforms/software/dependency-management/.../configurations/DefaultConfiguration.java`)
- `ArtifactSetToFileCollectionFactory.NameBackedResolutionHost.consolidateFailures`
  (`platforms/software/dependency-management/.../resolveengine/artifact/ArtifactSetToFileCollectionFactory.java`)

When `LenientMode.isEnabled()` and failures are non-empty: log one warning per
failure (e.g. `LOGGER.warn("Ignoring resolution failure for {}: {}", displayName, failureMessage)`)
and return `Optional.empty()`. Otherwise unchanged.

- Verify: `./gradlew :dependency-management:compileJava` (time-boxed).

Commit: `Skip unresolvable dependencies in lenient mode`

## Step 4 — Ignore unknown extensions (Groovy DSL)

`bar { foo = 1 }` in a Groovy build script with no such extension/method fails
with `MissingMethodException`. In lenient mode: emit the warning
`Extension bar is unknown` and skip the call (return null, closure never runs).

Hook point: `BasicScript.ScriptDynamicObject`
(`subprojects/core/src/main/java/org/gradle/groovy/scripts/BasicScript.java`).
All script-level method dispatch funnels through
`BasicScript.invokeMethod -> dynamicObject.invokeMethod`
(`AbstractDynamicObject.invokeMethod` throws when `tryInvokeMethod` finds
nothing). This covers project, settings and init Groovy scripts.

- Override `invokeMethod(String, Object...)` in `ScriptDynamicObject`:
  call `tryInvokeMethod`; if not found and `LenientMode.isEnabled()`:
  `LOGGER.warn("Extension {} is unknown", name)` and return `null`;
  otherwise fall back to `super.invokeMethod` (existing behavior).
- Property access (`getProperty`) stays strict in Groovy (returning null for a
  missing property would turn `bar.foo` into an NPE, not a skipped statement —
  the Kotlin DSL skipper in step 5 handles that case properly).
- Verify: `./gradlew :core:compileJava` (time-boxed).

Commit: `Ignore unknown extensions in lenient Groovy scripts`

## Step 5 — Kotlin DSL statement skipper (erase-and-recompile)

When a `.gradle.kts` script fails to compile (e.g. `tasks.withType<Foo>() {}`
with unknown `Foo`, `bar { foo = 1 }` with unknown extension `bar`, or
`sourceCompatibility = bar.foo` with unknown `bar`), remove the statement(s)
responsible for the compilation errors from the in-memory source and recompile.
Repeat until the script compiles, no progress can be made, or a round limit is
hit; then rethrow the original `ScriptCompilationException`.

Hook point: `compileKotlinScriptToDirectory(...)`
(`platforms/core-configuration/kotlin-dsl/src/main/kotlin/org/gradle/kotlin/dsl/support/KotlinCompiler.kt`)
— both stage-1 (buildscript/plugins) and stage-2 (body) compilation, for
project/settings/init scripts, funnel through it.

Implementation:

- Wrap the call to `compileKotlinScriptModuleTo` in a retry loop (max ~20
  rounds), only active when `LenientMode.isEnabled()`.
- On `ScriptCompilationException`: collect the error locations
  (line + column). If none have locations, rethrow.
- New file: `.../kotlin/dsl/support/LenientStatementEraser.kt` — uses the
  EXISTING Kotlin parser (PSI), not a hand-rolled one:
  - Parse the script text into a `KtFile` via
    `PsiFileFactory.getInstance(project).createFileFromText(name, KotlinFileType.INSTANCE, text)`.
    The `project` comes from a `KotlinCoreEnvironment` created the same way as
    `kotlinCoreEnvironmentFor` in KotlinCompiler.kt (lenient mode only, reused
    across retry rounds).
  - For each error (line, col) → offset: `ktFile.findElementAt(offset)`, then
    walk up the PSI parents to the nearest statement — a `KtExpression` whose
    parent is a `KtBlockExpression`, `KtScript` or `KtScriptInitializer`.
    Erase its `textRange`. Merge overlapping ranges.
  - Replace erased characters with spaces, preserving newlines (line numbers
    must not shift).
  - Escalation: if a new error offset falls inside (or adjacent to) a
    previously erased range, walk one statement level higher (erase the
    enclosing statement) — handles cases where erasing the inner statement
    leaves the outer statement broken. Track previously erased ranges across
    rounds inside the retry loop.
  - Return the erased source text (or null when nothing erasable).
- Write the erased source to a fresh temp file with the SAME file name (so the
  compiled class name derived from the file name stays stable) in a temp
  directory, and re-run the compilation with a `pathTranslation` that also maps
  the temp path back to the original script path. Do not modify the user's
  script on disk.
- For every skipped statement emit a warning via the passed `Logger`:
  - if the erased statement is a simple `name { ... }` block and the error was
    `Unresolved reference: name` (or `Unresolved reference 'name'`): warn
    `Extension name is unknown`;
  - otherwise warn `Skipping statement that does not compile at line <L>: <error message>`.
- Examples that must work after erasure:
  - `tasks.withType<Foo>() { ... }` → whole statement erased.
  - `tasks.withType<JavaCompile>() { sourceCompatibility = bar.foo }` → only the
    offending inner line erased, block kept.
  - `bar { foo = 1 }` → whole block erased, warning `Extension bar is unknown`.
- Unit tests: new test class for the eraser next to the existing compiler tests
  (`platforms/core-configuration/kotlin-dsl/src/test/kotlin/org/gradle/kotlin/dsl/support/`)
  covering the three examples above plus multiple failing statements.
- Verify: `./gradlew :kotlin-dsl:compileKotlin` and the new unit test
  (time-boxed).

Commit: `Skip statements that fail to compile in lenient Kotlin DSL`

## Notes / assumptions

- The mode is guarded exclusively by the `GRADLE_LENIENT` environment variable,
  read once per daemon JVM.
- Lenient mode is best-effort: skipping a plugin/dependency/statement can still
  surface secondary errors downstream; those are out of scope.
- Groovy DSL unknown *types* (e.g. `tasks.withType(Foo) {}` in `build.gradle`)
  are not covered (would require AST rewriting); the task examples with generic
  syntax (`withType<Foo>()`) target the Kotlin DSL, covered by step 5.
- Verification of each step: targeted module compilation/tests via `./gradlew`,
  time-boxed; a full Gradle self-build is out of scope.
