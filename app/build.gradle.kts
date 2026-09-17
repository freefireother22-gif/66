import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// AI Studio/Firebase setup: add app/google-services.json, then this applies automatically.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val envProperties = Properties().apply {
    val file = rootProject.file(".env")
    if (file.exists()) file.inputStream().use { load(it) }

    // Also check for .dev.env.json or root .dev.env.json
    listOf(rootProject.file(".dev.env.json"), rootProject.file("../.dev.env.json"), file("/app/.dev.env.json")).forEach { devJson ->
        if (devJson.exists()) {
            try {
                val content = devJson.readText()
                val matcher = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").findAll(content)
                for (match in matcher) {
                    val k = match.groupValues[1]
                    val v = match.groupValues[2]
                    if (!containsKey(k) || getProperty(k).isNullOrEmpty()) {
                        setProperty(k, v)
                    }
                }
            } catch (_: Exception) {}
        }
    }
}

fun getSecretConfig(key: String, fallback: String = ""): String {
    return (localProperties.getProperty(key)
        ?: envProperties.getProperty(key)
        ?: System.getenv(key)
        ?: (project.findProperty(key) as? String)
        ?: fallback).trim()
}

android {
    namespace = "com.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aistudio.parentalcontrol.whntoq"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.2-fresh"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val localTurnJsonFile = rootProject.file("turn-config.local.json")
        val localTurnJson = if (localTurnJsonFile.exists()) localTurnJsonFile.readText().trim() else ""

        val iceServersJsonRaw = (System.getenv("TURN_ICE_SERVERS_JSON")
            ?: (project.findProperty("TURN_ICE_SERVERS_JSON") as? String)
            ?: envProperties.getProperty("TURN_ICE_SERVERS_JSON")
            ?: localProperties.getProperty("TURN_ICE_SERVERS_JSON")
            ?: localTurnJson).trim()

        var turnUrl = getSecretConfig("TURN_SERVER_URL", "")
        var turnUser = getSecretConfig("TURN_USERNAME", "")
        var turnPass = getSecretConfig("TURN_PASSWORD", "")

        // Clean turnUrl if it contains stray timestamp or space (e.g. "turn:global.relay.metered.ca:8003:05 PM")
        if (turnUrl.isNotEmpty()) {
            if (turnUrl.contains(" ")) {
                turnUrl = turnUrl.substringBefore(" ").trim()
            }
            if (turnUrl.contains("global.relay.metered.ca") && turnUrl.contains(":8003")) {
                turnUrl = turnUrl.replace(Regex(":8003.*"), ":80")
            }
        }

        if (turnUrl.isEmpty() && iceServersJsonRaw.isNotEmpty()) {
            val urlRegex = Regex("\"urls\"\\s*:\\s*\"(turn[s]?:[^\"]+)\"")
            val userRegex = Regex("\"username\"\\s*:\\s*\"([^\"]+)\"")
            val credRegex = Regex("\"(?:credential|password)\"\\s*:\\s*\"([^\"]+)\"")
            val foundUrl = urlRegex.find(iceServersJsonRaw)?.groupValues?.get(1) ?: ""
            val foundUser = userRegex.find(iceServersJsonRaw)?.groupValues?.get(1) ?: ""
            val foundCred = credRegex.find(iceServersJsonRaw)?.groupValues?.get(1) ?: ""
            if (foundUrl.isNotEmpty() && foundUser.isNotEmpty() && foundCred.isNotEmpty()) {
                turnUrl = foundUrl.substringBefore(" ").trim()
                turnUser = foundUser
                turnPass = foundCred
            }
        }

        val escapedIceServersJson = iceServersJsonRaw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "")
            .replace("\n", " ")

        var signalingWssUrl = getSecretConfig("SIGNALING_WSS_URL", "wss://server-1-hqnr.onrender.com/ws")
        if (signalingWssUrl.isEmpty() || signalingWssUrl.contains("example.com") || signalingWssUrl.contains("your-domain")) {
            signalingWssUrl = "wss://server-1-hqnr.onrender.com/ws"
        }
        if (signalingWssUrl.startsWith("https://")) {
            signalingWssUrl = "wss://" + signalingWssUrl.removePrefix("https://")
        } else if (signalingWssUrl.startsWith("http://")) {
            signalingWssUrl = "ws://" + signalingWssUrl.removePrefix("http://")
        }
        if (!signalingWssUrl.endsWith("/ws")) {
            signalingWssUrl = signalingWssUrl.trimEnd('/') + "/ws"
        }

        buildConfigField("String", "SIGNALING_WSS_URL", "\"${signalingWssUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "TURN_ICE_SERVERS_JSON", "\"$escapedIceServersJson\"")
        buildConfigField("String", "TURN_SERVER_URL", "\"${turnUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "TURN_USERNAME", "\"${turnUser.replace("\"", "\\\"")}\"")
        buildConfigField("String", "TURN_PASSWORD", "\"${turnPass.replace("\"", "\\\"")}\"")
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.getstream:stream-webrtc-android:1.1.3")
    implementation("io.getstream:stream-webrtc-android-ui:1.1.3")

    // Monitoring backend and Android 15+ location stack
    implementation(platform("com.google.firebase:firebase-bom:33.10.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
