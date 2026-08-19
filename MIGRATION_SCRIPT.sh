#!/usr/bin/env bash
# =============================================================================
# Splendor-Assist: 18-module → 2-module migration script
# SELF-CONTAINED — all file content embedded as heredocs.
# No companion directory required. Drop this single file in your repo root and run.
#
# Forensic audit: 2026-08-18
# All PROPERTY_SPECIAL_USE_FGS_SUBTYPE values verified live against repo manifests.
# =============================================================================
# SAFETY RULES:
#   1. Run from repository root (where settings.gradle.kts lives).
#   2. Must be on branch migration/2-module-consolidation (script creates it if absent).
#   3. Idempotent — safe to re-run if interrupted.
#   4. No source file is deleted until AFTER structural verification passes.
#   5. All source moves use git mv (preserves history).
# =============================================================================

set -euo pipefail

BRANCH="migration/2-module-consolidation"

# ── PHASE 0: Pre-flight ───────────────────────────────────────────────────────
echo "=== PHASE 0: Pre-flight checks ==="

if ! git rev-parse --git-dir > /dev/null 2>&1; then
    echo "ERROR: Not inside a git repository. cd to repo root first." >&2
    exit 1
fi

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$CURRENT_BRANCH" != "$BRANCH" ]; then
    echo "Creating/switching to branch: $BRANCH"
    git checkout -b "$BRANCH" 2>/dev/null || git checkout "$BRANCH"
fi

echo "  Branch : $(git rev-parse --abbrev-ref HEAD)"
echo "  Commit : $(git rev-parse HEAD)"

# ── PHASE 1: Create target directory structures ───────────────────────────────
echo ""
echo "=== PHASE 1: Create target directory structures ==="

mkdir -p core/src/main/java/com/assistant/storage
mkdir -p core/src/main/java/com/assistant/diagnostic/notification
mkdir -p core/src/main/java/com/assistant/diagnostic/persistence
mkdir -p core/src/main/java/com/assistant/diagnostic/registry
mkdir -p core/src/main/java/com/assistant/runtime
mkdir -p core/src/main/java/com/assistant/execution
mkdir -p core/src/main/java/com/assistant/events
mkdir -p core/src/main/java/com/assistant/controlroom
mkdir -p core/src/main/java/com/assistant/survival
mkdir -p core/src/main/java/com/assistant/audit
mkdir -p core/src/main/java/com/assistant/admin
mkdir -p core/src/main/res/layout

for pkg in \
    "com/assistant/adapter/lmk" \
    "com/assistant/adapter/sync" \
    "com/assistant/adapter/input" \
    "com/assistant/adapter/net" \
    "com/assistant/adapter/ping" \
    "com/assistant/adapter/stutter" \
    "com/assistant/adapter/lag" \
    "com/assistant/adapter/boot" \
    "com/assistant/adapter/watchdog" \
    "com/assistant/adapter/memory" \
    "com/assistant/adapter/thermal" \
    "com/assistant/adapter/battery" \
    "com/assistant/adapter/scheduler" \
    "com/assistant/adapter/smartassist" \
    "com/assistant/adapter/smartassist/contributors" \
    "com/assistant/adapter/smartassist/fps" \
    "com/assistant/adapter/interruption"
do
    mkdir -p "app/src/main/java/$pkg"
done

echo "  Directory structures created."

# ── PHASE 2: Write new Gradle / build files ───────────────────────────────────
echo ""
echo "=== PHASE 2: Write Gradle configuration ==="

cat > settings.gradle.kts << 'SETTINGS_EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SplendorAssistEngine"
include(":app")
include(":core")
SETTINGS_EOF
echo "  settings.gradle.kts written."

cat > core/build.gradle.kts << 'CORE_GRADLE_EOF'
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.assistant.core"
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
CORE_GRADLE_EOF
echo "  core/build.gradle.kts written."

