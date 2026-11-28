plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    testOptions { unitTests {
        isIncludeAndroidResources = true
        // Dépôt Maven de Robolectric (images Android) si le dépôt par défaut est saturé : ROBOLECTRIC_REPO=<url>
        all { t -> System.getenv("ROBOLECTRIC_REPO")?.let { t.systemProperty("robolectric.dependency.repo.url", it) } }
    } }
    namespace = "fr.paul.telecommande.box"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.paul.telecommande.box"
        minSdk = 23          // Android TV 6.0+ (Freebox Pop, mini 4K, Shield, Chromecast, Sony, Philips, TCL…)
        targetSdk = 34
        // Numéro de version fixé ici (et non par GitHub) : on l'augmente à chaque publication.
        versionCode = 170
        versionName = "1.4.59"
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
    // Captures des écrans TV (Robolectric, rendu natif) : SCREENS=1 gradle :box:testReleaseUnitTest
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
