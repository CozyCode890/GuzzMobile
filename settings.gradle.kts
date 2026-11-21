pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Tải AAR sherpa-onnx và model tách giọng, kiểm SHA-256 (D79, gradle/guzz-deps.txt).
plugins {
    id("guzz.deps")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "GuzzMobile"

include(":app")
include(":core:common")
include(":core:audio")
include(":core:media")
include(":core:gemini")
include(":core:speaker")
include(":core:diarization")
include(":core:pipeline")
include(":core:remote")
include(":server")
include(":feature:queue")
include(":feature:automation")
