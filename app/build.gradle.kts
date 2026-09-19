import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Deployment-specific values live in local.properties (never committed), so a fresh clone builds
// and runs with the backend and push simply switched off.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun local(key: String) = "\"" + (localProps.getProperty(key) ?: "").replace("\"", "") + "\""

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

android {
    namespace = "so.duffle.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "so.duffle.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "TAP_SERVER_URL", local("duffle.server"))
        buildConfigField("String", "FIREBASE_APP_ID", local("firebase.appId"))
        buildConfigField("String", "FIREBASE_API_KEY", local("firebase.apiKey"))
        buildConfigField("String", "FIREBASE_PROJECT_ID", local("firebase.projectId"))
        buildConfigField("String", "FIREBASE_SENDER_ID", local("firebase.senderId"))
    }
    buildTypes {
        getByName("debug") { buildConfigField("boolean", "PREVIEWS", "true") }
        getByName("release") { buildConfigField("boolean", "PREVIEWS", "false") }
        // A release build in every way that affects speed (not debuggable, so ART optimises it),
        // signed with the debug key so it installs over the debug build, and with the adb preview
        // shortcuts kept on. Debuggable builds run Compose several times slower; measuring
        // smoothness on one measures the debugger, not the app.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            buildConfigField("boolean", "PREVIEWS", "true")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // Mobile Wallet Adapter — the hackathon requires it, and it means we never touch keys.
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.1.1")
    // Transaction building, base58 and RPC. Stable, unlike the beta web3-solana line.
    implementation("org.sol4k:sol4k:0.8.2")
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    // Firebase brings in an old Fragment (<1.3), whose permission results break the
    // ActivityResult API the notification prompt uses. Release lint refuses to build with it.
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    // Push for payments that land while the app is closed. Initialised by hand from
    // local.properties, so no google-services plugin and no build break without Firebase.
    implementation("com.google.firebase:firebase-messaging:25.1.3")
    testImplementation("junit:junit:4.13.2")
    // Android stubs org.json in unit tests, so JSONObject.put() throws "not mocked".
    // The real implementation makes the wire-format tests run on the JVM.
    testImplementation("org.json:json:20240303")
}
