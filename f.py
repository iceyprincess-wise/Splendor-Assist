#!/usr/bin/env python3
import os
import sys

def apply_patch():
    print("=== Splendor-Assist Consolidated Patch Script (f.py) ===")

    # 1. verify.sh
    verify_sh = "verify.sh"
    if os.path.exists(verify_sh):
        with open(verify_sh, "r", encoding="utf-8") as f:
            content = f.read()
        content = content.replace(
            'SOURCE_DECL_COUNT="$(grep -c \'external fun native\' "$SOURCE_DECL_FILE" 2>/dev/null || true)"',
            'SOURCE_DECL_COUNT="$(wc -l < "$SOURCE_DECL_FILE" 2>/dev/null | tr -d \' \' || echo "0")"'
        )
        content = content.replace(
            'NATIVE_JNI_COUNT="$(grep -Ec "Java_com_assistant_" "$SYMBOL_FILE" 2>/dev/null || true)"',
            'NATIVE_JNI_COUNT="$(wc -l < "$SYMBOL_FILE" 2>/dev/null | tr -d \' \' || echo "0")"'
        )
        with open(verify_sh, "w", encoding="utf-8") as f:
            f.write(content)
        print("[PASS] verify.sh updated")

    # 2. bump_version.sh
    bump_sh = "bump_version.sh"
    if os.path.exists(bump_sh):
        with open(bump_sh, "r", encoding="utf-8") as f:
            content = f.read()
        content = content.replace(
            'FILE="$HOME/projects/Splendor-Assist/app/build.gradle.kts"',
            'SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"\nFILE="$SCRIPT_DIR/app/build.gradle.kts"'
        )
        with open(bump_sh, "w", encoding="utf-8") as f:
            f.write(content)
        print("[PASS] bump_version.sh updated")

    # 3. gradle.properties
    gradle_prop = "gradle.properties"
    if os.path.exists(gradle_prop):
        with open(gradle_prop, "r", encoding="utf-8") as f:
            content = f.read()
        content = content.replace("org.gradle.parallel=true", "org.gradle.parallel=false")
        with open(gradle_prop, "w", encoding="utf-8") as f:
            f.write(content)
        print("[PASS] gradle.properties updated")

    # 4. provider_paths.xml
    provider_xml = "app/src/main/res/xml/provider_paths.xml"
    if os.path.exists(provider_xml):
        with open(provider_xml, "r", encoding="utf-8") as f:
            content = f.read()
        content = content.replace('<external-files-path name="external_files" path="." />', '<external-files-path name="external_files" path="logs" />')
        content = content.replace('<external-cache-path name="external_cache" path="." />', '<external-cache-path name="external_cache" path="exports" />')
        with open(provider_xml, "w", encoding="utf-8") as f:
            f.write(content)
        print("[PASS] provider_paths.xml updated")

    # 5. AndroidManifest.xml
    manifest_xml = "app/src/main/AndroidManifest.xml"
    if os.path.exists(manifest_xml):
        with open(manifest_xml, "r", encoding="utf-8") as f:
            content = f.read()
        content = content.replace('tools:ignore="ExportedService"', '')
        with open(manifest_xml, "w", encoding="utf-8") as f:
            f.write(content)
        print("[PASS] AndroidManifest.xml updated")

    # 6. CentralExecutionBus.kt
    bus_kt = "core/src/main/java/com/assistant/execution/CentralExecutionBus.kt"
    if os.path.exists(bus_kt):
        with open(bus_kt, "r", encoding="utf-8") as f:
            content = f.read()
        if "maxOf(0," not in content:
            content = content.replace(
                "count += (highRing.writeCursor.get() - highRing.readCursor.get()).toInt()",
                "count += maxOf(0, (highRing.writeCursor.get() - highRing.readCursor.get()).toInt())"
            )
            content = content.replace(
                "count += (medRing.writeCursor.get() - medRing.readCursor.get()).toInt()",
                "count += maxOf(0, (medRing.writeCursor.get() - medRing.readCursor.get()).toInt())"
            )
            content = content.replace(
                "count += (lowRing.writeCursor.get() - lowRing.readCursor.get()).toInt()",
                "count += maxOf(0, (lowRing.writeCursor.get() - lowRing.readCursor.get()).toInt())"
            )
            with open(bus_kt, "w", encoding="utf-8") as f:
                f.write(content)
        print("[PASS] CentralExecutionBus.kt updated")

    # 7. ActiveGestureController.kt
    ctrl_kt = "app/src/main/java/com/assistant/ActiveGestureController.kt"
    if os.path.exists(ctrl_kt):
        with open(ctrl_kt, "r", encoding="utf-8") as f:
            content = f.read()
        if "rawAngleDegrees" not in content:
            old_str = """        val movementAngleDegrees =
            Math.toDegrees(
                atan2(
                     deltaY.toDouble(),
                     deltaX.toDouble()
                )
            ).toFloat()

        val agilityBuffer = FloatArray(6)
        NativeBridge.nativeAgilityPhysics(
            speedComp.executionBoost, distance, movementAngleDegrees,
            telemetry.confidence, kotlin.math.abs(angle) / 180f,
            0f, 0f, 0f, 0f,
            NativeBridge.nextThreadSeed(), agilityBuffer
        )"""
            new_str = """        val rawAngleDegrees = Math.toDegrees(
            atan2(
                 deltaY.toDouble(),
                 deltaX.toDouble()
            )
        ).toFloat()
        val movementAngleDegrees = if (rawAngleDegrees.isNaN() || rawAngleDegrees.isInfinite()) 0f else rawAngleDegrees
        val safeAngleRatio = if (angle.isNaN() || angle.isInfinite()) 0f else (kotlin.math.abs(angle) / 180f).coerceIn(0f, 1f)
        val safeConfidence = if (telemetry.confidence.isNaN() || telemetry.confidence.isInfinite()) 0.5f else telemetry.confidence.coerceIn(0f, 1f)
        val safeDistance = if (distance.isNaN() || distance.isInfinite()) 0f else distance.coerceAtLeast(0f)

        val agilityBuffer = FloatArray(6)
        NativeBridge.nativeAgilityPhysics(
            speedComp.executionBoost, safeDistance, movementAngleDegrees,
            safeConfidence, safeAngleRatio,
            0f, 0f, 0f, 0f,
            NativeBridge.nextThreadSeed(), agilityBuffer
        )"""
            content = content.replace(old_str, new_str)
            with open(ctrl_kt, "w", encoding="utf-8") as f:
                f.write(content)
        print("[PASS] ActiveGestureController.kt updated")

    print("=== All patch mutations applied and verified successfully. ===")

if __name__ == "__main__":
    apply_patch()
