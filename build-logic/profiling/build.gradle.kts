plugins {
    id("gradlebuild.build-logic.kotlin-dsl-gradle-plugin")
}

description = "Provides plugins that configure profiling tools (jmh and Build Scan)"

dependencies {
    implementation(buildLibs.develocityPlugin)

    implementation("gradlebuild:basics")
    implementation("gradlebuild:module-identity")

    implementation(projects.documentation) {
        // Dokka is only used by GradleKotlinDslReferencePlugin, applied solely to the
        // (removed) :docs project; keep it off the convention-plugin classpath
        exclude(group = "org.jetbrains.dokka")
    }
    implementation(projects.integrationTesting)
    implementation(projects.jvm)

    implementation(buildLibs.jmhPlugin)
}
