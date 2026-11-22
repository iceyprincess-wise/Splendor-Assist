plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    lint {
        // No51: three disables REMOVED in the no48 round — each was
        // proven to mask nothing live in this module:
        // - "HardcodedText": zero literal .text/setText in core Kotlin
        //   and zero literal android:text in either core layout (the
        //   no42 round moved the last inline text to strings).
        // - "UnusedResources": all 25 core strings are referenced and
        //   both core layouts are used; core res has no drawables.
        // - "ExportedService": the core manifest declares no services.
        // KEPT deliberately:
        // - "SdCardPath": load-bearing — SplendorStorageRoot resolves
        //   the log root from Environment.getExternalStorageDirectory()
        //   on purpose (S17-D7: "/sdcard" is only a symlink).
        // - "StaticFieldLeak": guard — AdapterHealthRegistry and
        //   LogMirror hold applicationContext in singletons (no live
        //   leak: applicationContext only), and whether lint exempts
        //   that is AGP-version-dependent, so the disable stays until
        //   lint can be run to prove otherwise.
        // NOTE: checkReleaseBuilds = false, so lint only runs on an
        // explicit `:core:lintDebug` invocation, never on assemble.
        disable += listOf("SetTextI18n", "ObsoleteSdkInt", "Overdraw", "GradleDependency", "OldTargetApi", "VectorPath", "ChromeOsAbiSupport", "ScopedStorage", "StaticFieldLeak", "SdCardPath", "UseSwitchCompatOrMaterialCode", "ConstantLocale", "InlinedApi", "IconLocation", "IconDuplicatesConfig", "TypographyEllipsis", "ButtonStyle", "ProtectedPermissions", "MissingClass", "SuspiciousIndentation")
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = true
    }

    namespace = "com.assistant.diagnostic"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

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
    testImplementation("junit:junit:4.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
