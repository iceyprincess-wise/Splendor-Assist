python3 - << 'EOF'
import sys

BASE = "/data/data/com.termux/files/home/projects/Splendor-Assist"
PKG  = f"{BASE}/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

# ── BallDetector: wire coast window to return last known position ──
old_bd = """\
        if (candidate == null || candidate.score < MIN_CANDIDATE_CONFIDENCE) {
            lostFrames++
            if (lostFrames > COAST_FRAMES) {
                initialized = false
                lastBallX = 0f
                lastBallY = 0f
                lastRadius = 0f
                lastConfidence = 0f
            }
            return BallDetectionResult(
                detected = false,
                x = 0f,
                y = 0f,
                radius = 0f,
                confidence = 0f,
                searchPixels = 0,
                matchedPixels = 0
            )
        }"""
new_bd = """\
        if (candidate == null || candidate.score < MIN_CANDIDATE_CONFIDENCE) {
            lostFrames++
            if (lostFrames > COAST_FRAMES) {
                initialized = false
                lastBallX = 0f
                lastBallY = 0f
                lastRadius = 0f
                lastConfidence = 0f
                return BallDetectionResult(
                    detected = false,
                    x = 0f, y = 0f, radius = 0f, confidence = 0f,
                    searchPixels = 0, matchedPixels = 0
                )
            }
            if (initialized) {
                val coastFraction = 1f - (lostFrames.toFloat() / COAST_FRAMES)
                val coastConfidence = (lastConfidence * coastFraction).coerceIn(0f, 1f)
                return BallDetectionResult(
                    detected = true,
                    x = lastBallX, y = lastBallY, radius = lastRadius,
                    confidence = coastConfidence,
                    searchPixels = 0, matchedPixels = 0
                )
            }
            return BallDetectionResult(
                detected = false,
                x = 0f, y = 0f, radius = 0f, confidence = 0f,
                searchPixels = 0, matchedPixels = 0
            )
        }"""

path_bd = f"{PKG}/BallDetector.kt"
with open(path_bd) as f: txt = f.read()
if old_bd not in txt:
    print("FAIL BallDetector: old text not found"); sys.exit(1)
txt = txt.replace(old_bd, new_bd, 1)
with open(path_bd, "w") as f: f.write(txt)
print("BallDetector: OK")

# ── CrossPrecisionEngine: confidence [2..8] → [0.60..1.0] ──
old_cp = """\
        return CrossPrecisionResult(
            crossX=x,
            crossY=y-(40f*boost),
            confidence=2.0f+(boost*6.0f)
        )"""
new_cp = """\
        return CrossPrecisionResult(
            crossX=x,
            crossY=y-(40f*boost),
            confidence=(0.60f+(boost*0.40f)).coerceIn(0f,1f)
        )"""

path_cp = f"{PKG}/CrossPrecisionEngine.kt"
with open(path_cp) as f: txt2 = f.read()
if old_cp not in txt2:
    print("FAIL CrossPrecisionEngine: old text not found"); sys.exit(1)
txt2 = txt2.replace(old_cp, new_cp, 1)
with open(path_cp, "w") as f: f.write(txt2)
print("CrossPrecisionEngine: OK")
EOF
