plugins {
    id("gradlebuild.build-logic.kotlin-dsl-gradle-plugin")
}

description = "Provides plugins to configure quality checks (incubating report, CodeNarc, et al)"

dependencies {
    implementation("gradlebuild:basics")

    implementation(buildLibs.codenarc) {
        exclude(group = "org.apache.groovy")
        exclude(group = "org.codehaus.groovy")
    }
    implementation(buildLibs.javaParserSymbolSolver) {
        exclude(group = "com.google.guava")
    }
    compileOnly(buildLibs.kotlinCompilerEmbeddable) {
        because("Required by IncubatingApiReportTask and Gradle10RemovalReportTask")
    }

    testImplementation(buildLibs.commonsLang3)
    testImplementation(buildLibs.kotlinCompilerEmbeddable) {
        because("RemovalReportWorkActionTest parses Kotlin sources via KotlinSourceParser")
    }
}

configurations.testRuntimeClasspath {
    // https://youtrack.jetbrains.com/issue/KT-87492/KGP-jar-bundles-a-partial-and-unshaded-copy-of-org.jetbrains.kotlin.com.intellij.-classes-that-collide-with-kotlin-compiler
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-gradle-plugin")
}
