plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.paul.telecommande.box"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.paul.telecommande.box"
        minSdk = 23          // Android TV 6.0+ (Freebox Pop, mini 4K, Shield, Chromecast, Sony, Philips, TCL…)
        targetSdk = 34
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER") ?: "18").toInt()
        versionName = "1.4." + (System.getenv("GITHUB_RUN_NUMBER") ?: "7")
    }

    val ksFile = System.getenv("ANDROID_KEYSTORE_PATH")
    signingConfigs {
        if (!ksFile.isNullOrBlank() && file(ksFile).exists()) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
