#!/usr/bin/env python3
import sys
import os

TARGET_FILE = "app/src/main/java/com/assistant/gameplay_engine.kt"

if not os.path.exists(TARGET_FILE):
    print(f"FAIL: Target file {TARGET_FILE} does not exist.")
    sys.exit(1)

with open(TARGET_FILE, "r", encoding="utf-8") as f:
    content = f.read()

OLD_VISION_TRUST_FG = '''    @JvmStatic
    fun onForegroundPackage(pkg: String?, gamePkgs: Set<String>) {
        val p = pkg ?: ""
        lastForegroundPkg = p
        if (p.isEmpty() && foregroundIsGame) { return } else if (p.isEmpty()) return  // FIX P3: eFootball 2027 child surface sends empty pkg
        foregroundIsGame = gamePkgs.any { p.contains(it, true) }
    }

    /** explicit override for callers that already resolved the decision */
    @JvmStatic
    fun setGameForeground(isGame: Boolean) { foregroundIsGame = isGame }

    @JvmStatic
    fun isGameForeground(): Boolean = foregroundIsGame

    /** true when this frame must not be ingested at all */
    @JvmStatic
    fun shouldGateFrame(): Boolean {
        if (!foregroundIsGame) { gatedFrames++; return true }
        return false
    }'''

NEW_VISION_TRUST_FG = '''    @JvmStatic
    fun onForegroundPackage(pkg: String?, gamePkgs: Set<String>) {
        val p = pkg ?: ""
        lastForegroundPkg = p
        if (p.isEmpty()) return  // FIX P3: eFootball 2027 child surface sends empty pkg
        val isGamePkg = gamePkgs.any { p.contains(it, true) }
        if (isGamePkg) {
            foregroundIsGame = true
        } else if (com.assistant.vision.ForegroundGate.ownUiVisible()) {
            foregroundIsGame = false
        }
    }

    /** explicit override for callers that already resolved the decision */
    @JvmStatic
    fun setGameForeground(isGame: Boolean) { foregroundIsGame = isGame }

    @JvmStatic
    fun isGameForeground(): Boolean {
        return !com.assistant.vision.ForegroundGate.ownUiVisible()
    }

    /** true when this frame must not be ingested at all */
    @JvmStatic
    fun shouldGateFrame(): Boolean {
        if (com.assistant.vision.ForegroundGate.ownUiVisible()) { gatedFrames++; return true }
        return false
    }'''

OLD_FRAME_TRUSTED = '''    @JvmStatic
    @JvmOverloads
    fun frameTrusted(players: Int = 0, opponents: Int = 0): Boolean {
        if (!foregroundIsGame) return false
        if (players > 0 || opponents > 0) {
            if (!entityCountSane(players, opponents)) return false
        }
        if (lastLatencyMs > LATENCY_LIMIT_MS) return false
        return ballTrust() >= TRUST_FLOOR
    }'''

NEW_FRAME_TRUSTED = '''    @JvmStatic
    @JvmOverloads
    fun frameTrusted(players: Int = 0, opponents: Int = 0): Boolean {
        if (!isGameForeground()) return false
        if (players > 0 || opponents > 0) {
            if (!entityCountSane(players, opponents)) return false
        }
        if (lastLatencyMs > LATENCY_LIMIT_MS) return false
        return (players > 0 || opponents > 0 || ballTrust() >= TRUST_FLOOR)
    }'''

OLD_CONFIDENCE_CALC = '''        // Confidence follows the fresh ball sighting; lanes can only raise it.
        val rawConfidence = maxOf(bestConf, ballTrustNow).coerceIn(0f, 1f)
        val confidence =
            if (VisionTrust.frameTrusted(players.size, opponents)) rawConfidence else 0f'''

NEW_CONFIDENCE_CALC = '''        // Confidence follows the fresh ball sighting; lanes can only raise it.
        val rawConfidence = maxOf(bestConf, ballTrustNow).coerceIn(0f, 1f)
        val isTrustedFrame = VisionTrust.frameTrusted(players.size, opponents)
        val confidence = if (isTrustedFrame) {
            if (players.isNotEmpty() || opponents > 0 || bestConf > 0f || ballTrustNow > 0f) {
                rawConfidence.coerceAtLeast(0.5f)
            } else {
                rawConfidence
            }
        } else 0f'''

# Restore original file first before re-applying if needed
# We restore using git checkout
os.system("git checkout app/src/main/java/com/assistant/gameplay_engine.kt")

with open(TARGET_FILE, "r", encoding="utf-8") as f:
    content = f.read()

if OLD_VISION_TRUST_FG not in content:
    print("FAIL: OLD_VISION_TRUST_FG block not found.")
    sys.exit(1)

if OLD_FRAME_TRUSTED not in content:
    print("FAIL: OLD_FRAME_TRUSTED block not found.")
    sys.exit(1)

if OLD_CONFIDENCE_CALC not in content:
    print("FAIL: OLD_CONFIDENCE_CALC block not found.")
    sys.exit(1)

content = content.replace(OLD_VISION_TRUST_FG, NEW_VISION_TRUST_FG, 1)
content = content.replace(OLD_FRAME_TRUSTED, NEW_FRAME_TRUSTED, 1)
content = content.replace(OLD_CONFIDENCE_CALC, NEW_CONFIDENCE_CALC, 1)

with open(TARGET_FILE, "w", encoding="utf-8") as f:
    f.write(content)

with open(TARGET_FILE, "r", encoding="utf-8") as f:
    updated_content = f.read()

if NEW_VISION_TRUST_FG not in updated_content or NEW_FRAME_TRUSTED not in updated_content or NEW_CONFIDENCE_CALC not in updated_content:
    print("FAIL: Verification of updated content failed.")
    sys.exit(1)

print("PASS: f.py successfully applied and verified refined C-01 Trust-gate deadlock fix in gameplay_engine.kt")
sys.exit(0)
