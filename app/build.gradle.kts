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
// Rewards are off unless a build asks for them, so nothing in the paying path changes shape
// until the layer is finished. Set rewards.enabled=true in local.properties to see it.
fun flag(key: String) = (localProps.getProperty(key) ?: "").trim().equals("true", true).toString()

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
        buildConfigField("boolean", "REWARDS", flag("rewards.enabled"))
        buildConfigField("String", "FIREBASE_APP_ID", local("firebase.appId"))
        buildConfigField("String", "FIREBASE_API_KEY", local("firebase.apiKey"))
        buildConfigField("String", "FIREBASE_PROJECT_ID", local("firebase.projectId"))
        buildConfigField("String", "FIREBASE_SENDER_ID", local("firebase.senderId"))
        // Live token prices come from Pyth's keyed Hermes gateway. Blank on a clone with no key,
        // which switches Pyth off and prices from Jupiter alone — see Pyth.configured.
        buildConfigField("String", "PYTH_API_KEY", local("pyth.apiKey"))
        // Usage counts go to PostHog (Analytics.kt). The project key is public by design; blank
        // on a clone without one, which switches analytics off.
        buildConfigField("String", "POSTHOG_KEY", local("posthog.key"))
        buildConfigField("String", "POSTHOG_HOST", "\"" + (localProps.getProperty("posthog.host") ?: "https://us.i.posthog.com") + "\"")
    }
    signingConfigs {
        // The key a build for friends is signed with: its own, generated once by share.sh into
        // secrets/ (never committed) and described in local.properties. Not the debug key, which
        // is the same on every machine and so proves nothing about who built an APK.
        create("share") {
            val f = localProps.getProperty("share.storeFile")
            if (f != null && rootProject.file(f).exists()) {
                storeFile = rootProject.file(f)
                storePassword = localProps.getProperty("share.storePassword")
                keyAlias = localProps.getProperty("share.keyAlias")
                keyPassword = localProps.getProperty("share.keyPassword")
            }
        }
    }
    buildTypes {
        getByName("debug") { buildConfigField("boolean", "PREVIEWS", "true") }
        getByName("release") { buildConfigField("boolean", "PREVIEWS", "false") }
        // A release build in every way that affects speed (not debuggable, so ART optimises it),
        // signed with the debug key so it installs over the debug build, and with the adb preview
        // shortcuts kept on. Debuggable builds run Compose several times slower; measuring
        // smoothness on one measures the debugger, not the app.
        // The build that goes to other people: release in every way that matters — not debuggable,
        // no adb preview shortcuts — signed with its own key. It talks to the PUBLIC server address
        // handed in as -Pshare.server=…, so a friend's phone is not looking for this Mac's Wi-Fi
        // address. The Pyth key is blanked: an APK can be unpacked by anyone who has it, and with
        // no key the app prices from Jupiter alone (see Pyth.configured).
        create("share") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("share")
            isDebuggable = false
            buildConfigField("boolean", "PREVIEWS", "false")
            val server = (project.findProperty("share.server") ?: "").toString().replace("\"", "")
            buildConfigField("String", "TAP_SERVER_URL", "\"$server\"")
            buildConfigField("String", "PYTH_API_KEY", "\"\"")
        }
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
    // Usage counts (Analytics.kt). Off on a build without a PostHog key.
    implementation("com.posthog:posthog-android:3.72.6")
    // Reading a QR off the camera. CameraX drives the preview and hands frames to ML Kit,
    // which does the decoding on-device — no network, and no image leaves the phone.
    implementation("androidx.camera:camera-camera2:1.6.0")
    implementation("androidx.camera:camera-view:1.6.0")
    // Hands ML Kit's answers back in the preview's own coordinates, so the frame on screen can
    // be drawn where the code actually is without redoing the sensor-to-view maths by hand.
    implementation("androidx.camera:camera-mlkit-vision:1.6.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // ML Kit reads QR codes but cannot write one, and the receive sheet has to draw ours. QRose
    // encodes and draws it, finder patterns included — see Qr.kt for why a hand-drawn one failed.
    implementation("io.github.alexzhirkevich:qrose:1.0.1")

    // Token logos come from whatever host a mint lists, so they are loaded at runtime.
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Many token logos are SVG — Jupiter serves USDT's as logo.svg. Coil decodes raster only
    // without this, so those tokens silently fell back to a letter on a hashed colour and
    // looked like tokens with no logo at all.
    implementation("io.coil-kt:coil-svg:2.7.0")
    testImplementation("junit:junit:4.13.2")
    // Android stubs org.json in unit tests, so JSONObject.put() throws "not mocked".
    // The real implementation makes the wire-format tests run on the JVM.
    testImplementation("org.json:json:20240303")
}
