plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.betterhv.note"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.betterhv.note"
        minSdk = 28
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "0.1-phase6"
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

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".uitest"
        }
        release {
            isMinifyEnabled = false
            // The installed development build uses the debug key. Keeping that
            // signature lets `adb install -r` preserve the production data.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    // ROM framework stubs: compile against, never package (device provides them).
    compileOnly(project(":framework-stubs"))
    androidTestCompileOnly(project(":framework-stubs"))
    implementation(project(":transfer-core"))
    implementation(project(":transfer-android"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Android's iText port combines revision-cached single-page PDFs. OpenPDF
    // depends on java.awt.Color, which is unavailable on Android.
    implementation("com.itextpdf:itextg:5.5.10")

    // The ink core (com.betterhv.note.ink, excluding InkRenderer) has no Android
    // dependencies, so plain JUnit on the JVM covers it -- no Robolectric needed.
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
