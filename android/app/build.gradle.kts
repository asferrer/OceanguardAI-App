import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Research contribution: NAS credentials injected from local.properties (never committed)
val contribProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

/**
 * Derives version from the latest git tag.
 * Expected tag format: v1.0.0, v1.2.3, etc.
 * Falls back to "0.0.0-dev" if no tags exist.
 *
 * - Tagged commit:     "1.0.0"
 * - After a tag:       "1.0.0-5-gabcdef" (5 commits after tag)
 * - No tags at all:    "0.0.0-dev"
 */
fun gitVersionName(): String {
    return try {
        val process = ProcessBuilder("git", "describe", "--tags", "--match", "v*")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        if (process.exitValue() == 0 && output.isNotEmpty()) {
            // "v1.0.0" -> "1.0.0", "v1.0.0-3-gabcdef" -> "1.0.0-3-gabcdef"
            output.removePrefix("v")
        } else {
            "0.0.0-dev"
        }
    } catch (_: Exception) {
        "0.0.0-dev"
    }
}

/**
 * Derives versionCode from the total git commit count.
 * Auto-increments with every commit — no manual bumping needed.
 */
fun gitVersionCode(): Int {
    return try {
        val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        if (process.exitValue() == 0) output.toIntOrNull() ?: 1 else 1
    } catch (_: Exception) {
        1
    }
}

android {
    namespace = "com.oceanguard.ai"
    compileSdk = 36

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("OCEANGUARD_KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("OCEANGUARD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OCEANGUARD_KEY_ALIAS")
                keyPassword = System.getenv("OCEANGUARD_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.oceanguard.ai"
        minSdk = 26  // Android 8.0
        targetSdk = 35
        versionCode = gitVersionCode()
        versionName = gitVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Research contribution: WebDAV credentials (read from local.properties)
        buildConfigField("String", "CONTRIB_URL",  "\"${contribProps.getProperty("CONTRIBUTION_WEBDAV_URL", "")}\"")
        buildConfigField("String", "CONTRIB_USER", "\"${contribProps.getProperty("CONTRIBUTION_WEBDAV_USER", "")}\"")
        buildConfigField("String", "CONTRIB_PASS", "\"${contribProps.getProperty("CONTRIBUTION_WEBDAV_PASS", "")}\"")

        vectorDrawables {
            useSupportLibrary = true
        }

        // Native library configuration — arm64-v8a only (S22 Ultra is ARM64; halves NDK build time)
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=ON",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // CRITICAL: Don't compress model files (gguf added for Qwen3.5 GGUF models)
    androidResources {
        noCompress += listOf("bin", "tflite", "litert", "task", "litertlm", "gguf")
    }

    // Asset pack configuration (for large model files)
    assetPacks += mutableSetOf()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// tensorflow-lite-gpu still brings litert-api transitively — exclude to avoid duplicate classes
// with org.tensorflow:tensorflow-lite-api brought by tensorflow-lite:2.17.0
configurations.all {
    exclude(group = "com.google.ai.edge.litert", module = "litert-api")
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2026.03.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.ui:ui-text-google-fonts:1.7.6")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // TensorFlow Lite - for RT-DETRv2 object detection
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    // select-tf-ops removed: both FP16 and INT8 models are now Erf-free (TFLITE_BUILTINS only)

    // LiteRT-LM — Gemma 4 E2B inference (dual backend alongside llama.cpp)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.0")
    // Guava (transitiva de LiteRT-LM) — explicit to expose ListenableFuture at compile time for CameraX 1.6.0
    implementation("com.google.guava:guava:33.3.1-android")

    // Room Database - Offline storage (2.8.x: room-ktx merged into room-runtime)
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.7")

    // DataStore - Settings/preferences
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // CameraX - Image capture
    implementation("androidx.camera:camera-core:1.6.0")
    implementation("androidx.camera:camera-camera2:1.6.0")
    implementation("androidx.camera:camera-lifecycle:1.6.0")
    implementation("androidx.camera:camera-view:1.6.0")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.37.3")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.13.2")

    // Image loading — Coil 3 (KMP, mejor integracion Compose)
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.4.0")

    // Location services
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Charts - Compose-native charting library (Fase 3)
    implementation("com.patrykandpatrick.vico:compose-m3:3.1.0")

    // Maps - MapLibre Compose (vector tiles, dark mode, clustering, heatmap)
    implementation("org.maplibre.compose:maplibre-compose:0.12.1")

    // === UI Modernization Libraries ===
    // Lottie - rich vector animations for empty states
    implementation("com.airbnb.android:lottie-compose:6.7.1")
    // Shimmer - loading placeholder effects
    implementation("com.valentinilk.shimmer:compose-shimmer:1.3.3")
    // Haze - glassmorphism / backdrop blur
    implementation("dev.chrisbanes.haze:haze:1.7.2")
    // Konfetti - celebration particle effects
    implementation("nl.dionsegijn:konfetti-compose:2.0.5")

    // WorkManager — background upload scheduling (ktx merged into runtime since 2.9.0)
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // Debug Tools
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
