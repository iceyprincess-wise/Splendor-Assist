#!/usr/bin/env python3
import os, sys

FPS = "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/fps"

def ok(msg):   print("OK     " + msg)
def fail(msg): print("FAIL   " + msg); sys.exit(1)

def patch(path, old, new):
    with open(path) as f: content = f.read()
    if old not in content: fail(path.split("/")[-1] + " pattern not found")
    with open(path, "w") as f: f.write(content.replace(old, new, 1))
    ok("PATCH " + path.split("/")[-1])

# ─────────────────────────────────────────────────────────────────────────────
# FIX 1: LatencyDefeatingInputEngine.kt
# calibratedEndX/Y not clamped after 1.12x ping scaling -> off-screen gesture
# ─────────────────────────────────────────────────────────────────────────────
patch(
    FPS + "/LatencyDefeatingInputEngine.kt",
    (
        "        val calibratedEndX = if (baseDistance > 0) {\n"
        "            (humanizedStartX + (humanizedEndX - humanizedStartX) * dynamicScaleFactor)\n"
        "        } else {\n"
        "            humanizedEndX\n"
        "        }\n"
        "        val calibratedEndY = if (baseDistance > 0) {\n"
        "            (humanizedStartY + (humanizedEndY - humanizedStartY) * dynamicScaleFactor)\n"
        "        } else {\n"
        "            humanizedEndY\n"
        "        }"
    ),
    (
        "        // FIX: clamp to screen bounds after scaling — unclipped overshoot injects off-screen\n"
        "        val calibratedEndX = if (baseDistance > 0) {\n"
        "            (humanizedStartX + (humanizedEndX - humanizedStartX) * dynamicScaleFactor)\n"
        "                .coerceIn(0f, 1650f)\n"
        "        } else {\n"
        "            humanizedEndX.coerceIn(0f, 1650f)\n"
        "        }\n"
        "        val calibratedEndY = if (baseDistance > 0) {\n"
        "            (humanizedStartY + (humanizedEndY - humanizedStartY) * dynamicScaleFactor)\n"
        "                .coerceIn(0f, 720f)\n"
        "        } else {\n"
        "            humanizedEndY.coerceIn(0f, 720f)\n"
        "        }"
    )
)

# ─────────────────────────────────────────────────────────────────────────────
# FIX 2: NativePipelineCache.kt
# computeDirectInterpolation has NO bounds check on startIndex/endIndex
# -> ArrayIndexOutOfBoundsException crash on any invalid call
# ─────────────────────────────────────────────────────────────────────────────
patch(
    FPS + "/NativePipelineCache.kt",
    (
        "    fun computeDirectInterpolation(\n"
        "        startIndex:Int,\n"
        "        endIndex:Int,\n"
        "        bias:Float\n"
        "    ):Long {\n"
        "\n"
        "        val dx=\n"
        "            vectorBufferX[endIndex]-\n"
        "            vectorBufferX[startIndex]\n"
        "\n"
        "        val dy=\n"
        "            vectorBufferY[endIndex]-\n"
        "            vectorBufferY[startIndex]"
    ),
    (
        "    fun computeDirectInterpolation(\n"
        "        startIndex:Int,\n"
        "        endIndex:Int,\n"
        "        bias:Float\n"
        "    ):Long {\n"
        "        // FIX: guard both indices — unchecked access crashes on any out-of-range call\n"
        "        if (startIndex !in 0..63 || endIndex !in 0..63) return 0L\n"
        "\n"
        "        val dx=\n"
        "            vectorBufferX[endIndex]-\n"
        "            vectorBufferX[startIndex]\n"
        "\n"
        "        val dy=\n"
        "            vectorBufferY[endIndex]-\n"
        "            vectorBufferY[startIndex]"
    )
)

print("")
print("=" * 50)
print("FPS fixes done:")
print("  PATCH LatencyDefeatingInputEngine.kt  calibratedEnd clamped to screen")
print("  PATCH NativePipelineCache.kt          bounds guard added")
print("=" * 50)
print("")
print("Run:")
print("  ./gradlew :adapter_smartassist:compileDebugKotlin 2>&1 | tail -15")
print("  git add -A")
print('  git commit -m "fix(fps): clamp gesture coords; NativePipelineCache bounds guard"')
print("  git push")
