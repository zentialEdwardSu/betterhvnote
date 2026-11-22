import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
}

val javaVersion = JavaVersion.toVersion(libs.versions.java.get())

java {
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.java.get())) }
}

dependencies {
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}
