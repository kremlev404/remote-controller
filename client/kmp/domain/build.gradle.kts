plugins {
    alias(libs.plugins.remotecontroller.kmp.client)
    alias(libs.plugins.remotecontroller.kmp.wasm)
}

// The JVM target exists for the tests: these are plain rules, so they need no device.
kotlin {
    jvm()

    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
