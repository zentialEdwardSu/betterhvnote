import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.android.lint")
    id("dev.detekt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets {
        main {
            kotlin.srcDir("src/commonMain/kotlin")
            kotlin.srcDir("src/main/kotlin")
        }
        test {
            kotlin.srcDir("src/commonTest/kotlin")
            kotlin.srcDir("src/test/kotlin")
        }
    }
}

lint {
    // Android consumers require API 29; TrulyRandom only applies to Android 4.3 and older.
    disable += "TrulyRandom"
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
