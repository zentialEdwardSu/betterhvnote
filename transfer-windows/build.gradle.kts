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
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.java.get()))
    }
}

dependencies {
    api(project(":transfer-core"))
    implementation(libs.jna)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

val generatedNativeResources = layout.buildDirectory.dir("generated/native-resources")
val nativeBuildDir = layout.buildDirectory.dir("native-x64")
sourceSets.main { resources.srcDir(generatedNativeResources) }

val buildWindowsNative = tasks.register<Exec>("buildWindowsNative") {
    onlyIf { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    inputs.files(fileTree("src/main/cpp"))
    outputs.file(generatedNativeResources.map { it.file("win32-x86-64/notelink_windows.dll") })
    commandLine(
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        file("src/main/cpp/build-native.ps1").absolutePath,
        "-ProjectDirectory", projectDir.absolutePath,
        "-OutputDirectory", generatedNativeResources.get().asFile.absolutePath,
        "-BuildDirectory", nativeBuildDir.get().asFile.absolutePath
    )
}

tasks.named("processResources") { dependsOn(buildWindowsNative) }
