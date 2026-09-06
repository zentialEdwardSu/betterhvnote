plugins {
    id("com.android.application") version "9.3.2" apply false
    id("com.android.library") version "9.3.2" apply false
    id("com.android.lint") version "9.3.2" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.compose") version "1.12.0" apply false
    id("app.cash.sqldelight") version "2.3.2" apply false
    id("dev.detekt") version "2.0.0-alpha.6" apply false
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        pluginManager.apply("dev.detekt")
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.android") {
        pluginManager.apply("dev.detekt")
    }
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            lint {
                abortOnError = true
                warningsAsErrors = true
                checkDependencies = true
                checkReleaseBuilds = true
                checkTestSources = true
            }
        }
    }
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            lint {
                abortOnError = true
                warningsAsErrors = true
                checkDependencies = true
                checkReleaseBuilds = true
                checkTestSources = true
            }
        }
    }
    pluginManager.withPlugin("com.android.lint") {
        extensions.configure<com.android.build.api.dsl.Lint> {
            abortOnError = true
            warningsAsErrors = true
            checkDependencies = true
            checkTestSources = true
        }
    }
    pluginManager.withPlugin("dev.detekt") {
        dependencies.add(
            "detektPlugins",
            "dev.detekt:detekt-rules-ktlint-wrapper:2.0.0-alpha.6",
        )
        extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            buildUponDefaultConfig = false
            allRules = false
            parallel = true
            ignoreFailures = false
        }
    }
}
