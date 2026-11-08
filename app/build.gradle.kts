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
        versionCode = 3
        versionName = "0.3"
        // 64-bit ARM only: every current phone (Play has required 64-bit since 2019), and the
        // Apple-silicon emulators used for baseline profiles. Drops the 32-bit ARM and x86
        // copies of every native library.
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // 16 KB page alignment, required on newer Android for native libraries.
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }
    // libwebp, built from a pinned, hash-checked release (src/main/cpp/CMakeLists.txt).
    ndkVersion = "27.1.12297006"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // Deliberately public: the key and its passwords are in the repo so anyone can build
        // an APK that installs over (and updates) a GitHub release. That also means anyone can
        // sign one, so it gives no protection against tampered builds. Never use it for Play.
        create("githubPublicRelease") {
            storeFile = rootProject.file("github-public-release.jks")
            storePassword = "github-public-release"
            keyAlias = "github-public-release"
            keyPassword = "github-public-release"
        }
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
            signingConfig = signingConfigs.getByName("githubPublicRelease")
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

// The profile is recorded on "nonMinifiedRelease", a copy of release the plugin adds. It inherits
// `optimization`, so R8 renamed every class there and the profile came out in names (Lp4;) the
// real release build doesn't have: Android ignored ~99% of it. Recorded unminified, R8 maps the
// profile onto the release build's own names when it builds release.
androidComponents {
    beforeVariants(selector().withBuildType("nonMinifiedRelease")) {
        it.isMinifyEnabled = false
        it.shrinkResources = false
    }
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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Compose compiler stability/skippability reports — opt in per run:
//   ./gradlew :app:assembleRelease -PcomposeReports
if (providers.gradleProperty("composeReports").isPresent) {
    composeCompiler {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}
