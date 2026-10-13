// Vendored Termux terminal-view (Apache 2.0, see ../terminal-emulator/VENDORED.md).
// Used unmodified; driven by the patched TerminalSession.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.view"
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

dependencies {
    api(project(":terminal-emulator"))
    implementation("androidx.annotation:annotation:1.7.1")
}
