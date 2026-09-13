import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.maragung.arrowide"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.maragung.arrowide"
        minSdk = 26
        // targetSdk intentionally 28 (Termux-style) so downloaded toolchain
        // binaries can be executed from app storage on Android 10+ (W^X).
        targetSdk = 28
        versionCode = 5
        versionName = "0.5.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        // targetSdk is intentionally 28 (Termux-style W^X workaround) and the
        // app ships via GitHub Releases, not Google Play — the "apps must
        // target API 33+" rule does not apply here.
        disable += "ExpiredTargetSdkVersion"
    }

    // The release signing keystore is bootstrapped by CI on the first
    // Release run (keytool runs on the runner; no JDK on dev machines).
    // When present, release builds are signed; otherwise they stay
    // unsigned and the workflow regenerates the key.
    val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
    val keystoreFile = rootProject.file("keystore/arrow-release.jks")
    if (keystorePropertiesFile.exists() && keystoreFile.exists()) {
        val keystoreProperties = Properties().apply {
            keystorePropertiesFile.inputStream().use { load(it) }
        }
        signingConfigs {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Minification stays OFF for now: sora-editor's TextMate
            // grammars and kotlinx-serialization use reflective loading that
            // R8 can strip; re-enable only after on-device validation.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Code editor
    implementation(libs.sora.editor)
    implementation(libs.sora.language.textmate)

    // Toolchain: .deb (ar) + tar.xz extraction (plan #4-#7), pure Java
    implementation(libs.xz)
    implementation(libs.commons.compress)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
