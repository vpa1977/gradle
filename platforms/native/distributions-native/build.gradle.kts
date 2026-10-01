plugins {
    id("gradlebuild.distribution.packaging")
    id("gradlebuild.public-api-jar")
}

description = "The collector project for the 'native' portion of the Gradle distribution"

dependencies {
    coreRuntimeOnly(platform(projects.corePlatform))

    agentsRuntimeOnly(projects.instrumentationAgent)

    pluginsRuntimeOnly(platform(projects.distributionsJvm)) {
        because("the native platform requires JVM ecosystem plugins")
    }
    pluginsRuntimeOnly(platform(projects.distributionsPublishing)) {
        because("configuring publishing is part of the 'language native' support")
    }

    pluginsRuntimeOnly(projects.languageNative)
    pluginsRuntimeOnly(projects.testingNative)
    pluginsRuntimeOnly(projects.pluginsModelNative)
}
