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
}

dependencies {
  implementation("com.google.code.gson:gson:2.14.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
  testImplementation(kotlin("test"))
}
