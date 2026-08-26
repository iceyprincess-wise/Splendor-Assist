#!/usr/bin/env python3
import os, sys

B = "/data/data/com.termux/files/home/projects/Splendor-Assist/app/src/main/java/com/assistant"
FILES = {
    "INT": B + "/adapter/interruption/InterruptionAdapterService.kt",
    "OV": B + "/OverlayService.kt",
}

def load(p):
    with open(p, 'rb') as f: raw = f.read()
    return raw, raw.decode('utf-8').replace('\r\n', '\n')

def save(p, r, t):
    with open(p, 'wb') as f: f.write((t.replace('\n', '\r\n') if b'\r\n' in r else t).encode('utf-8'))

def rep(t, old, new, tag):
    if new in t:
        print(f"PROVEN - {tag} (already applied, skip)")
        return t
    c = t.count(old)
    if c != 1:
        print(f"BLOCKED - anchor x{c}: {tag}; NO change.")
        sys.exit(1)
    print(f"PROVEN - {tag}")
    return t.replace(old, new, 1)

print("=== SPLDOR-ASSIST V12 (HEARTBEAT SECURITY & CLEANUP) ===")

# 1) InterruptionAdapterService: Isolate registerReceiver from heartbeat publish
r, t = load(FILES["INT"])
OLD_INT = """                try {
                    val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val batteryLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val charging = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
                    val state = InterruptionCoordinator.evaluate(batteryLevel, charging, 0)
                    InterruptionRepository.save(state)
                    val throttleMode = when (state.severity) {
                        "CRITICAL" -> "AGGRESSIVE_THROTTLE"
                        "THROTTLE" -> "MODERATE_THROTTLE"
                        "WARNING" -> "LIGHT_THROTTLE"
                        else -> "NORMAL"
                    }
                    AdapterHealthRegistry.update(
                        AdapterHealthSnapshot(
                            adapterName = "adapter_interruption",
                            status = state.severity,
                            lastHeartbeat = System.currentTimeMillis(),
                            errorCount = errorCount.get(),
                            recoveryCount = 0,
                            details = "battery=${state.batteryLevel},call=${TelephonyStateRepository.activeCall},mode=$throttleMode"
                        )
                    )
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                    RuntimeLogger.log("InterruptionAdapter heartbeat failed :: ${e.javaClass.simpleName}", "HEALTH")
                }"""

NEW_INT = """                // V12 ROOT-CAUSE FIX: registerReceiver can throw SecurityException on
                // Android 14+/HyperOS when called from background service. If it throws,
                // the entire try block aborted and the heartbeat was NEVER published,
                // causing boosterAlive=false and permanent booster-not-ready degradation.
                // FIX: Wrap registerReceiver individually. Use defaults if it fails.
                // The heartbeat MUST publish regardless of battery read success.
                var batteryLevel = -1
                var charging = false
                try {
                    val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    batteryLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    charging = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
                } catch (_: SecurityException) {
                    // Battery read blocked by OS; proceed with defaults to keep heartbeat alive
                } catch (_: Throwable) {}

                try {
                    val state = InterruptionCoordinator.evaluate(batteryLevel, charging, 0)
                    InterruptionRepository.save(state)
                    val throttleMode = when (state.severity) {
                        "CRITICAL" -> "AGGRESSIVE_THROTTLE"
                        "THROTTLE" -> "MODERATE_THROTTLE"
                        "WARNING" -> "LIGHT_THROTTLE"
                        else -> "NORMAL"
                    }
                    AdapterHealthRegistry.update(
                        AdapterHealthSnapshot(
                            adapterName = "adapter_interruption",
                            status = state.severity,
                            lastHeartbeat = System.currentTimeMillis(),
                            errorCount = errorCount.get(),
                            recoveryCount = 0,
                            details = "battery=${state.batteryLevel},call=${TelephonyStateRepository.activeCall},mode=$throttleMode"
                        )
                    )
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                    RuntimeLogger.log("InterruptionAdapter heartbeat failed :: ${e.javaClass.simpleName}", "HEALTH")
                }"""
t = rep(t, OLD_INT, NEW_INT, "INT-heartbeat-isolation")
save(FILES["INT"], r, t)

# 2) OverlayService: Clean up unused variable warning from V11
r_ov, t_ov = load(FILES["OV"])
OLD_OV_WARN = "            val thisFrameCount = ++captureFrameCount\n"
NEW_OV_WARN = "            captureFrameCount++\n"
if OLD_OV_WARN in t_ov:
    t_ov = t_ov.replace(OLD_OV_WARN, NEW_OV_WARN, 1)
    print("PROVEN - OV: unused variable warning cleaned.")
    save(FILES["OV"], r_ov, t_ov)
else:
    print("PROVEN - OV: warning already cleaned (skip).")

print("=== V12 COMPLETE - run: ./gradlew :app:compileDebugKotlin ===")
