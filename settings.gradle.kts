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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "remote-controller"

include(":shared")
include(":client:core")
include(":client:domain")
include(":client:data")
include(":client:storage")
include(":client:feature:connect:api")
include(":client:feature:connect:impl")
include(":client:feature:remote:api")
include(":client:feature:remote:impl")
include(":client:feature:settings:api")
include(":client:feature:settings:impl")
include(":client:feature:umbrella")
include(":client:feature:common")
include(":android")
include(":desktop")
