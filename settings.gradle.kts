pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // PREFER_SETTINGS (not FAIL_ON_PROJECT_REPOS): EAS Build injects an
    // init.gradle that adds a project-level maven repository, which would make
    // the stricter mode fail the build. PREFER_SETTINGS still uses the repos
    // declared here while tolerating the CI-injected one.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "WhatsThatLinux"
include(":app")
