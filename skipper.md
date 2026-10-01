I understand the scenario now. This is about the **Declarative DSL analyzer**. When an extension `Foo` exists at schema level but later has no properties in the actual schema (empty `{}`), usages of its attribute `bar` currently fail with unresolved-reference errors. The goal is to *skip* such constructs entirely.

## Where errors are produced today

In Declarative DSL, property/function access is resolved by:

- `NamedReferenceResolver.kt` for property reads/assignments: `findDataProperty` only finds `properties` on `DataClass`, and then reports `UnresolvedReference`/`UnresolvedAssignmentLhs` if missing.
- `FunctionCallResolver.kt:351` for extension configuring functions: `findMemberFunction` looks at `receiverType.memberFunctions`.

`CodeAnalyzer.kt:17-43` calls `analysisStatementFilter.shouldAnalyzeStatement(...)` before analyzing each statement. That filter is the intended place to decide *which* AST statements participate in analysis.

## Existing filter mechanism

`AnalysisStatementFilter` (tooling model + internal API) supports:

- `AnalyzeEverythingFilter`
- `ConfiguringCallFilter`
- `TopLevelElementFilter`
- `NamedCallFilter`
- composition via `AndFilter` / `OrFilter` / `NotFilter`

(See `AnalysisStatementFilter.kt` and `AnalysisStatementFiltersImplementations`.)

`shouldAnalyzeStatement` only sees the language AST node (`DataStatement`) and a `isTopLevel` boolean. It does **not** see the schema. So it can trivially skip statements like `foo {}` by name, but it cannot decide *“skip usages of `bar` because `Foo` is empty”* purely from the AST.

## Two practical ways to skip `bar` usages

### Option A: Schema-driven property filtering

Make the extension’s properties (or the extension type itself) carry metadata saying *“this is an empty replacement”*, then skip any statement whose resolved receiver type is that empty type. This requires schema availability during analysis.

Where to hook:

1. Extend `SchemaItemMetadata` to add `EmptyExtensionMarker` (or similar).
2. In `ExtensionInfo.schemaFunction` (`schemaFromGradleExtensions.kt:118-136`), attach that metadata to the generated configuring function when the extension type has no visible DCL properties.
3. In `CodeAnalyzerImpl` or `NamedReferenceResolverImpl`, when resolving a qualified access whose receiver type carries the empty-replacement marker, silently skip the subtree by returning `null` and not collecting errors for that statement.

This keeps the AST in the tree but makes analysis no-op for the affected constructs.

### Option B: Statement-filter pre-scan

If you want a simpler, purely syntactic skip, add a new `AnalysisStatementFilter` subtype that receives (or pre-computes) the set of *extension names* that are empty, then rejects any `NamedReference`/`FunctionCall` whose chain root matches those names.

Example:

```kotlin
class SkipEmptyExtensionUsagesFilter(private val emptyExtensions: Set<String>) : AnalysisStatementFilter.EmptyExtensionFilter {
    override fun shouldAnalyzeStatement(statement: DataStatement, isTopLevel: Boolean): Boolean =
        statement !is RootAccessOn(emptyExtensions)
}
```

But this is fragile: it does not understand nested receivers, imports, or local aliases. Still, it’s the closest to *“skip constructs where `bar` is used”* without schema participation.

### Recommended approach: schema-aware skip during analysis

The cleanest place is in `AnalysisContext.runPropertyAccessResolution` / `doResolveQualifiedNamedReference` and in `FunctionCallResolver.findMemberFunction`. Before reporting an error, check whether the receiver’s type was introduced by an *empty replacement* extension:

```kotlin
private fun AnalysisContext.isEmptyExtension(type: DataType): Boolean {
    val clazz = type as? DataClass ?: return false
    return clazz.metadata.any { it is EmptyExtensionMarker }
}
```

For a qualified property access `foo.bar`:

1. `doResolveQualifiedNamedReference` resolves `foo` to a receiver origin.
2. If `foo`’s inferred type is the empty extension, do **not** collect `UnresolvedReference`; instead return `null` and optionally emit a **warning** (or nothing).

For a configuring function call `foo { ... }` or `foo.bar { ... }`:

1. `FunctionCallResolver.findMemberFunction` is invoked.
2. If the receiver type is the empty-replacement extension, skip/ignore the whole call subtree.

This requires:

- A new marker metadata type in `SchemaItemMetadata`.
- A way to propagate the *empty* status from the Gradle extension to the schema configuring function and its returned type.

In `schemaFromGradleExtensions.kt`, you could detect empty extension types and attach the marker there, or on the function returned object type.

## Key limitation to be aware of

Skipping analysis means subsequent statements depending on those skipped values (e.g., local variables initialized from `foo.bar`) will also be unresolved, so you must ensure skipping is _syntactically_ complete:
- skip the whole assignment whose lhs or rhs references the empty extension,
- skip the whole function call whose receiver is the empty extension,
- skip nested lambdas inside such calls.

`AnalyzedStatementUtils.collectIncludedStatements` already determines which statements are analyzed based on the filter; a correctly widened filter can drop entire subtrees.

## Files involved

- `platforms/core-configuration/declarative-dsl-tooling-models/src/main/kotlin/org/gradle/declarative/dsl/schema/SchemaItemMetadata.kt` — add marker metadata.
- `platforms/core-configuration/declarative-dsl-provider/src/main/kotlin/org/gradle/internal/declarativedsl/project/schemaFromGradleExtensions.kt` — attach marker when extension is replaced by empty `{}`.
- `platforms/core-configuration/declarative-dsl-core/src/main/kotlin/org/gradle/internal/declarativedsl/analysis/DefaultAnalysisSchema.kt` — implement marker serialization.
- `platforms/core-configuration/declarative-dsl-core/src/main/kotlin/org/gradle/internal/declarativedsl/analysis/NamedReferenceResolver.kt` — skip errors for accesses on empty extension types.
- `platforms/core-configuration/declarative-dsl-core/src/main/kotlin/org/gradle/internal/declarativedsl/analysis/FunctionCallResolver.kt` — skip resolving calls on empty extension types.
- `platforms/core-configuration/declarative-dsl-core/src/main/kotlin/org/gradle/internal/declarativedsl/analysis/AnalysisStatementFilter.kt` — optional syntactic fallback.

## Summary

Yes, you can skip constructs that use `bar` after `Foo` is replaced by an empty extension. The most reliable path is to mark the empty extension type as *“no-op in DCL”* via schema metadata, then teach the analyzer (`NamedReferenceResolver` and `FunctionCallResolver`) to silently ignore property and function accesses whose receiver carries that marker instead of emitting `UnresolvedReference`.