plugins {
    id("gradlebuild.build-logic.kotlin-dsl-gradle-plugin")
}

description = "Provides plugins that combine and configure other plugins for different kinds of Gradle subprojects"

dependencies {
    implementation("gradlebuild:basics")
    implementation("gradlebuild:module-identity")
    implementation("gradlebuild:publishing")

    implementation(projects.buildquality)
    implementation(projects.dependencyModules)
    implementation(projects.jvm)
    implementation(projects.packaging)

    implementation(buildLibs.kgp)
}
