import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.Lint
import dev.detekt.gradle.extensions.DetektExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.protobuf) apply false
}

fun Lint.configureSharedLint(includeReleaseBuilds: Boolean = true) {
    abortOnError = true
    warningsAsErrors = true
    checkDependencies = true
    if (includeReleaseBuilds) {
        checkReleaseBuilds = true
    }
    checkTestSources = true
    // Dependency upgrades are handled explicitly; an available update must not fail CI.
    disable += "GradleDependency"
    disable += "AndroidGradlePluginVersion"
}

fun Project.configureReleaseSigning(extension: ApplicationExtension) {
    val releaseStoreFile = providers.environmentVariable("ANDROID_RELEASE_KEYSTORE_FILE")
    val releaseStorePassword = providers.environmentVariable("ANDROID_RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS")
    val releaseSigningReady = listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
    ).all { it.isPresent }

    if (releaseSigningReady) {
        val releaseSigning = extension.signingConfigs.create("release") {
            storeFile = file(releaseStoreFile.get())
            storeType = "PKCS12"
            storePassword = releaseStorePassword.get()
            keyAlias = releaseKeyAlias.get()
            keyPassword = releaseStorePassword.get()
        }
        extension.buildTypes.getByName("release").signingConfig = releaseSigning
    }
}

val detektPluginId = libs.plugins.detekt.get().pluginId
val detektKtlintWrapper = libs.detekt.rules.ktlint.wrapper

subprojects {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir(name))

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        pluginManager.apply(detektPluginId)
    }
    pluginManager.withPlugin("com.android.application") {
        pluginManager.apply(detektPluginId)
        extensions.configure<ApplicationExtension> {
            lint { configureSharedLint() }
            configureReleaseSigning(this)
        }
    }
    pluginManager.withPlugin("com.android.library") {
        pluginManager.apply(detektPluginId)
        extensions.configure<LibraryExtension> {
            lint { configureSharedLint() }
        }
    }
    pluginManager.withPlugin("com.android.lint") {
        extensions.configure<Lint> {
            configureSharedLint(includeReleaseBuilds = false)
        }
    }
    pluginManager.withPlugin("dev.detekt") {
        dependencies.add("detektPlugins", detektKtlintWrapper)
        extensions.configure<DetektExtension> {
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            buildUponDefaultConfig = false
            allRules = false
            parallel = true
            ignoreFailures = false
        }
    }
}
