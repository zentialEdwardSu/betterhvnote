import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.sqldelight")
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
    resources.srcDir("src/main/resources")
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
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(project(":transfer-core"))
    implementation(project(":transfer-windows"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("app.cash.sqldelight:runtime:2.0.2")
    implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.betterhv.note.sender.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            modules("java.instrument", "java.sql", "jdk.unsupported")
            packageName = "NoteLink"
            packageVersion = "0.2.3"
            description = "Transfer images, text and BetterHvNote exports over BLE and Wi-Fi Direct"
            vendor = "BetterHv"
            windows {
                iconFile.set(file("src/main/resources/icons/notelink.ico"))
                menuGroup = "NoteLink"
                upgradeUuid = "0ec5e7be-a75d-4aaa-b38d-f43f3823bd87"
            }
        }
    }
}

val addWindowsFirewallActions by tasks.registering(Exec::class) {
    onlyIf { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    dependsOn(":transfer-windows:buildWindowsNative")
    val msiDirectory = layout.buildDirectory.dir("compose/binaries/main/msi")
    val actionExecutable = project(":transfer-windows").layout.buildDirectory.file("native-x64/notelink_firewall_action.exe")
    inputs.file(actionExecutable)
    outputs.dir(msiDirectory)
    commandLine(
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        rootProject.file("tools/windows-installer/add-firewall-actions.ps1").absolutePath,
        "-MsiDirectory", msiDirectory.get().asFile.absolutePath,
        "-ActionExecutable", actionExecutable.get().asFile.absolutePath
    )
}

tasks.matching { it.name == "packageMsi" }.configureEach {
    finalizedBy(addWindowsFirewallActions)
}
