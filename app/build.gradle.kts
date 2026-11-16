plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.triliumzro.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.triliumzro.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 64
        versionName = "0.64"

        // Where the app points on first launch. Kept out of the public source:
        // set it in an untracked gradle.properties (`server=https://…/`) or on
        // the command line (`-Pserver=https://…/`). Empty = ask on first run.
        val defServer = (project.findProperty("server") as String?) ?: ""
        buildConfigField("String", "DEFAULT_SERVER", "\"$defServer\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }


    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
}
