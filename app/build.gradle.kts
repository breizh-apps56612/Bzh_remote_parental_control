plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.paul.telecommande"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.paul.telecommande"
        minSdk = 21          // Android 5.0 : ~99 % des appareils en circulation
        targetSdk = 34
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER") ?: "43").toInt()
        versionName = "2.4." + (System.getenv("GITHUB_RUN_NUMBER") ?: "7")
        vectorDrawables.useSupportLibrary = true
    }

    // Signature release : via secrets GitHub si présents, sinon clé de debug (APK installable directement)
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { abortOnError = false }
}

// L'appli Box est embarquée dans l'appli téléphone pour être installée sur la TV depuis le téléphone.
val boxApkDir = layout.buildDirectory.dir("generated/boxApk")
val copyBoxApk by tasks.registering(Copy::class) {
    dependsOn(":box:assembleRelease")
    from(rootProject.file("box/build/outputs/apk/release/box-release.apk"))
    into(boxApkDir)
    rename { "box.apk" }
}
android.sourceSets["main"].assets.srcDir(boxApkDir)
android.androidResources.noCompress.add("apk")
tasks.named("preBuild") { dependsOn(copyBoxApk) }

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":shared"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
