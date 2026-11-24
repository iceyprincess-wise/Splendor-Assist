plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing (Updater Round, Piece 1): reads KEYSTORE_FILE, KEYSTORE_PASSWORD,
// KEY_ALIAS, KEY_PASSWORD from env or gradle properties. CI injects these from
// GitHub Actions secrets. Without them the release build stays unsigned (as before).
val releaseKeystoreFile: String? = System.getenv("KEYSTORE_FILE") ?: (project.findProperty("KEYSTORE_FILE") as String?)
val releaseKeystorePassword: String? = System.getenv("KEYSTORE_PASSWORD") ?: (project.findProperty("KEYSTORE_PASSWORD") as String?)
val releaseKeyAlias: String? = System.getenv("KEY_ALIAS") ?: (project.findProperty("KEY_ALIAS") as String?)
val releaseKeyPassword: String? = System.getenv("KEY_PASSWORD") ?: (project.findProperty("KEY_PASSWORD") as String?)

android {
    signingConfigs {
        create("release") {
            if (releaseKeystoreFile != null) storeFile = file(releaseKeystoreFile)
            if (releaseKeystorePassword != null) storePassword = releaseKeystorePassword
            if (releaseKeyAlias != null) keyAlias = releaseKeyAlias
            if (releaseKeyPassword != null) keyPassword = releaseKeyPassword
        }
    }

    lint {
        disable += listOf()
        checkReleaseBuilds = true
        abortOnError = true
        warningsAsErrors = true
    }

    

    buildFeatures {
        viewBinding = true
    }

    namespace = "com.assistant.overlay"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.assistant.overlay"
        minSdk = 26
        targetSdk = 36
        versionCode = 259
        versionName = "1.0-SECURE-LOCKED"
        
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
                // DISABLED_SPLENDOR_UNUSED_GWP_ASAN_ARG arguments += "-DANDROID_ALLOW_GWP_ASAN=OFF"

            
                // SPLENDOR_NATIVE_LINK_SHIM_V6_BEGIN
                arguments += "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
                arguments += "-Wno-deprecated"
                arguments += "-Wno-author"
                // SPLENDOR_NATIVE_LINK_SHIM_V6_END
}
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseKeystoreFile != null && releaseKeystorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(project(":core"))
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.mlkit:text-recognition:16.0.1") {
        exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
    }
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}


