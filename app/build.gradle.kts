plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    buildFeatures {
        viewBinding = true
    }

    namespace = "com.assistant.overlay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.assistant.overlay"
        minSdk = 26
        targetSdk = 34
        versionCode = 21
        versionName = "1.0-SECURE-LOCKED"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("com.google.mlkit:text-recognition:16.0.1") {
        // tensorflow-lite:2.14.0 from adapter_smartassist is the full runtime.
        // mlkit transitively pulls tensorflow-lite-api:2.14.0 (API-only stub)
        // causing a duplicate namespace warning. Exclude the stub — the full
        // runtime already provides everything the stub offers.
        exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
    }
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    implementation(project(":diagnostic_core"))

    implementation(project(":adapter_net"))
    implementation(project(":adapter_input"))
    implementation(project(":adapter_lmk"))
    implementation(project(":adapter_sync"))

    implementation(project(":adapter_ping"))
    implementation(project(":adapter_stutter"))
    implementation(project(":adapter_lag"))
    implementation(project(":adapter_boot"))
    implementation(project(":adapter_watchdog"))

    implementation(project(":adapter_memory"))
    implementation(project(":adapter_thermal"))
    implementation(project(":adapter_battery"))
    implementation(project(":adapter_scheduler"))
    implementation(project(":adapter_smartassist"))

    implementation(project(":adapter_interruption"))
}
