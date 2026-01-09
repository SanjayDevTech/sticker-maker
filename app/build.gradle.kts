plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.example.stickermaker"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.stickermaker"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            // R8 code + resource shrinking over every package (the convention
            // scope is "**"; the template narrowed it to androidx/kotlin only,
            // which left the app's own code unoptimised). Keep rules live in
            // src/main/keepRules/.
            optimization {
                enable = true
            }
            // Debug signing so `installRelease` works locally until a real
            // keystore exists. Replace before shipping.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // English-only app: drop the ~80 translations every AndroidX library ships.
        localeFilters += "en"
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/kotlin-tooling-metadata.json",
                "DebugProbesKt.bin",
            )
        }
    }
    dependenciesInfo {
        // Encrypted dependency report only Play reads; keep it out of the APK.
        includeInApk = false
    }
}

baselineProfile {
    // Also generate a startup profile so R8 lays out startup classes in the primary dex.
    dexLayoutOptimization = true
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
