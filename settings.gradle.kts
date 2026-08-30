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
        maven {
            url = uri("https://maven.ghostscript.com")
            content { includeGroup("com.artifex.mupdf") }
        }
    }
}

rootProject.name = "BetterHvNote"
include(":app")
include(":framework-stubs")
include(":transfer-core")
include(":transfer-android")
include(":phone-app")
include(":phone-desktop")
include(":transfer-windows")
