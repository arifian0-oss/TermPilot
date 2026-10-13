// Vendored Termux terminal-emulator (Apache 2.0, see VENDORED.md).
// Patched: TerminalSession external-I/O mode for SSH bridging.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.terminal"
    compileSdk = 34
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
