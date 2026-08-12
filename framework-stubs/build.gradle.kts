plugins {
    id("java-library")
}

// Pure-Java stubs for Hanvon ROM framework classes (android.os.HvPenDraw*).
// Consumed as compileOnly by :app so they satisfy the compiler but are
// never packaged — the N10Pro ROM provides the real implementations.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
