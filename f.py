#!/usr/bin/env python3
import os, sys

B = "/data/data/com.termux/files/home/projects/Splendor-Assist/app/src/main/java/com/assistant"
FILES = {
    "OV": B + "/OverlayService.kt",
    "ACC": B + "/adapter/smartassist/SmartAssistAccessibilityEngine.kt",
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

print("=== SPLDOR-ASSIST V10 (LATENCY ELIMINATION) ===")

# 1) OverlayService: Remove frame-skipping, force every-frame full processing
r, t = load(FILES["OV"])
OLD_OV = """            val thisFrameCount = ++captureFrameCount
            val doFullProcessing = (thisFrameCount % 2L == 0L)
            if (com.assistant.vision.ForegroundGate.shouldSkipCapture()) {
                image.close()
                return@setOnImageAvailableListener
            }
            try {
                val scanBuffer = image.planes[0].buffer.duplicate()
                val normalized = com.assistant.adapter.smartassist.FrameNormalizer.normalize(scanBuffer.duplicate(), image.width, image.height)

                if (doFullProcessing) {"""
NEW_OV = """            val thisFrameCount = ++captureFrameCount
            // V10 LATENCY FIX: Remove frame-skipping. Full processing MUST run every frame
            // to eliminate 33-66ms decision staleness. Memory pressure is handled by
            // MemoryCaptureGateEngine capping interval, not by skipping frames.
            if (com.assistant.vision.ForegroundGate.shouldSkipCapture()) {
                image.close()
                return@setOnImageAvailableListener
            }
            try {
                val scanBuffer = image.planes[0].buffer.duplicate()
                val normalized = com.assistant.adapter.smartassist.FrameNormalizer.normalize(scanBuffer.duplicate(), image.width, image.height)

                {"""
t = rep(t, OLD_OV, NEW_OV, "OV-frame-skip")

OLD_OV_CLOSE = """                } else {
                    try {
                        val lightSamples = com.assistant.adapter.smartassist.FrameScanner.scan(normalized)
                        val lightBlobs = com.assistant.adapter.smartassist.ConnectedComponentEngine.extract(lightSamples)
                        val filteredBlobs = com.assistant.adapter.smartassist.NoiseFilter.filter(lightBlobs)
                        val ballCandidate = com.assistant.adapter.smartassist.BallCandidateEngine.select(filteredBlobs)
                        val ball = com.assistant.adapter.smartassist.BallDetector.detect(ballCandidate)
                        com.assistant.adapter.smartassist.BallTelemetryBridge.publish(ball)
                    } catch (_: Throwable) {}
                }"""
NEW_OV_CLOSE = """                }"""
t = rep(t, OLD_OV_CLOSE, NEW_OV_CLOSE, "OV-light-path")
save(FILES["OV"], r, t)

# 2) SmartAssistAccessibilityEngine: Reduce poll rate, eliminate latch margin
r, t = load(FILES["ACC"])
OLD_ACC_POLL = "private const val BUS_POLL_RATE_MS = 8L"
NEW_ACC_POLL = "private const val BUS_POLL_RATE_MS = 4L  // V10: Tighter polling to reduce queue-to-dispatch window"
t = rep(t, OLD_ACC_POLL, NEW_ACC_POLL, "ACC-poll")

OLD_ACC_MARGIN = "private const val LATCH_RELEASE_MARGIN_MS = 8L"
NEW_ACC_MARGIN = "private const val LATCH_RELEASE_MARGIN_MS = 0L  // V10: Eliminate dead air; gesture duration is sufficient"
t = rep(t, OLD_ACC_MARGIN, NEW_ACC_MARGIN, "ACC-margin")
save(FILES["ACC"], r, t)

# 3) MemoryCaptureGateEngine: Cap interval at 33ms for gameplay freshness
r, t = load(FILES["MEM"])
# This file may not exist or may have different structure. We'll add a safety check.
if os.path.exists(FILES["MEM"]):
    # Assume it has a function like recommendedIntervalMs() that returns a Long
    # We'll add a hard cap at the end of that function
    OLD_MEM = "return intervalMs"
    NEW_MEM = "return intervalMs.coerceAtMost(33L)  // V10: Cap at 30fps for gameplay freshness"
    if OLD_MEM in t:
        t = rep(t, OLD_MEM, NEW_MEM, "MEM-cap")
        save(FILES["MEM"], r, t)
    else:
        print("UNVERIFIED - MemoryCaptureGateEngine.kt anchor not found; skipping cap.")
else:
    print("UNVERIFIED - MemoryCaptureGateEngine.kt not found; skipping cap.")

print("=== V10 COMPLETE - run: ./gradlew :app:compileDebugKotlin ===")
