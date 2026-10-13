plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.termpilot.app"
    compileSdk = 34
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "com.termpilot.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES,NOTICE,LICENSE,LICENSE.txt,NOTICE.txt}"
            // sshj + BouncyCastle ship overlapping metadata; first one wins.
            pickFirsts += "**/bcprov*.properties"
            // Kotlin multiplatform artifacts each embed metadata markers.
            excludes += "commonMain/**"
            excludes += "nativeMain/**"
            // BouncyCastle OSGI manifests collide across bcprov/bcpkix/bcutil.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/kotlin-project-structure-metadata.json"
        }
    }
}

// The sandbox egress proxy breaks Java HTTP, so all artifacts are mirrored
// into local-repo/ (see tools/fetch_deps.py) and Gradle runs offline.
// Offline, Gradle cannot do version listings, so version *ranges* in POMs
// (e.g. sshj's bcutil-jdk18on:[1.80,1.81)) are pinned to the mirrored
// resolution recorded in gradle-forces.txt.
configurations.all {
    resolutionStrategy {
        rootDir.resolve("gradle-forces.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { force(it) }
    }
    // lifecycle-viewmodel-ktx (old) was merged into lifecycle-viewmodel-android;
    // both ship ViewModelKt -> duplicate classes. Drop the old one.
    exclude(group = "androidx.lifecycle", module = "lifecycle-viewmodel-ktx")
}

dependencies {
    // NOTE: versions are pinned explicitly (matching compose-bom 2024.06.00)
    // because the sandbox egress proxy breaks Java HTTP; all artifacts are
    // pre-mirrored into local-repo/ by tools/fetch_deps.py.

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // SSH
    implementation("com.hierynomus:sshj:0.40.0")

    // Terminal emulation + view: vendored Termux modules (patched for SSH).
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))

    // HTTP for the LLM API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Encrypted storage for secrets (API keys, passwords, private keys)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Host entries
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling:1.6.8")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.6.8")
}