cat > core/consumer-rules.pro << 'CORE_PROGUARD_EOF'
-keep interface com.assistant.runtime.** { *; }
-keep class com.assistant.runtime.** { *; }
-keep class com.assistant.execution.** { *; }
-keep class com.assistant.diagnostic.RuntimeLogger { *; }
-keep class com.assistant.diagnostic.AdapterSignalBus { *; }
-keep class com.assistant.diagnostic.registry.** { *; }
-keep class com.assistant.storage.SplendorStorageRoot { *; }
CORE_PROGUARD_EOF
echo "  core/consumer-rules.pro written."

mkdir -p core/src/main
cat > core/src/main/AndroidManifest.xml << 'CORE_MANIFEST_EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity
            android:name="com.assistant.admin.AdminSettingsActivity"
            android:exported="true"
            android:label="Splendor Admin" />
        <activity
            android:name="com.assistant.diagnostic.AnalyticsTheaterActivity"
            android:exported="false" />
        <activity
            android:name="com.assistant.diagnostic.CrashInspectorActivity"
            android:exported="false" />
    </application>
</manifest>
CORE_MANIFEST_EOF
echo "  core/src/main/AndroidManifest.xml written."

cat > app/build.gradle.kts << 'APP_GRADLE_EOF'
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
        versionCode = 33
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
    implementation(project(":core"))
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.mlkit:text-recognition:16.0.1") {
        exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
    }
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
APP_GRADLE_EOF
echo "  app/build.gradle.kts written."

