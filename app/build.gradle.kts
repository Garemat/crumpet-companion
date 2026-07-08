import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Version derives from the latest v* git tag (the release workflow tags before building).
fun gitVersionName(): String = try {
    val p = ProcessBuilder("git", "describe", "--tags", "--match", "v*", "--abbrev=0")
        .directory(rootProject.projectDir).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readLine()?.trim() ?: ""
    p.waitFor()
    if (out.startsWith("v")) out.removePrefix("v") else "0.1.0"
} catch (e: Exception) {
    "0.1.0"
}

fun gitVersionCode(): Int {
    val parts = gitVersionName().split(".")
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 1
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return major * 10000 + minor * 100 + patch
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.garemat.crumpet"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.garemat.crumpet"
        minSdk = 26          // Health Connect + variable fonts
        targetSdk = 36
        versionCode = gitVersionCode()
        versionName = gitVersionName()
        // Single sideloaded target (Pixel/GrapheneOS = arm64). Keeps the tflite native
        // lib to one ABI instead of shipping ~12MB of unused x86/armeabi variants.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed only when the keystore env is present (the release workflow); local
            // release builds without it stay unsigned but still compile.
            signingConfigs.findByName("release")?.takeIf { it.storeFile != null }?.let {
                signingConfig = it
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.tensorflow.lite)

    // Spotify App Remote (wake_spotify action) — vendored AAR; Spotify doesn't publish
    // it to Maven (github.com/spotify/android-sdk releases). Gson is its wire dep.
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation(libs.gson)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
