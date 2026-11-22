import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.protobuf)
}

val javaVersion = JavaVersion.toVersion(libs.versions.java.get())

protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins.getByName("java").option("lite")
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

lint {
    // Android consumers require API 29; TrulyRandom only applies to Android 4.3 and older.
    disable += "TrulyRandom"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.protobuf.javalite)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}
