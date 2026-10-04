import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    kotlin("jvm")
    id("gradlebuild.module-jar")
    id("gradlebuild.reproducible-archives")
    id("gradlebuild.repositories")
    id("gradlebuild.test-retry")
    id("gradlebuild.ci-reporting")
    id("gradlebuild.private-javadoc")
}

description = "A plugin that sets up a Kotlin DSL code that is shared between build-logic and runtime"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}
