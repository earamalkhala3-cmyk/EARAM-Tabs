plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android { namespace = "com.earam.tabs"; compileSdk = 35
    buildFeatures { buildConfig = true }
    defaultConfig { applicationId = "com.earam.tabs"; minSdk = 23; targetSdk = 35; versionCode = 5; versionName = "0.1.4"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
        create("release") {
            storeFile = file(System.getenv("ANDROID_KEYSTORE_PATH") ?: "release.keystore")
            storeType = "PKCS12"
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = false
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = false
        }
    }
    packaging {
        jniLibs { useLegacyPackaging = false }
        resources {
            excludes += "/assets/dexopt/**"
            noCompress += "arsc"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies { implementation(project(":core")); implementation("androidx.core:core-ktx:1.15.0"); implementation("androidx.appcompat:appcompat:1.7.0"); implementation("com.google.android.material:material:1.12.0"); testImplementation("junit:junit:4.13.2") }
