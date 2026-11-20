plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val nexusVersionName = providers.environmentVariable("NEXUS_VERSION_NAME").orElse("0.8.0").get()
val nexusVersionCode = providers.environmentVariable("NEXUS_VERSION_CODE").orNull?.toIntOrNull() ?: 8
val nexusSigningStoreFile = providers.environmentVariable("NEXUS_SIGNING_STORE_FILE").orNull
val nexusSigningStorePassword = providers.environmentVariable("NEXUS_SIGNING_STORE_PASSWORD").orNull
val nexusSigningKeyAlias = providers.environmentVariable("NEXUS_SIGNING_KEY_ALIAS").orNull
val nexusSigningKeyPassword = providers.environmentVariable("NEXUS_SIGNING_KEY_PASSWORD").orNull
val nexusSigningConfigured = listOf(
    nexusSigningStoreFile, nexusSigningStorePassword, nexusSigningKeyAlias, nexusSigningKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "ai.hybrid.commandcenter"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.hybrid.commandcenter"
        minSdk = 26
        targetSdk = 35
        versionCode = nexusVersionCode
        versionName = nexusVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
            // CI emulator only: an x86_64 copy of the native engine so the on-device
            // model path runs natively on the x86_64 emulator. Phone APKs stay arm64.
            if (providers.gradleProperty("nexus.emulatorAbi").orNull == "true") abiFilters += "x86_64"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // The engine is always optimised: testers install debug APKs, and an -O0
                // llama.cpp answers many times slower (CI saw minutes per sentence).
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                // 16 KB page devices (Android 15+) refuse 4 KB-aligned libraries.
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    signingConfigs {
        create("nexusRelease") {
            if (nexusSigningConfigured) {
                storeFile = file(requireNotNull(nexusSigningStoreFile))
                storePassword = nexusSigningStorePassword
                keyAlias = nexusSigningKeyAlias
                keyPassword = nexusSigningKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("nexusRelease")
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        // The CPU-specific llama.cpp backends are found by scanning nativeLibraryDir,
        // so the libraries must be extracted there on install.
        jniLibs { useLegacyPackaging = true }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Robolectric's Conscrypt reads InetAddress internals when it serves TLS
                // (NEXUS Mesh tests run real mutual-TLS sockets on the JVM).
                test.jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED")
                // The Mesh host-peer E2E reads its target from the CI environment.
                listOf("MESH_LINK", "MESH_TARGET", "MESH_OUT").forEach { key ->
                    System.getenv(key)?.let { test.environment(key, it) }
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Robolectric offline: the android-all jar arrives as a normal Gradle
// dependency (proxy-friendly, deterministic in CI) instead of Robolectric's
// own runtime fetcher.
val robolectricAndroidAll: Configuration by configurations.creating

tasks.withType<Test>().configureEach {
    val jars = robolectricAndroidAll
    val targetDir = layout.buildDirectory.dir("robolectric-android-all")
    doFirst {
        val dir = targetDir.get().asFile
        dir.mkdirs()
        jars.files.forEach { jar ->
            val copy = File(dir, jar.name)
            if (!copy.exists()) jar.copyTo(copy, overwrite = false)
        }
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", dir.absolutePath)
    }
    // `gradle :app:testDebugUnitTest -Pnexus.screenshots=true` renders the gallery;
    // CI runs without it, so screenshots never gate or slow down the build.
    if (project.findProperty("nexus.screenshots") == "true") {
        systemProperty("roborazzi.test.record", "true")
    }
}

dependencies {
    robolectricAndroidAll("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    // 1.11.0 is required at runtime: LiteRT-LM 0.16.1 calls the static SendChannel.close$default
    // that 1.10.x lacks, which crashed the app when a Gemma answer finished (seen on the CI emulator).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // On-device OCR (Apache 2.0, Google ML Kit): Latin script only — the
    // Cyrillic model is not published, which the UI states honestly.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // NEXUS Mesh pairing: QR codes are drawn with ZXing core (Apache-2.0) and scanned
    // with the Google code scanner (no camera permission; UI provided by Play services).
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Shizuku (Apache 2.0): ADB-level privileges over Binder, no root required.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // PDF text layer (Apache 2.0): real parser for Archive materials.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Kotlin metadata 2.3.0; compatible with this project's Kotlin 2.2.20 toolchain.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // JVM screen render smoke tests: every screen must compose without crashing.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // JVM screenshots of real screens (Apache 2.0). No-op unless recording is requested.
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.43.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Real JVM JSON implementation; Android's unit-test stubs throw "Method not mocked".
    testImplementation("org.json:json:20260814")
    // Use a real JVM JSON implementation in local unit tests instead of android.jar mock stubs.
    testImplementation("org.json:json:20260814")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Real-device smoke test on an emulator in CI (see .github/workflows/nexus-android-emulator.yml).
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
