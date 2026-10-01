# Your task

Implement lenient Gradle build.
First create Plan.md planning the implementation for each step.
Second, implement Plan.md spawning subagent for each step.
Make a single commit per plan step.

# Kotlin DSL

If compilation fails - remove smallest possible statement that fails to compile and try to recompile.

# Skip unknown plugins

When plugin is unknown, e.g. id ("foo") version "bar", emit a warning, but do not fail the build.

# Skip unknown dependencies

When dependency can not be fetched, emit a warning but do not fail the build

# Ignore unknown extensions

When extension is unknown, e.g.

bar {
    foo = 1
}

emit a warning "Extension bar is unknown" and skip the extension.

# Ignore unknown type usages

When unknown extension or type is used, e.g.
---
tasks.withType<Foo>() {

}
---

and Foo is unknown type, skip the statement.
---
---

When unknown type is used in the statement, e.g.
---
tasks.withType<JavaCompile>() {
    sourceCompatibility = bar.foo
}
---

skip the statement:
---
tasks.withType<JavaCompile>() {
}
---





