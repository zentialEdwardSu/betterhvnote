plugins {
    id("java-library")
    alias(libs.plugins.android.lint)
}

val javaVersion = JavaVersion.toVersion(libs.versions.java.get())

// Pure-Java stubs for Hanvon ROM framework classes (android.os.HvPenDraw*).
// Consumed as compileOnly by :app so they satisfy the compiler but are
// never packaged — the N10Pro ROM provides the real implementations.
java {
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}
