plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.financetracker.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.financetracker.ai"
        minSdk = 26 // MediaPipe LLM Inference requires API 24+; 26 gives us better ML perf APIs
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        // Required so ModelDownloader can gate its diagnostics behind BuildConfig.DEBUG.
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room (local, on-device database — no cloud storage)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // MediaPipe GenAI — on-device Gemma inference, fully offline, no per-token API billing
    implementation("com.google.mediapipe:tasks-genai:0.10.24")

    // Note: analytics charts are drawn with plain Compose primitives (see AnalyticsScreen.kt)
    // rather than a third-party chart library, to keep the dependency surface minimal and stable.

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WorkManager — background AI categorization jobs + budget alert checks
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // App lock (fingerprint/face unlock, like every commercial finance app)
    implementation("androidx.biometric:biometric-ktx:1.2.0-alpha05")

    // Encrypted local prefs for PIN/lock settings
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // DocumentFile for letting the user pick the downloaded .task model file
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
