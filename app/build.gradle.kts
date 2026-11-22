import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val javaVersion = JavaVersion.toVersion(libs.versions.java.get())

extensions.configure<ApplicationExtension> {
    namespace = "com.betterhv.note"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.betterhv.note"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
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

    ndkVersion = libs.versions.ndk.get()

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".uitest"
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    lint {
        // BetterHvNote is built for the ARM64-only Hanvon N10Pro, not ChromeOS.
        disable += "ChromeOsAbiSupport"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.java.get())) }
}

dependencies {
    // ROM framework stubs: compile against, never package (device provides them).
    compileOnly(project(":framework-stubs"))
    androidTestCompileOnly(project(":framework-stubs"))
    implementation(project(":transfer-core"))
    implementation(project(":transfer-android"))
    implementation(project(":update-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)
    implementation(libs.gson)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // MuPDF writes editable Ink annotations and grafts cached PDF pages.
    implementation(libs.mupdf.fitz)

    // The ink core (com.betterhv.note.ink, excluding InkRenderer) has no Android
    // dependencies, so plain JUnit on the JVM covers it -- no Robolectric needed.
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
