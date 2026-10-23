plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.rubup.worker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rubup.worker"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-debug"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // The worker intentionally uses Android platform APIs only.
}
