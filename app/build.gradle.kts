plugins {
    id("com.google.devtools.ksp") version "1.9.22-1.0.17"
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.assistant.overlay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.assistant.overlay"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {

    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    val media3Version = "1.3.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("com.google.mlkit:text-recognition:16.0.1")
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
