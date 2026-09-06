import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.detekt")
}

val releaseStoreFile = providers.environmentVariable("ANDROID_RELEASE_KEYSTORE_FILE")
val releaseStorePassword = providers.environmentVariable("ANDROID_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS")
val releaseSigningReady = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
).all { it.isPresent }

extensions.configure<ApplicationExtension> {
    namespace = "com.betterhv.note"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.betterhv.note"
        minSdk = 29
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = providers.gradleProperty("noteVersionCode").get().toInt()
        versionName = providers.gradleProperty("noteVersion").get()
        buildConfigField("long", "BUILD_TIME_EPOCH_MILLIS", "${System.currentTimeMillis()}L")
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
            }
        }
    }

    ndkVersion = "30.0.15729638"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseStoreFile.get())
                storeType = "PKCS12"
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseStorePassword.get()
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".uitest"
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseSigningReady) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
            // These binaries were recovered from the Hanvon ROM and have 4 KiB ELF LOAD
            // alignment. Production rendering no longer calls them; keeping them in the
            // APK would make an otherwise compatible app fail on 16 KiB page-size devices.
            // Keep the source artifacts for ROM research, but never package them.
            excludes += setOf(
                "**/libc++_shared.so",
                "**/libhvdither.so",
                "**/libhw_PenEngine.so",
                "**/libHwGraphUtil.so"
            )
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // ROM framework stubs: compile against, never package (device provides them).
    compileOnly(project(":framework-stubs"))
    androidTestCompileOnly(project(":framework-stubs"))
    implementation(project(":transfer-core"))
    implementation(project(":transfer-android"))
    implementation(project(":update-core"))

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // MuPDF writes editable Ink annotations and grafts cached PDF pages.
    implementation("com.artifex.mupdf:fitz:1.28.0")

    // The ink core (com.betterhv.note.ink, excluding InkRenderer) has no Android
    // dependencies, so plain JUnit on the JVM covers it -- no Robolectric needed.
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
}
