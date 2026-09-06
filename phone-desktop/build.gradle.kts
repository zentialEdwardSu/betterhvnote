import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.android.lint")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.sqldelight")
    id("dev.detekt")
}

val noteLinkVersion = providers.gradleProperty("noteLinkVersion").get()
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
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets {
        main {
            kotlin.srcDir(rootProject.file("phone-app/src/commonMain/kotlin"))
            kotlin.srcDir("src/main/kotlin")
        }
        test {
            kotlin.srcDir("src/test/kotlin")
        }
    }
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
            srcDirs(rootProject.file("phone-app/src/commonMain/sqldelight/queue"))
        }
        create("ExportInboxDatabase") {
            packageName.set("com.betterhv.note.sender.shared.db.inbox")
            srcDirs(rootProject.file("phone-app/src/commonMain/sqldelight/inbox"))
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation(project(":transfer-core"))
    implementation(project(":transfer-windows"))
    implementation(project(":update-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("app.cash.sqldelight:runtime:2.3.2")
    implementation("app.cash.sqldelight:sqlite-driver:2.3.2")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.betterhv.note.sender.desktop.MainKt"
        nativeDistributions {
            modules("java.instrument", "java.sql", "jdk.unsupported")
            packageName = "NoteLink"
            packageVersion = noteLinkVersion
            description = "Transfer images, text and BetterHvNote exports over BLE and Wi-Fi Direct"
            vendor = "BetterHv"
            windows {
                iconFile.set(file("src/main/resources/icons/notelink.ico"))
            }
        }
    }
}

val packagePortableZip = tasks.register<Zip>("packagePortableZip") {
    dependsOn("createDistributable", ":transfer-windows:buildWindowsNative")
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
