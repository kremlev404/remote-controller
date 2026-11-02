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
    // Kotlin's browser target adds the Node.js download repository itself.
    // PREFER_SETTINGS keeps every repository declared here and ignores that extra one.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        ivy {
            name = "Node Distributions"
            url = uri("https://nodejs.org/dist")
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy {
            name = "Yarn Distributions"
            url = uri("https://github.com/yarnpkg/yarn/releases/download")
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]).[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        ivy {
            name = "Binaryen Distributions"
            url = uri("https://github.com/WebAssembly/binaryen/releases/download")
            patternLayout {
                artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

rootProject.name = "remote-controller"

include(":shared")

include(":server:desktop")
include(":server:host:screencast")
include(":server:relay")

include(":client:android")
include(":client:web")
include(":client:kmp:core")
include(":client:kmp:domain")
include(":client:kmp:data")
include(":client:kmp:storage")
include(":client:kmp:feature:connect:api")
include(":client:kmp:feature:connect:impl")
include(":client:kmp:feature:remote:api")
include(":client:kmp:feature:remote:impl")
include(":client:kmp:feature:settings:api")
include(":client:kmp:feature:settings:impl")
include(":client:kmp:feature:screen:api")
include(":client:kmp:feature:screen:impl")
include(":client:kmp:feature:umbrella")
include(":client:kmp:feature:common")
