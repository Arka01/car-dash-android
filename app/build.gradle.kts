plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// GitHub Actions numbers every build; a higher number lets the new APK install over the old one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.arka01.cardash"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.arka01.cardash"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        // The key comes from GitHub secrets (see README); it is never stored in the repo.
        create("release") {
            System.getenv("SIGNING_KEYSTORE_PATH")?.let { path ->
                storeFile = file(path)
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "cardash"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Lint warnings shouldn't block a test build; we can't run lint locally anyway.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.core:core:1.17.0")   // 1.19+ needs Android SDK 37
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Phone screen (Jetpack Compose). Compose 1.12+ needs Android SDK 37 and AGP 9.
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))   // Compose 1.11.4
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.12.4")
}
