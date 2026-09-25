plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

import java.util.Properties

val keystoreProps = Properties().also { p ->
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use(p::load)
}

fun keystoreProp(name: String): String? = keystoreProps.getProperty(name)?.takeIf { it.isNotBlank() }

val releaseSigningKeys = listOf(
    "jarvis.keystore.path",
    "jarvis.keystore.password",
    "jarvis.key.alias",
    "jarvis.key.password",
)
val releaseSigningReady = releaseSigningKeys.all { keystoreProp(it) != null }
if (!releaseSigningReady) {
    logger.warn(
        "Signature release désactivée : propriétés manquantes dans local.properties " +
            "(${releaseSigningKeys.filter { keystoreProp(it) == null }.joinToString()}). " +
            "Les builds debug ne sont pas affectés ; le release sera non signé."
    )
}

android {
    namespace = "com.jarvis.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 66
        versionName = "0.9.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (releaseSigningReady) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProp("jarvis.keystore.path")!!)
                storePassword = keystoreProp("jarvis.keystore.password")
                keyAlias = keystoreProp("jarvis.key.alias")
                keyPassword = keystoreProp("jarvis.key.password")
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            isMinifyEnabled = false
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
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Local persistence — memory store, undo log, action metadata
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Settings / config (assistant name, voice, theme, wake word toggle)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Gemini Live API — raw WebSocket (BidiGenerateContent) + REST fallback calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // API key stored encrypted on-device, never bundled/hardcoded
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Google sign-in for Gmail and Drive (the Authorization API).
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Location reminders ("quand j'arrive à la maison…"): Android's geofencing, which wakes the app only at the boundary
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Reminders
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Offline wake word (openWakeWord models)
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // Folder access through the Storage Access Framework
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Camera frames for the Live session
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")

    // HTML parsing for the web_search action's DuckDuckGo results
    implementation("org.jsoup:jsoup:1.17.2")

    // Local AI for the offline mode: a small Gemma model run entirely on the phone (the user imports the .task file themselves; see README)
    implementation("com.google.mediapipe:tasks-genai:0.10.27")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