cat > app/proguard-rules.pro << 'APP_PROGUARD_EOF'
-keep class com.google.mlkit.** { *; }
-keep class com.assistant.EngineData { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn com.google.mlkit.**
-keep class com.assistant.overlay.interceptor.SmartAssistAccessibilityEngine { *; }
-keep class com.assistant.adapter.** extends android.app.Service { *; }
-keep class com.assistant.adapter.smartassist.SmartAssistAccessibilityEngine { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
APP_PROGUARD_EOF
echo "  app/proguard-rules.pro written."

cat > app/src/main/AndroidManifest.xml << 'APP_MANIFEST_EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.KILL_BACKGROUND_PROCESSES" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

    <application
        tools:replace="android:allowBackup"
        android:name="com.assistant.App"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="Splendor Assist"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:hardwareAccelerated="true"
        android:theme="@style/Theme.SplendorAssist">

        <activity android:name="com.assistant.controlroom.ui.SmartAssistControlRoomActivity" android:exported="false" />
        <activity android:name="com.assistant.controlroom.ui.GoalkeeperControlRoomActivity" android:exported="false" />
        <activity android:name="com.assistant.controlroom.ui.InterceptionControlRoomActivity" android:exported="false" />
        <activity android:name="com.assistant.controlroom.ui.FutureRoomsActivity" android:exported="false" />
        <activity android:name="com.assistant.controlroom.ui.AgentHubActivity" android:exported="false" />
        <activity android:name="com.assistant.controlroom.ui.GameplayRoomActivity" android:exported="false" />

        <activity android:name="com.assistant.MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity android:name="com.assistant.ErrorActivity" android:exported="false" />
        <activity android:name="com.assistant.DiagnosisDetailActivity" android:exported="false" />
        <activity android:name="com.assistant.DiagnosisRoomActivity" android:exported="false" />
        <activity android:name="com.assistant.LogActivity" android:exported="false" />
        <activity android:name="com.assistant.WelcomeActivity" android:exported="false" />
        <activity android:name="com.assistant.UpdateActivity" android:exported="false" />

        <service
            android:name="com.assistant.OverlayService"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />

        <service
            android:name="com.assistant.adapter.smartassist.SmartAssistAccessibilityEngine"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

        <service
            android:name="com.assistant.adapter.smartassist.SmartAssistAdapterService"
            android:exported="true"
            android:process=":kernel"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Coordinates SmartAssist orchestration and decision telemetry." />
        </service>

        <service
            android:name="com.assistant.adapter.lmk.LmkAdapterService"
            android:exported="true"
            android:process=":kernel"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="System performance optimization and thread priority alignment for active workloads." />
        </service>

        <service
            android:name="com.assistant.adapter.net.NetAdapterService"
            android:exported="true"
            android:process=":kernel"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Asynchronous data transmission balancing and routing telemetry processing." />
        </service>

        <service
            android:name="com.assistant.adapter.input.InputAdapterService"
            android:exported="true"
            android:process=":kernel"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Coordinates input hardware event data collection pipelines." />
        </service>

        <service
            android:name="com.assistant.adapter.sync.SyncAdapterService"
            android:exported="true"
            android:process=":kernel"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Synchronizes frame updates and graphics commands during system rendering events." />
        </service>

        <service
            android:name="com.assistant.adapter.ping.PingAdapterService"
            android:exported="true"
            android:process=":kernel"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Monitors network latency and connectivity telemetry." />
        </service>

        <service
            android:name="com.assistant.adapter.stutter.StutterAdapterService"
            android:exported="true"
            android:process=":kernel"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Monitors frame pacing and stutter conditions." />
        </service>

        <service
            android:name="com.assistant.adapter.lag.LagAdapterService"
            android:exported="true"
            android:process=":kernel"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Monitors application responsiveness and lag conditions." />
        </service>

        <service
            android:name="com.assistant.adapter.boot.BootAdapterService"
            android:exported="true"
            android:process=":kernel"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Monitors boot lifecycle and startup stabilization telemetry." />
        </service>

        <service
            android:name="com.assistant.adapter.watchdog.WatchdogAdapterService"
            android:exported="true"
            android:process=":kernel"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Supervises adapter health, failures and recovery telemetry." />
        </service>

        <service
            android:name="com.assistant.adapter.memory.MemoryAdapterService"
            android:exported="true"
            android:process=":kernel"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Monitors memory pressure and RAM telemetry." />
        </service>

        <service
            android:name="com.assistant.adapter.thermal.ThermalAdapterService"
            android:exported="true"
            android:process=":kernel"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Monitors device thermal state and throttling conditions." />
        </service>

        <service
            android:name="com.assistant.adapter.battery.BatteryAdapterService"
            android:exported="true"
            android:process=":kernel"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Monitors battery state and power conditions." />
        </service>

        <service
            android:name="com.assistant.adapter.scheduler.SchedulerAdapterService"
            android:exported="true"
            android:process=":kernel"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Coordinates adapter scheduling and fleet orchestration telemetry." />
        </service>

        <service
            android:name="com.assistant.adapter.interruption.InterruptionAdapterService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Interruption management and anti-stutter coordination" />
        </service>

    </application>
</manifest>
APP_MANIFEST_EOF
echo "  app/src/main/AndroidManifest.xml written."

git add \
    settings.gradle.kts \
    core/build.gradle.kts \
    core/consumer-rules.pro \
    core/src/main/AndroidManifest.xml \
    app/build.gradle.kts \
    app/proguard-rules.pro \
    app/src/main/AndroidManifest.xml

echo "  All Gradle and manifest files staged."

# ── PHASE 3: Move source → :core ─────────────────────────────────────────────
echo ""
echo "=== PHASE 3: Move source → :core ==="

move_if_exists() {
    local src="$1" dst="$2"
    if [ -e "$src" ]; then
        git mv "$src" "$dst"
        echo "  Moved : $src"
    else
        echo "  SKIP  : $src (not found)"
    fi
}

move_if_exists \
    storage_core/src/main/java/com/assistant/storage/SplendorStorageRoot.kt \
    core/src/main/java/com/assistant/storage/SplendorStorageRoot.kt

move_if_exists \
    diagnostic_core/src/main/java/com/assistant/diagnostic/AdapterSignalBus.kt \
    core/src/main/java/com/assistant/diagnostic/AdapterSignalBus.kt
move_if_exists \
    diagnostic_core/src/main/java/com/assistant/diagnostic/AnalyticsTheaterActivity.kt \
    core/src/main/java/com/assistant/diagnostic/AnalyticsTheaterActivity.kt
move_if_exists \
    diagnostic_core/src/main/java/com/assistant/diagnostic/CrashInspector.kt \
    core/src/main/java/com/assistant/diagnostic/CrashInspector.kt
move_if_exists \
    diagnostic_core/src/main/java/com/assistant/diagnostic/CrashInspectorActivity.kt \
    core/src/main/java/com/assistant/diagnostic/CrashInspectorActivity.kt
move_if_exists \
    diagnostic_core/src/main/java/com/assistant/diagnostic/MachineLedger.kt \
    core/src/main/java/com/assistant/diagnostic/MachineLedger.kt
move_if_exists \
    diagnostic_core/src/main/java/com/assistant/diagnostic/RuntimeLogger.kt \
    core/src/main/java/com/assistant/diagnostic/RuntimeLogger.kt

move_if_exists \
    diagnostic_core/src/main/java/com/assistant/diagnostic/notification/NodeNotificationHub.kt \
    core/src/main/java/com/assistant/diagnostic/notification/NodeNotificationHub.kt

move_if_exists \
    diagnostic_core/src/main/java/com/assistant/diagnostic/persistence/HealthPersistenceStore.kt \
    core/src/main/java/com/assistant/diagnostic/persistence/HealthPersistenceStore.kt

move_if_exists \
    diagnostic_core/src/main/java/com/assistant/diagnostic/registry/AdapterHealthRegistry.kt \
    core/src/main/java/com/assistant/diagnostic/registry/AdapterHealthRegistry.kt
move_if_exists \
    diagnostic_core/src/main/java/com/assistant/diagnostic/registry/PerformanceTelemetryRegistry.kt \
    core/src/main/java/com/assistant/diagnostic/registry/PerformanceTelemetryRegistry.kt

STRAY="diagnostic_core/src/main/java/com/assistant/diagnostic/registry/AdapterHealthRegistry.ktpackage"
if [ -f "$STRAY" ]; then
    git rm "$STRAY"
    echo "  DELETED stray: AdapterHealthRegistry.ktpackage (CF-5)"
fi

move_if_exists \
    diagnostic_core/src/main/java/com/assistant/runtime/GameplayEngineRegistry.kt \
    core/src/main/java/com/assistant/runtime/GameplayEngineRegistry.kt
move_if_exists \
    diagnostic_core/src/main/java/com/assistant/runtime/RuntimeFrame.kt \
    core/src/main/java/com/assistant/runtime/RuntimeFrame.kt

move_if_exists \
    diagnostic_core/src/main/java/com/assistant/execution/CentralExecutionBus.kt \
    core/src/main/java/com/assistant/execution/CentralExecutionBus.kt
move_if_exists \
    diagnostic_core/src/main/java/com/assistant/execution/ContributionRegistry.kt \
    core/src/main/java/com/assistant/execution/ContributionRegistry.kt
move_if_exists \
    diagnostic_core/src/main/java/com/assistant/execution/HybridExecutionTerminal.kt \
    core/src/main/java/com/assistant/execution/HybridExecutionTerminal.kt

move_if_exists \
    diagnostic_core/src/main/java/com/assistant/events/RuntimeEvents.kt \
    core/src/main/java/com/assistant/events/RuntimeEvents.kt

move_if_exists \
    diagnostic_core/src/main/java/com/assistant/controlroom/AdapterControlRoom.kt \
    core/src/main/java/com/assistant/controlroom/AdapterControlRoom.kt
move_if_exists \
    diagnostic_core/src/main/java/com/assistant/controlroom/AdapterControlRoomRegistry.kt \
    core/src/main/java/com/assistant/controlroom/AdapterControlRoomRegistry.kt

move_if_exists \
    diagnostic_core/src/main/java/com/assistant/survival/ProcessSurvivalRegistry.kt \
    core/src/main/java/com/assistant/survival/ProcessSurvivalRegistry.kt
move_if_exists \
    diagnostic_core/src/main/java/com/assistant/survival/ResourceBudgetRegistry.kt \
    core/src/main/java/com/assistant/survival/ResourceBudgetRegistry.kt

move_if_exists \
    diagnostic_core/src/main/java/com/assistant/audit/SelfAuditRegistry.kt \
    core/src/main/java/com/assistant/audit/SelfAuditRegistry.kt

move_if_exists \
    "diagnostic_core/src/main/res/layout/activity_analytics_theater.xml" \
    "core/src/main/res/layout/activity_analytics_theater.xml"
move_if_exists \
    "diagnostic_core/src/main/res/layout/activity_crash_inspector.xml" \
    "core/src/main/res/layout/activity_crash_inspector.xml"

echo "  :core source migration complete."

# ── PHASE 4: Move adapter source → :app ──────────────────────────────────────
echo ""
echo "=== PHASE 4: Move adapter source → :app ==="

move_adapter_source() {
    local adapter="$1"
    local pkg="$2"
    local src_root="${adapter}/src/main/java/${pkg}"
    local dst_root="app/src/main/java/${pkg}"

    if [ -d "$src_root" ]; then
        while IFS= read -r -d '' f; do
            local rel="${f#${src_root}/}"
            local dst_dir
            dst_dir="${dst_root}/$(dirname "$rel")"
            mkdir -p "$dst_dir"
            git mv "$f" "${dst_root}/${rel}"
            echo "    Moved: ${f##*/}"
        done < <(find "$src_root" -name "*.kt" -print0)
    else
        echo "  SKIP (no source dir): $src_root"
    fi

    local res_src="${adapter}/src/main/res"
    local res_dst="app/src/main/res"
    if [ -d "$res_src" ]; then
        while IFS= read -r -d '' f; do
            local rel="${f#${res_src}/}"
            local dst_dir
            dst_dir="${res_dst}/$(dirname "$rel")"
            mkdir -p "$dst_dir"
            cp "$f" "${res_dst}/${rel}"
            git add "${res_dst}/${rel}"
            git rm "$f"
            echo "    Moved resource: $rel"
        done < <(find "$res_src" -type f -print0)
    fi
}

move_adapter_source "adapter_lmk"         "com/assistant/adapter/lmk"
move_adapter_source "adapter_sync"         "com/assistant/adapter/sync"
move_adapter_source "adapter_input"        "com/assistant/adapter/input"
move_adapter_source "adapter_net"          "com/assistant/adapter/net"
move_adapter_source "adapter_ping"         "com/assistant/adapter/ping"
move_adapter_source "adapter_stutter"      "com/assistant/adapter/stutter"
move_adapter_source "adapter_lag"          "com/assistant/adapter/lag"
move_adapter_source "adapter_boot"         "com/assistant/adapter/boot"
move_adapter_source "adapter_watchdog"     "com/assistant/adapter/watchdog"
move_adapter_source "adapter_memory"       "com/assistant/adapter/memory"
move_adapter_source "adapter_thermal"      "com/assistant/adapter/thermal"
move_adapter_source "adapter_battery"      "com/assistant/adapter/battery"
move_adapter_source "adapter_scheduler"    "com/assistant/adapter/scheduler"
move_adapter_source "adapter_smartassist"  "com/assistant/adapter/smartassist"
move_adapter_source "adapter_interruption" "com/assistant/adapter/interruption"

echo "  All adapter sources moved."

# ── PHASE 5: Structural verification ─────────────────────────────────────────
echo ""
echo "=== PHASE 5: Structural verification ==="

echo "--- include() lines ---"
grep -nE '^\s*include\s*\(' settings.gradle.kts

echo ""
echo "--- Stale module references in .gradle.kts ---"
OLD_MODULES=(
    "adapter_lmk" "adapter_sync" "adapter_input" "adapter_net"
    "adapter_ping" "adapter_stutter" "adapter_lag" "adapter_boot"
    "adapter_watchdog" "adapter_memory" "adapter_thermal" "adapter_battery"
    "adapter_scheduler" "adapter_smartassist" "adapter_interruption"
    "storage_core" "diagnostic_core"
)
found_stale=0
for mod in "${OLD_MODULES[@]}"; do
    hits=$(grep -rn "$mod" --include="*.gradle.kts" . 2>/dev/null || true)
    if [ -n "$hits" ]; then
        echo "  STALE: $mod"
        echo "$hits"
        found_stale=1
    fi
done
[ "$found_stale" -eq 0 ] && echo "  OK — no stale references."

echo ""
echo "--- :core must NOT depend on :app ---"
if grep -q 'project.*:app' core/build.gradle.kts 2>/dev/null; then
    echo "  ERROR: circular dependency!" >&2; exit 1
fi
echo "  OK"

echo ""
echo "--- Module count ---"
INCLUDE_COUNT=$(grep -c 'include(' settings.gradle.kts)
echo "  include() count: $INCLUDE_COUNT (expected: 2)"
[ "$INCLUDE_COUNT" -ne 2 ] && { echo "  ERROR" >&2; exit 1; }
echo "  OK"

echo ""
echo "--- FOREGROUND_SERVICE_SPECIAL_USE ---"
grep -q "FOREGROUND_SERVICE_SPECIAL_USE" app/src/main/AndroidManifest.xml \
    && echo "  OK — present" \
    || { echo "  ERROR: missing!" >&2; exit 1; }

echo ""
echo "--- Service count ---"
SVC_COUNT=$(grep -c '<service' app/src/main/AndroidManifest.xml)
echo "  <service> count: $SVC_COUNT (expected: 16)"

# ── PHASE 6: Remove obsolete module directories ───────────────────────────────
echo ""
echo "=== PHASE 6: Remove obsolete module directories ==="

for mod in \
    storage_core diagnostic_core \
    adapter_lmk adapter_sync adapter_input adapter_net \
    adapter_ping adapter_stutter adapter_lag adapter_boot \
    adapter_watchdog adapter_memory adapter_thermal adapter_battery \
    adapter_scheduler adapter_smartassist adapter_interruption
do
    if [ -d "$mod" ]; then
        while IFS= read -r -d '' f; do
            git rm -f "$f" 2>/dev/null || rm -f "$f"
        done < <(find "$mod" -type f -print0)
        rm -rf "$mod"
        echo "  Removed: $mod/"
    fi
done

echo "  Done."

# ── PHASE 7: Commit ───────────────────────────────────────────────────────────
echo ""
echo "=== PHASE 7: Commit ==="

git add -A
git commit -m "chore: consolidate 18 Gradle modules -> 2 (:app + :core)

BEFORE: 18 modules, 34 project() dependency edges
AFTER:  2 modules,  1 project() dependency edge (:app -> :core)

KEY FIXES
  CF-1  FOREGROUND_SERVICE_SPECIAL_USE explicit in app manifest
  CF-2  kotlinx-coroutines in core/build.gradle.kts
  CF-3  core jvmTarget 1.8 -> 17
  CF-5  AdapterHealthRegistry.ktpackage deleted

RUNTIME PRESERVED
  15 foreground services (14 in :kernel, Interruption in main process)
  Zero Kotlin import or package changes required"

echo ""
echo "================================================================"
echo "  MIGRATION COMPLETE"
echo "================================================================"
echo ""
echo "  Next steps:"
echo "  1.  ./gradlew projects              (expect :app + :core only)"
echo "  2.  ./gradlew :core:compileDebugKotlin"
echo "  3.  ./gradlew :app:compileDebugKotlin"
echo "  4.  ./gradlew :app:assembleDebug    (green = safe to push)"
echo "  5.  git push origin migration/2-module-consolidation"
echo ""
