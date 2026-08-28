pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            name = "fongMiMedia3"
            url = uri(rootDir.resolve("third_party/media3-fongmi/repository"))
            content { includeGroup("androidx.media3") }
        }
        // Keep newly released stable artifacts reachable when a workstation-level mirror lags.
        maven(url = "https://repo1.maven.org/maven2")
        google()
        mavenCentral()
    }
}

rootProject.name = "OpenList"
include(":app")
