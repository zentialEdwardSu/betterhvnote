plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
    id("app.cash.sqldelight") version "2.0.2" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        pluginManager.apply("io.gitlab.arturbosch.detekt")
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.android") {
        pluginManager.apply("io.gitlab.arturbosch.detekt")
    }
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            lint {
                abortOnError = true
                warningsAsErrors = true
                checkDependencies = true
                checkReleaseBuilds = true
                checkTestSources = true
                baseline = file("lint-baseline.xml")
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
                baseline = file("lint-baseline.xml")
            }
        }
    }
    pluginManager.withPlugin("io.gitlab.arturbosch.detekt") {
        dependencies.add("detektPlugins", "io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            baseline = file("detekt-baseline.xml")
            buildUponDefaultConfig = true
            allRules = true
            parallel = true
            ignoreFailures = false
        }
    }
}
