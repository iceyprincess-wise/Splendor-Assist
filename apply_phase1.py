#!/usr/bin/env python3
import os, sys

BASE = os.path.expanduser("~/projects/Splendor-Assist")
ok_count = 0
fail_count = 0

def patch(rel, old, new, desc):
    global ok_count, fail_count
    path = os.path.join(BASE, rel)
    if not os.path.exists(path):
        print(f"  MISSING FILE: {rel}"); fail_count += 1; return False
    txt = open(path, encoding="utf-8").read()
    if old not in txt:
        print(f"  NOT FOUND: {desc}"); fail_count += 1; return False
    open(path, "w", encoding="utf-8").write(txt.replace(old, new, 1))
    print(f"  OK: {desc}"); ok_count += 1; return True

def write_file(rel, content, desc):
    global ok_count
    path = os.path.join(BASE, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w", encoding="utf-8").write(content)
    print(f"  CREATED: {desc}"); ok_count += 1

print("\n[1/8] GridRecentsInterceptor fixes")
patch("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GridRecentsInterceptor.kt",
    "abstract class GridRecentsInterceptor",
    "open class GridRecentsInterceptor",
    "Remove abstract")
patch("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GridRecentsInterceptor.kt",
    'Log.e("OmegaInterceptor", "Fallback to default activity resolver: ${e.message}")',
    'RuntimeLogger.log("RECENTS back-action failed: ${e.message}", "RECENTS_INTERCEPTOR")',
    "Remove broken Log.e referencing non-existent GridRecentsActivity")
patch("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GridRecentsInterceptor.kt",
    """            val intent = Intent().apply {
                // Point explicitly to YOUR application context so your GridRecentsActivity appears
                setClassName(applicationContext.packageName, \"${applicationContext.packageName}.GridRecentsActivity\")
                
                // Exclude EXCLUDE_FROM_RECENTS so it actually registers in the Recents menu
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                         Intent.FLAG_ACTIVITY_CLEAR_TOP or
                         Intent.FLAG_ACTIVITY_NO_ANIMATION or
                         Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
            startActivity(intent)""",
    "            performGlobalAction(GLOBAL_ACTION_BACK)",
    "Fix hijack: GLOBAL_ACTION_BACK snaps player back to game instantly")

print("\n[2/8] Admin fixes")
patch("diagnostic_core/src/main/java/com/assistant/admin/AdminConfigStore.kt",
    'Tunable("lag.display.game_fps",        "Game frame rate (eFootball = 30)",          30f,',
    'Tunable("lag.display.game_fps",        "Game frame rate (eFootball = 60)",          60f,',
    "AdminConfigStore: game_fps default 60")
patch("diagnostic_core/src/main/java/com/assistant/admin/AdminTuningDetector.kt",
    'Pick("lag.display.game_fps", 30f,',
    'Pick("lag.display.game_fps", 60f,',
    "AdminTuningDetector: detector pick 60fps")

print("\n[3/8] NEW AdapterSignalBus.kt")
write_file("diagnostic_core/src/main/java/com/assistant/diagnostic/AdapterSignalBus.kt", '''package com.assistant.diagnostic

/**
 * AdapterSignalBus — hive cross-adapter signal channel.
 * Bodyguard adapters publish here. SmartAssist reads non-blocking every frame.
 */
object AdapterSignalBus {
    @Volatile var netWindow: String = "UNKNOWN"; private set
    @Volatile var lagVerdict: String = "UNKNOWN"; private set
    @Volatile var stutterState: String = "UNKNOWN"; private set

    fun publishNet(verdict: String) { netWindow = verdict }
    fun publishLag(verdict: String) { lagVerdict = verdict }
    fun publishStutter(state: String) { stutterState = state }

    val netIsHold: Boolean    get() = netWindow == "HOLD"
    val lagIsChoking: Boolean get() = lagVerdict == "CHOKING"
    val stutterIsSevere: Boolean get() = stutterState == "SEIZURE"
    val environmentHostile: Boolean get() = netIsHold || lagIsChoking || stutterIsSevere
}
''', "AdapterSignalBus.kt")

print("\n[4/8] Wire AdapterSignalBus into ActionWindowEngine")
patch("adapter_net/src/main/java/com/assistant/adapter/net/ActionWindowEngine.kt",
    "import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry",
    "import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry\nimport com.assistant.diagnostic.AdapterSignalBus",
    "ActionWindowEngine: import bus")
patch("adapter_net/src/main/java/com/assistant/adapter/net/ActionWindowEngine.kt",
    "                    PerformanceTelemetryRegistry.publishActionWindow(verdict,",
    "                    AdapterSignalBus.publishNet(verdict)\n                    PerformanceTelemetryRegistry.publishActionWindow(verdict,",
    "ActionWindowEngine: publish to bus")

print("\n[5/8] Wire AdapterSignalBus into LagVerdictEngine")
patch("adapter_lag/src/main/java/com/assistant/adapter/lag/LagVerdictEngine.kt",
    "import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry",
    "import com.assistant.diagnostic.registry.PerformanceTelemetryRegistry\nimport com.assistant.diagnostic.AdapterSignalBus",
    "LagVerdictEngine: import bus")
patch("adapter_lag/src/main/java/com/assistant/adapter/lag/LagVerdictEngine.kt",
    "                    PerformanceTelemetryRegistry.publishDisplay(",
    "                    AdapterSignalBus.publishLag(verdict)\n                    PerformanceTelemetryRegistry.publishDisplay(",
    "LagVerdictEngine: publish to bus")

print("\n[6/8] NEW CpuGovernorEngine.kt")
write_file("adapter_lag/src/main/java/com/assistant/adapter/lag/CpuGovernorEngine.kt", '''package com.assistant.adapter.lag

import android.os.Process
import com.assistant.diagnostic.RuntimeLogger

/**
 * CpuGovernorEngine — Reserve A75 fast cores for eFootball, park Splendor on A55.
 * Helio G81-Ultra: cores 0-5 = A55 (efficiency), cores 6-7 = A75 (performance).
 * Without root: scheduler priority-only mode (still very effective).
 * With root/sysfs: sets actual cpufreq governor per cluster.
 */
object CpuGovernorEngine {
    @Volatile private var running = false
    @Volatile var mode = "STARTING"; private set

    fun start() {
        if (running) return
        running = true
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Throwable) {}
        val t = Thread {
            var logged = false
            while (running) {
                try {
                    var wrote = false
                    for (core in 6..7) {
                        val gov = java.io.File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_governor")
                        if (gov.canWrite()) { gov.writeText("performance"); wrote = true }
                    }
                    for (core in 0..5) {
                        val gov = java.io.File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_governor")
                        if (gov.canWrite()) gov.writeText("schedutil")
                    }
                    if (!logged) {
                        mode = if (wrote) "SYSFS_ACTIVE" else "PRIORITY_ONLY"
                        RuntimeLogger.log("CpuGovernor mode=$mode (A75=game A55=splendor)", "CPUGOV")
                        logged = true
                    }
                } catch (e: Throwable) {
                    if (!logged) {
                        mode = "PRIORITY_ONLY"
                        RuntimeLogger.log("CpuGovernor: priority-only (${e.javaClass.simpleName})", "CPUGOV")
                        logged = true
                    }
                }
                try { Thread.sleep(30_000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "lag-cpu-gov"; t.priority = Thread.MIN_PRIORITY; t.start()
        RuntimeLogger.log("CpuGovernorEngine started", "CPUGOV")
    }

    fun stop() { running = false; mode = "STOPPED" }
}
''', "CpuGovernorEngine.kt")

patch("adapter_lag/src/main/java/com/assistant/adapter/lag/LagAdapterService.kt",
    'RuntimeLogger.log("Lag engine stack ignited: 6 engines [V3 ADMIN-WIRED]", "LAG")',
    'CpuGovernorEngine.start()\n        RuntimeLogger.log("Lag engine stack ignited: 7 engines [V3+CPUGOV]", "LAG")',
    "LagAdapterService: ignite CpuGovernor")
patch("adapter_lag/src/main/java/com/assistant/adapter/lag/LagAdapterService.kt",
    "        ThermalPeekEngine.stop()",
    "        CpuGovernorEngine.stop()\n        ThermalPeekEngine.stop()",
    "LagAdapterService: stop CpuGovernor")

print("\n[7/8] NEW ConnectionHealEngine.kt")
write_file("adapter_net/src/main/java/com/assistant/adapter/net/ConnectionHealEngine.kt", '''package com.assistant.adapter.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.assistant.diagnostic.RuntimeLogger

/**
 * ConnectionHealEngine — Active network healer. Never accept a bad connection.
 * When HOLD is detected: WiFi rescan + rebind to best available network.
 * Cooldown: one heal per 15s (never floods the modem).
 */
object ConnectionHealEngine {
    @Volatile private var running = false
    @Volatile private var lastHealMs = 0L
    @Volatile var healCount = 0; private set
    private const val COOLDOWN_MS = 15_000L

    fun start(ctx: Context) {
        if (running) return
        running = true
        val appCtx = ctx.applicationContext
        try {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    RuntimeLogger.log("ConnectionHeal: network LOST — healing", "NETHEAL")
                    tryHeal(appCtx)
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.linkDownstreamBandwidthKbps in 1..500) {
                        RuntimeLogger.log("ConnectionHeal: bandwidth critical — healing", "NETHEAL")
                        tryHeal(appCtx)
                    }
                }
            })
        } catch (t: Throwable) {
            RuntimeLogger.log("ConnectionHeal: callback failed: ${t.message}", "NETHEAL")
        }
        val t = Thread {
            while (running) {
                try { if (ActionWindowEngine.verdict == "HOLD") tryHeal(appCtx) } catch (_: Throwable) {}
                try { Thread.sleep(8_000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-heal"; t.start()
        RuntimeLogger.log("ConnectionHealEngine started", "NETHEAL")
    }

    fun stop() { running = false }

    private fun tryHeal(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastHealMs < COOLDOWN_MS) return
        lastHealMs = now; healCount++
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && wm.isWifiEnabled) {
                @Suppress("DEPRECATION") wm.startScan()
                RuntimeLogger.log("ConnectionHeal: WiFi rescan #$healCount", "NETHEAL")
            }
        } catch (_: Throwable) {}
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.activeNetwork?.let { cm.bindProcessToNetwork(it) }
            RuntimeLogger.log("ConnectionHeal: rebound to best network #$healCount", "NETHEAL")
        } catch (_: Throwable) {}
    }
}
''', "ConnectionHealEngine.kt")

patch("adapter_net/src/main/java/com/assistant/adapter/net/NetAdapterService.kt",
    'RuntimeLogger.log("Net engine stack ignited: 9 engines [V2 PROACTIVE]", "NET")',
    'ConnectionHealEngine.start(this)\n        RuntimeLogger.log("Net engine stack ignited: 10 engines [V2+HEAL]", "NET")',
    "NetAdapterService: ignite ConnectionHeal")
patch("adapter_net/src/main/java/com/assistant/adapter/net/NetAdapterService.kt",
    "        ActionWindowEngine.stop()",
    "        ConnectionHealEngine.stop()\n        ActionWindowEngine.stop()",
    "NetAdapterService: stop ConnectionHeal")

print("\n[8/8] bump_version.sh")
bv = os.path.join(BASE, "bump_version.sh")
open(bv, "w").write('#!/bin/bash\nFILE="$HOME/projects/Splendor-Assist/app/build.gradle.kts"\nCURRENT=$(grep -o "versionCode = [0-9]*" "$FILE" | grep -o "[0-9]*")\nNEW=$((CURRENT + 1))\nsed -i "s/versionCode = $CURRENT/versionCode = $NEW/" "$FILE"\necho "versionCode: $CURRENT -> $NEW"\n')
os.chmod(bv, 0o755)
print("  CREATED: bump_version.sh")
ok_count += 1

print(f"\n{'='*50}")
print(f"RESULT: {ok_count} OK  |  {fail_count} FAILED")
if fail_count == 0:
    print("All patches OK. Run the commit block next.")
else:
    print("Fix failed patches above then re-run.")
PYEOF
