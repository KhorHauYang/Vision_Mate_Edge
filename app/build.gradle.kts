plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.tech4goodvision"
    compileSdk = 34 // Ensure this is a standard integer version, e.g., 34

    defaultConfig {
        applicationId = "com.example.tech4goodvision"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 🔥 THIS BLOCK IS CRITICAL FOR SAMSUNG PHONES
        // Forces 64-bit architecture to prevent the 16KB Alignment crash
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // Google CameraX Toolkit
    implementation("androidx.camera:camera-core:1.3.0")
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")

    // 🔥 UPDATED: The ultra-stable TFLite Engine (Guaranteed 16KB Samsung Fix)
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
}