plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android { namespace = "com.earam.tabs"; compileSdk = 35
    defaultConfig { applicationId = "com.earam.tabs"; minSdk = 23; targetSdk = 35; versionCode = 2; versionName = "0.1.1"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    ndkVersion = "27.2.12479018"
    packaging { jniLibs { useLegacyPackaging = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies { implementation(project(":core")); implementation("androidx.core:core-ktx:1.15.0"); implementation("androidx.appcompat:appcompat:1.7.0"); implementation("com.google.android.material:material:1.12.0"); testImplementation("junit:junit:4.13.2") }
