import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.sqldelight)
}

val javaVersion = JavaVersion.toVersion(libs.versions.java.get())

val noteLinkVersion = providers.gradleProperty("noteLinkVersion").get()
val jpackageVersion = Regex("""^\d+\.\d+\.\d+$""").matchEntire(noteLinkVersion)
    ?.value
    ?.takeIf { it != "0.0.0" }
    ?: "0.0.1"
val generatedVersionResources = layout.buildDirectory.dir("generated/version-resources")

val generateVersionResource = tasks.register("generateVersionResource") {
    val outputFile = generatedVersionResources.map { it.file("notelink-version.properties") }
    inputs.property("version", noteLinkVersion)
    outputs.file(outputFile)
    doLast {
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText("version=$noteLinkVersion\n")
        }
    }
}

java {
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.java.get())) }
}

sourceSets.main {
    resources.srcDir(generatedVersionResources)
}

tasks.named("processResources") { dependsOn(generateVersionResource) }

tasks.withType<Test>().configureEach {
    inputs.property("noteLinkVersion", noteLinkVersion)
    systemProperty("notelink.expectedVersion", noteLinkVersion)
}

sqldelight {
    databases {
        create("SenderQueueDatabase") {
            packageName.set("com.betterhv.note.sender.shared.db.queue")
            srcDirs("src/main/sqldelight/queue")
        }
        create("ExportInboxDatabase") {
            packageName.set("com.betterhv.note.sender.shared.db.inbox")
            srcDirs("src/main/sqldelight/inbox")
        }
        create("SendHistoryDatabase") {
            packageName.set("com.betterhv.note.sender.shared.db.history")
            srcDirs("src/main/sqldelight/history")
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.desktop.material3)
    implementation(libs.compose.desktop.material.icons.extended)
    implementation(project(":transfer-core"))
    implementation(project(":transfer-windows"))
    implementation(project(":update-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.sqlite.jdbc)
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.sqlite.driver)
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.betterhv.note.sender.desktop.MainKt"
        nativeDistributions {
            modules("java.instrument", "java.sql", "jdk.unsupported")
            packageName = "NoteLink"
            packageVersion = jpackageVersion
            description = "Transfer images, text and BetterHvNote exports over BLE and Wi-Fi Direct"
            vendor = "BetterHv"
            windows {
                iconFile.set(file("src/main/resources/icons/notelink.ico"))
            }
        }
    }
}

val patchWindowsLauncherManifest = tasks.register("patchWindowsLauncherManifest") {
    dependsOn("createDistributable")
    onlyIf { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    val launcherExe = layout.buildDirectory.file("compose/binaries/main/app/NoteLink/NoteLink.exe")
    val tempDir = layout.buildDirectory.dir("tmp/windows-manifest")
    val stampFile = layout.buildDirectory.file("tmp/windows-manifest/patched.stamp")
    outputs.file(stampFile)
    outputs.upToDateWhen { false }
    doLast {
        val exeFile = launcherExe.get().asFile
        require(exeFile.isFile) { "Missing Compose Desktop launcher: $exeFile" }
        val kitsBin = file("C:/Program Files (x86)/Windows Kits/10/bin")
        val mtExe = kitsBin.takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.filter {
                it.isFile && it.name.equals("mt.exe", ignoreCase = true) &&
                    it.parentFile.name.equals("x64", ignoreCase = true)
            }
            ?.sortedByDescending { it.absolutePath }
            ?.firstOrNull()
            ?: error("Windows SDK mt.exe (x64) was not found under $kitsBin")
        val work = tempDir.get().asFile.apply { mkdirs() }
        val extracted = work.resolve("orig.manifest")
        val patched = work.resolve("utf8.manifest")
        fun runMt(vararg args: String) {
            var lastExit = -1
            var lastOutput = ""
            repeat(5) { attempt ->
                val process = ProcessBuilder(mtExe.absolutePath, *args)
                    .redirectErrorStream(true)
                    .start()
                lastOutput = process.inputStream.bufferedReader().readText()
                lastExit = process.waitFor()
                if (lastExit == 0) return
                if (attempt < 4) Thread.sleep(300L * (attempt + 1))
            }
            error("mt.exe failed (exit $lastExit): $lastOutput")
        }
        exeFile.setWritable(true)
        runMt("-nologo", "-inputresource:${exeFile.absolutePath};#1", "-out:${extracted.absolutePath}")
        val xml = extracted.readText(Charsets.UTF_8)
        if (!xml.contains("activeCodePage")) {
            val tag = """<activeCodePage xmlns="http://schemas.microsoft.com/SMI/2019/WindowsSettings">UTF-8</activeCodePage>"""
            val updated = when {
                xml.contains("</asmv3:windowsSettings>") ->
                    xml.replace("</asmv3:windowsSettings>", "$tag</asmv3:windowsSettings>")
                xml.contains("</windowsSettings>") ->
                    xml.replace("</windowsSettings>", "$tag</windowsSettings>")
                else -> xml.replace(
                    "</assembly>",
                    """<application xmlns="urn:schemas-microsoft-com:asm.v3"><windowsSettings>$tag</windowsSettings></application></assembly>""",
                )
            }
            patched.writeText(updated, Charsets.UTF_8)
            runMt("-nologo", "-manifest", patched.absolutePath, "-outputresource:${exeFile.absolutePath};#1")
        }
        stampFile.get().asFile.writeText("utf8-manifest-ok\n")
    }
}

val packagePortableZip = tasks.register<Zip>("packagePortableZip") {
    dependsOn(patchWindowsLauncherManifest, ":transfer-windows:buildWindowsNative")
    val archiveRoot = "NoteLink-$noteLinkVersion"
    val applicationImage = layout.buildDirectory.dir("compose/binaries/main/app/NoteLink")
    val windowsNativeDll = project(":transfer-windows").layout.buildDirectory
        .file("generated/native-resources/win32-x86-64/notelink_windows.dll")
    inputs.dir(applicationImage)
    inputs.file(windowsNativeDll)
    inputs.dir(layout.projectDirectory.dir("src/main/portable"))
    archiveFileName.set("NoteLink-$noteLinkVersion-windows-x64.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(applicationImage) { into(archiveRoot) }
    from(windowsNativeDll) { into(archiveRoot) }
    from(layout.projectDirectory.dir("src/main/portable")) { into(archiveRoot) }
    doFirst {
        require(applicationImage.get().asFile.resolve("NoteLink.exe").isFile) {
            "Compose Desktop application image is missing NoteLink.exe"
        }
        require(windowsNativeDll.get().asFile.isFile) {
            "Windows native library is missing notelink_windows.dll"
        }
    }
}
