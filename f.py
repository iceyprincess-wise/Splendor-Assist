#!/usr/bin/env python3
import os, sys

B = "/data/data/com.termux/files/home/projects/Splendor-Assist/app/src/main/java/com/assistant"
FILES = {
    "OV": B + "/OverlayService.kt",
    "MEM": B + "/adapter/memory/MemoryCaptureGateEngine.kt",
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

print("=== SPLDOR-ASSIST V11 (SYNTAX & GATE FIX) ===")

# 1) OverlayService: Fix broken syntax from V10 partial application
r, t = load(FILES["OV"])
OLD_OV_BROKEN = """                {
                    val state = com.assistant.adapter.smartassist.VisionCore.process(normalized)
                    com.assistant.BoosterIgnition.ensureIgnited(this)
                    com.assistant.AppContributorRegistration.ensureRegistered()
                    com.assistant.adapter.smartassist.RuntimeCoordinator.reportCaptureReady()
                    val frame = com.assistant.adapter.smartassist.FrameAssembler.assemble()
                    com.assistant.adapter.smartassist.RuntimeDecisionLoop.onFrame(frame)
                    com.assistant.adapter.smartassist.GameStateBuilder.update(state)
                    com.assistant.overlay.interceptor.OmnipotentGoalkeeperEngine.scanFrameForOpponentAnimation(scanBuffer, image.width, image.height)
                } else {
                    try {
                        val lightSamples = com.assistant.adapter.smartassist.FrameScanner.scan(normalized)
                        val lightBlobs = com.assistant.adapter.smartassist.ConnectedComponentEngine.extract(lightSamples)
                        val filteredBlobs = com.assistant.adapter.smartassist.NoiseFilter.filter(lightBlobs)
                        val ballCandidate = com.assistant.adapter.smartassist.BallCandidateEngine.select(filteredBlobs)
                        val ball = com.assistant.adapter.smartassist.BallDetector.detect(ballCandidate)
                        com.assistant.adapter.smartassist.BallTelemetryBridge.publish(ball)
                    } catch (_: Throwable) {}
                }"""
NEW_OV_FIXED = """                // V10 LATENCY FIX: Full processing runs every frame. Light path removed.
                val state = com.assistant.adapter.smartassist.VisionCore.process(normalized)
                com.assistant.BoosterIgnition.ensureIgnited(this)
                com.assistant.AppContributorRegistration.ensureRegistered()
                com.assistant.adapter.smartassist.RuntimeCoordinator.reportCaptureReady()
                val frame = com.assistant.adapter.smartassist.FrameAssembler.assemble()
                com.assistant.adapter.smartassist.RuntimeDecisionLoop.onFrame(frame)
                com.assistant.adapter.smartassist.GameStateBuilder.update(state)
                com.assistant.overlay.interceptor.OmnipotentGoalkeeperEngine.scanFrameForOpponentAnimation(scanBuffer, image.width, image.height)"""
t = rep(t, OLD_OV_BROKEN, NEW_OV_FIXED, "OV-syntax-fix")
save(FILES["OV"], r, t)

# 2) MemoryCaptureGateEngine: Cap interval at 33ms for gameplay freshness
r, t = load(FILES["MEM"])
OLD_MEM = """    fun recommendedIntervalMs(): Long = when (captureThrottle) {
        3    -> 100L
        2    -> 66L
        1    -> 50L
        else -> 33L
    }"""
NEW_MEM = """    // V10 LATENCY FIX: Cap interval at 33ms for gameplay freshness.
    // Memory pressure must not starve the decision loop of fresh frames.
    fun recommendedIntervalMs(): Long = 33L"""
t = rep(t, OLD_MEM, NEW_MEM, "MEM-cap")
save(FILES["MEM"], r, t)

print("=== V11 COMPLETE - run: ./gradlew :app:compileDebugKotlin ===")
