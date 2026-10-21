plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.folio.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.folio.reader"
        minSdk = 26
        targetSdk = 35
        // Each CI build counts up, so a new APK always installs as an update.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0"
    }

    signingConfigs {
        // Builds on GitHub are signed with Folio's own key, so each one installs over the last.
        // The key is kept in the repo only encrypted (signing/folio.jks.enc); the build
        // workflow decrypts it with the FOLIO_SIGNING_PASSWORD secret. Builds elsewhere use
        // the machine's usual debug key.
        val keystore = System.getenv("FOLIO_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }
        val password = System.getenv("FOLIO_SIGNING_PASSWORD")
        if (keystore != null && password != null) {
            getByName("debug") {
                storeFile = keystore
                storePassword = password
                keyAlias = "folio"
                keyPassword = password
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the same key as the debug build, so it installs without extra setup.
            signingConfig = signingConfigs.getByName("debug")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
