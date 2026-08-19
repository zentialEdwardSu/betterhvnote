plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":transfer-core"))
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

val generatedNativeResources = layout.buildDirectory.dir("generated/native-resources")
sourceSets.main { resources.srcDir(generatedNativeResources) }

val buildWindowsNative by tasks.registering(Exec::class) {
    onlyIf { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    inputs.files(fileTree("src/main/cpp"))
    outputs.file(generatedNativeResources.map { it.file("win32-x86-64/notelink_windows.dll") })
    commandLine(
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        file("src/main/cpp/build-native.ps1").absolutePath,
        "-ProjectDirectory", projectDir.absolutePath,
        "-OutputDirectory", generatedNativeResources.get().asFile.absolutePath
    )
}

tasks.named("processResources") { dependsOn(buildWindowsNative) }
