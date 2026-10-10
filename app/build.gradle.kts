plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android { namespace = "com.earam.tabs"; compileSdk = 36
    // The canonical Earam logo lives at the repository root (Logo.png).
    // Copy it into Android resources before resource processing so every
    // Activity/dialog can use the exact same artwork.
    val copyEaramLogo = tasks.register<Copy>("copyEaramLogo") {
        from(rootProject.file("Logo.png"))
        into(layout.projectDirectory.dir("src/main/res/drawable"))
        rename { "earam_logo.png" }
    }
    tasks.named("preBuild").configure { dependsOn(copyEaramLogo) }
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "com.earam.tabs"
        minSdk = 26
        targetSdk = 36
        val runNumberProvider = providers.environmentVariable("GITHUB_RUN_NUMBER")
        val runNumberText = runNumberProvider.getOrNull()
        val runNumber = runNumberText?.toIntOrNull() ?: 0
        versionCode = 100000 + runNumber
        versionName = if (runNumber > 0) "0.1.$runNumber" else "0.1.7-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            enableV3Signing = true
        }
    }
    buildTypes {
        getByName("debug") {
            // Keep diagnostic builds side-by-side with the signed production app.
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Earam DEBUG"
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            manifestPlaceholders["appLabel"] = "Earam"
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = false
        }
    }
    packaging {
        jniLibs { useLegacyPackaging = false }
        resources {
            excludes += "/assets/dexopt/**"
        }
    }
    androidResources {
        noCompress += "arsc"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("net.alphatab:alphaTab:1.8.4")
    // alphaTab 1.8.4 declares alphaSkia 3.4.135. Override the Android
    // native renderer with 3.5.147, whose official build validates 16 KB
    // PT_LOAD alignment for 64-bit Android libraries.
    implementation("net.alphatab:alphaSkia-android:3.5.147")
    testImplementation("junit:junit:4.13.2")
}
