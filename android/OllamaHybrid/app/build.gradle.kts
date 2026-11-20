plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val nexusVersionName = providers.environmentVariable("NEXUS_VERSION_NAME").orElse("0.6.0").get()
val nexusVersionCode = providers.environmentVariable("NEXUS_VERSION_CODE").orNull?.toIntOrNull() ?: 6
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
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
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
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Real JVM JSON implementation; Android's unit-test stubs throw "Method not mocked".
    testImplementation("org.json:json:20260814")
    // Use a real JVM JSON implementation in local unit tests instead of android.jar mock stubs.
    testImplementation("org.json:json:20260814")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
