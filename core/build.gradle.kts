plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    lint {
        disable += listOf("SetTextI18n", "UnusedResources", "ObsoleteSdkInt", "Overdraw", "GradleDependency", "OldTargetApi", "VectorPath", "ChromeOsAbiSupport", "ScopedStorage", "StaticFieldLeak", "SdCardPath", "UseSwitchCompatOrMaterialCode", "ConstantLocale", "InlinedApi", "IconLocation", "IconDuplicatesConfig", "TypographyEllipsis", "HardcodedText", "ButtonStyle", "ProtectedPermissions", "ExportedService", "MissingClass", "SuspiciousIndentation")
        checkReleaseBuilds = false
        abortOnError = false
    }

    

    namespace = "com.assistant.diagnostic"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles("consumer-rules.pro")
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
