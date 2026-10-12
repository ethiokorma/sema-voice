pluginManagement {
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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SemaVoice"
include(":app")
include(":keyboard")
include(":core:common")
include(":core:audio")
include(":core:speech")
include(":core:translation")
include(":core:writing")
include(":core:models")
include(":core:database")
include(":core:settings")
include(":core:tts")
include(":core:runtime")
