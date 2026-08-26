#!/usr/bin/env python3
import os, sys

B = "/data/data/com.termux/files/home/projects/Splendor-Assist/app/src/main/java/com/assistant"
FILES = {
    "MAG": B + "/adapter/smartassist/contributors/MagneticFeetContributor.kt",
    "SHOT": B + "/contributors/ShotContributor.kt",
    "THREAT": B + "/contributors/ThreatPriorityContributor.kt",
    "PANIC": B + "/contributors/PanicSaveContributor.kt",
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

print("=== SPLDOR-ASSIST V9 (PROBABILISTIC AUTHORITY SCALING) ===")

# 1) MagneticFeetContributor: Replace hard hasBall/trusted gate with scaling
r, t = load(FILES["MAG"])
OLD_MAG = """        // Bail out early if we don’t have a trusted frame or the ball isn’t ours.
        if (!frame.trusted || !frame.hasBall) return null"""
NEW_MAG = """        // V9 ARCHITECTURAL FIX: Replace hard boolean kill-switch with probabilistic scaling.
        // Football is fluid; possession is probabilistic. We no longer "die" when the ball
        // is obscured or possession flickers. We scale authority instead.
        if (frame.confidence < 0.05f) return null // Hard floor for completely blind frames
        
        val possessionWeight = if (frame.hasBall) 1.0f else 0.4f // Allow transition play
        val trustWeight = if (frame.trusted) 1.0f else 0.6f
        val fluidMultiplier = possessionWeight * trustWeight"""
t = rep(t, OLD_MAG, NEW_MAG, "MAG-gate")

OLD_MAG_AUTH = "val authority = rawAuthority.coerceIn(0f, cap)"
NEW_MAG_AUTH = "val authority = (rawAuthority * fluidMultiplier).coerceIn(0f, cap)"
t = rep(t, OLD_MAG_AUTH, NEW_MAG_AUTH, "MAG-auth")

OLD_MAG_CONF = "val confidence = ((frame.confidence * visionWeight) +"
NEW_MAG_CONF = "val confidence = (((frame.confidence * visionWeight) +"
t = rep(t, OLD_MAG_CONF, NEW_MAG_CONF, "MAG-conf-open")

OLD_MAG_CONF_CLOSE = "(possessionNorm * engineWeight)).coerceIn(0f, 1f)"
NEW_MAG_CONF_CLOSE = "(possessionNorm * engineWeight)) * fluidMultiplier).coerceIn(0f, 1f)"
t = rep(t, OLD_MAG_CONF_CLOSE, NEW_MAG_CONF_CLOSE, "MAG-conf-close")
save(FILES["MAG"], r, t)

# 2) ShotContributor: Replace hard hasBall/trusted gate with scaling
r, t = load(FILES["SHOT"])
OLD_SHOT = "if (!frame.trusted || !frame.hasBall) return null"
NEW_SHOT = """if (frame.confidence < 0.05f) return null
        val possessionWeight = if (frame.hasBall) 1.0f else 0.0f // Shots require possession
        val trustWeight = if (frame.trusted) 1.0f else 0.5f
        val fluidMultiplier = possessionWeight * trustWeight"""
t = rep(t, OLD_SHOT, NEW_SHOT, "SHOT-gate")

OLD_SHOT_AUTH = "val authority ="
NEW_SHOT_AUTH = "val authority = fluidMultiplier *"
t = rep(t, OLD_SHOT_AUTH, NEW_SHOT_AUTH, "SHOT-auth")
save(FILES["SHOT"], r, t)

# 3) ThreatPriorityContributor: Replace hard hasBall gate (defensive engines shouldn't die when we have ball)
r, t = load(FILES["THREAT"])
OLD_THREAT = "if (!frame.trusted || frame.hasBall) return null"
NEW_THREAT = """if (frame.confidence < 0.05f) return null
        val possessionWeight = if (!frame.hasBall) 1.0f else 0.3f // Defensive focus when opponent has ball
        val trustWeight = if (frame.trusted) 1.0f else 0.6f
        val fluidMultiplier = possessionWeight * trustWeight"""
t = rep(t, OLD_THREAT, NEW_THREAT, "THREAT-gate")

OLD_THREAT_AUTH = "authority = (decision.priority / 135f).coerceIn(0f, 1f),"
NEW_THREAT_AUTH = "authority = ((decision.priority / 135f) * fluidMultiplier).coerceIn(0f, 1f),"
t = rep(t, OLD_THREAT_AUTH, NEW_THREAT_AUTH, "THREAT-auth")
save(FILES["THREAT"], r, t)

# 4) PanicSaveContributor: Replace hard hasBall gate (goalkeeper behavior)
r, t = load(FILES["PANIC"])
OLD_PANIC = "if (!frame.trusted || frame.hasBall) return null"
NEW_PANIC = """if (frame.confidence < 0.05f) return null
        val possessionWeight = if (!frame.hasBall) 1.0f else 0.2f // Keeper focus when opponent attacks
        val trustWeight = if (frame.trusted) 1.0f else 0.7f
        val fluidMultiplier = possessionWeight * trustWeight"""
t = rep(t, OLD_PANIC, NEW_PANIC, "PANIC-gate")

OLD_PANIC_AUTH = "authority = ((decision.priority / 130f) * 0.95f).coerceIn(0f, 1f),"
NEW_PANIC_AUTH = "authority = (((decision.priority / 130f) * 0.95f) * fluidMultiplier).coerceIn(0f, 1f),"
t = rep(t, OLD_PANIC_AUTH, NEW_PANIC_AUTH, "PANIC-auth")
save(FILES["PANIC"], r, t)

print("=== V9 COMPLETE - run: ./gradlew :app:compileDebugKotlin ===")
