plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "sk.mirecky.skener"
    compileSdk = 36

    defaultConfig {
        applicationId = "sk.mirecky.skener"
        minSdk = 36
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.$versionCode"

        // Aplikácia beží len na S25 Ultra (arm64); ostatné architektúry by zbytočne zväčšovali APK.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Ak sú nastavené premenné prostredia (GitHub Secrets), podpisuje sa súkromným kľúčom.
        // Inak sa použije vývojový kľúč z repozitára, aby sa aktualizácie dali inštalovať cez seba.
        create("release") {
            val path = System.getenv("SKENER_KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("SKENER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SKENER_KEY_ALIAS")
                keyPassword = System.getenv("SKENER_KEY_PASSWORD")
            } else {
                storeFile = rootProject.file("keystore/dev.keystore")
                storePassword = "android"
                keyAlias = "skener-dev"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Modely OCR sa čítajú cez file descriptor, preto nesmú byť v APK komprimované.
    androidResources {
        noCompress += "traineddata"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.telephoto.coil3)
    implementation(libs.reorderable)
    // Šifrovanie PDF nepoužívame, BouncyCastle by zbytočne zväčšil aplikáciu.
    implementation(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }
    implementation(libs.opencv)
    implementation(libs.tesseract)

    testImplementation(libs.junit)
}
