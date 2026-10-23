#!/usr/bin/env python3
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent

ACTIVE_FILE = ROOT / "app/src/main/java/com/assistant/ActiveGestureController.kt"
GAMEPLAY_FILE = ROOT / "app/src/main/java/com/assistant/gameplay_engine.kt"
MANIFEST_FILE = ROOT / "app/src/main/AndroidManifest.xml"
ACC_CONFIG_FILE = ROOT / "app/src/main/res/xml/accessibility_service_config.xml"
ACC_ENGINE_FILE = ROOT / "app/src/main/java/com/assistant/SmartAssistAccessibilityEngine.kt"
TOUCH_STATE_FILE = ROOT / "app/src/main/java/com/assistant/UserTouchState.kt"

def fail(msg):
    print(f"FAIL: {msg}", file=sys.stderr)
    sys.exit(1)

def read_text(path):
    if not path.exists():
        fail(f"Missing file: {path}")
    return path.read_text(encoding="utf-8")

def write_text(path, text):
    path.write_text(text, encoding="utf-8")

def replace_exact(src, old, new, label):
    if old not in src:
        fail(f"Anchor missing for {label}")
    if src.count(old) > 1:
        fail(f"Multiple matches for anchor {label}")
    return src.replace(old, new, 1)

def main():
    print("=== STARTING SPLENDOR-ASSIST MUTATION PATCH (f.py) ===")

    # 1. Create UserTouchState.kt
    touch_state_code = """package com.assistant

import java.util.concurrent.atomic.AtomicBoolean

object UserTouchState {
    private val touching = AtomicBoolean(false)

    fun setTouching(value: Boolean) {
        touching.set(value)
    }

    fun isTouching(): Boolean = touching.get()
}
"""
    write_text(TOUCH_STATE_FILE, touch_state_code)
    print("✓ Created UserTouchState.kt")

    # 2. Patch ActiveGestureController.kt
    active_src = read_text(ACTIVE_FILE)
    old_active_block = """        val arbitration =
            AuthorityArbitrationEngine.arbitrate(
                mode = mode,
        // SPLENDOR_THROUGH_PASS_ARBITRATION_V1
        // Viable through-ball telemetry stays inside the unified
        // arbitration -> execution bus -> accessibility path.
        val passArbitrationX =
            if (mode == 1 && selectedThroughLane != null) throughReceiverX else truePass.x

        val passArbitrationY =
            if (mode == 1 && selectedThroughLane != null) throughReceiverY - throughLead else truePass.y

                passX = passArbitrationX + speedContainmentOffset,"""

    new_active_block = """        // SPLENDOR_THROUGH_PASS_ARBITRATION_V1
        // Viable through-ball telemetry stays inside the unified
        // arbitration -> execution bus -> accessibility path.
        val passArbitrationX =
            if (mode == 1 && selectedThroughLane != null) throughReceiverX else truePass.x

        val passArbitrationY =
            if (mode == 1 && selectedThroughLane != null) throughReceiverY - throughLead else truePass.y

        val arbitration =
            AuthorityArbitrationEngine.arbitrate(
                mode = mode,
                passX = passArbitrationX + speedContainmentOffset,"""

    active_src = replace_exact(active_src, old_active_block, new_active_block, "ActiveGestureController arbitration syntax fix")
    write_text(ACTIVE_FILE, active_src)
    print("✓ Patched ActiveGestureController.kt syntax error")

    # 3. Patch AndroidManifest.xml
    manifest_src = read_text(MANIFEST_FILE)
    old_vpn_perm = '    <uses-permission android:name="android.permission.BIND_VPN_SERVICE" tools:ignore="ProtectedPermissions" />\n'
    if old_vpn_perm in manifest_src:
        manifest_src = manifest_src.replace(old_vpn_perm, "", 1)
        print("✓ Removed BIND_VPN_SERVICE uses-permission from AndroidManifest.xml")
    else:
        print("• BIND_VPN_SERVICE uses-permission already removed or different format")

    old_vpn_service = """        <service
            android:name="com.assistant.PingEliminatorVpnService"
            android:exported="true"
            android:permission="android.permission.BIND_VPN_SERVICE" >"""

    new_vpn_service = """        <service
            android:name="com.assistant.PingEliminatorVpnService"
            android:exported="false"
            android:foregroundServiceType="systemExempted"
            android:permission="android.permission.BIND_VPN_SERVICE" >"""

    if old_vpn_service in manifest_src:
        manifest_src = replace_exact(manifest_src, old_vpn_service, new_vpn_service, "PingEliminatorVpnService attributes")
        print("✓ Patched PingEliminatorVpnService in AndroidManifest.xml")

    write_text(MANIFEST_FILE, manifest_src)

    # 4. Patch accessibility_service_config.xml
    acc_config_src = read_text(ACC_CONFIG_FILE)
    old_events = 'android:accessibilityEventTypes="typeViewClicked|typeViewScrolled|typeWindowStateChanged"'
    new_events = 'android:accessibilityEventTypes="typeViewClicked|typeViewScrolled|typeWindowStateChanged|typeTouchInteractionStart|typeTouchInteractionEnd"'
    if old_events in acc_config_src:
        acc_config_src = replace_exact(acc_config_src, old_events, new_events, "accessibilityEventTypes")
        write_text(ACC_CONFIG_FILE, acc_config_src)
        print("✓ Added touch interaction events to accessibility_service_config.xml")

    # 5. Patch SmartAssistAccessibilityEngine.kt
    acc_engine_src = read_text(ACC_ENGINE_FILE)
    old_acc_event = """        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        try {
            VisionTrust.onForegroundPackage(pkg, VisionTrust.GAME_PACKAGES)
        } catch (_: Throwable) {
        }"""

    new_acc_event = """        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ->
                UserTouchState.setTouching(true)
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END ->
                UserTouchState.setTouching(false)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                try {
                    VisionTrust.onForegroundPackage(pkg, VisionTrust.GAME_PACKAGES)
                } catch (_: Throwable) {
                }
            }
        }"""

    acc_engine_src = replace_exact(acc_engine_src, old_acc_event, new_acc_event, "SmartAssistAccessibilityEngine touch event handling")
    write_text(ACC_ENGINE_FILE, acc_engine_src)
    print("✓ Patched SmartAssistAccessibilityEngine.kt event handling")

    # 6. Patch gameplay_engine.kt
    gameplay_src = read_text(GAMEPLAY_FILE)

    # 6a. FightingSpirit accuracyRetention
    old_fs = """        // Apply authority boost to the winning contribution's weight
        if (fs.active && best != null) {
            lastWeight = (best.weight * fs.authorityBoost).coerceIn(0f, 1f)
        }"""

    new_fs = """        // Apply authority boost and accuracy retention to the winning contribution's weight
        if (fs.active && best != null) {
            lastWeight = (best.weight * fs.authorityBoost * fs.accuracyRetention).coerceIn(0f, 1f)
        }"""

    gameplay_src = replace_exact(gameplay_src, old_fs, new_fs, "FightingSpirit accuracyRetention consumption")

    # 6b. DashPressureContributor isPlayerHoldingPressure
    old_pressure = """            oppY   = oppY,
            isPlayerHoldingPressure = true
        )"""

    new_pressure = """            oppY   = oppY,
            isPlayerHoldingPressure = UserTouchState.isTouching()
        )"""

    gameplay_src = replace_exact(gameplay_src, old_pressure, new_pressure, "DashPressureContributor isPlayerHoldingPressure")

    # 6c. GestureExecutionAuthority user touch gate
    old_authority = """        return try {
            val result = service.dispatchGesture(gesture, wrappedCallback, handler)"""

    new_authority = """        if (!UserTouchState.isTouching()) {
            RuntimeLogger.log(
                "Gesture blocked: no active user touch",
                "SMART_ASSIST"
            )
            return false
        }

        return try {
            val result = service.dispatchGesture(gesture, wrappedCallback, handler)"""

    gameplay_src = replace_exact(gameplay_src, old_authority, new_authority, "GestureExecutionAuthority user touch gate")

    write_text(GAMEPLAY_FILE, gameplay_src)
    print("✓ Patched gameplay_engine.kt (Fighting Spirit, UserTouchState, Gesture Execution Authority)")

    print("=== ALL MUTATIONS APPLIED SUCCESSFULLY ===")

if __name__ == "__main__":
    main()
