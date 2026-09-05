import sys
import os

base_dir = "/tmp/repo"
if not os.path.exists(base_dir):
    base_dir = os.path.expanduser("~/projects/Splendor-Assist")

pd_path = os.path.join(base_dir, "app/src/main/java/com/assistant/adapter/smartassist/PlayerDetector.kt")
rdl_path = os.path.join(base_dir, "app/src/main/java/com/assistant/adapter/smartassist/RuntimeDecisionLoop.kt")

if not os.path.exists(pd_path) or not os.path.exists(rdl_path):
    print(f"ERROR: Repository not found at {base_dir}. Ensure it is cloned.")
    sys.exit(1)

# 1. Patch PlayerDetector.kt
try:
    with open(pd_path, "r", encoding="utf-8") as f:
        pd_content = f.read()

    old_nms = """        // NMS: suppress lower-confidence detection if it overlaps a better one
        val kept = ArrayList<PlayerDetection>(raw.size)
        val suppressed = BooleanArray(raw.size)
        for (i in raw.indices) {
            if (suppressed[i]) continue
            kept.add(raw[i])
            for (j in i + 1 until raw.size) {
                if (suppressed[j]) continue
                val dx = abs(raw[i].x - raw[j].x)
                val dy = abs(raw[i].y - raw[j].y)
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                // suppress j if within ~40px of a better detection
                if (dist < 40f) suppressed[j] = true
            }
        }

        val aggregateConfidence = if (kept.isEmpty()) 0f else
            kept.map { it.confidence }.average().toFloat().coerceIn(0f, 1f)"""

    new_nms = """        // NMS: suppress lower-confidence detection if it overlaps a better one
        val kept = ArrayList<PlayerDetection>(raw.size)
        val suppressed = BooleanArray(raw.size)
        var sumConf = 0f
        for (i in raw.indices) {
            if (suppressed[i]) continue
            val pi = raw[i]
            kept.add(pi)
            sumConf += pi.confidence
            for (j in i + 1 until raw.size) {
                if (suppressed[j]) continue
                val dx = abs(pi.x - raw[j].x)
                val dy = abs(pi.y - raw[j].y)
                val distSq = dx * dx + dy * dy
                // suppress j if within ~40px of a better detection (40^2 = 1600)
                if (distSq < 1600f) suppressed[j] = true
            }
        }

        val aggregateConfidence = if (kept.isEmpty()) 0f else (sumConf / kept.size).coerceIn(0f, 1f)"""

    if old_nms in pd_content:
        pd_content = pd_content.replace(old_nms, new_nms, 1)
        with open(pd_path, "w", encoding="utf-8") as f:
            f.write(pd_content)
        print("SUCCESS: PlayerDetector.kt patched with squared-distance NMS and zero-alloc aggregate confidence.")
    else:
        print("ERROR: Could not find PlayerDetector NMS anchor block.")
        sys.exit(1)
except Exception as e:
    print(f"Error processing PlayerDetector.kt: {e}")
    sys.exit(1)

# 2. Patch RuntimeDecisionLoop.kt
try:
    with open(rdl_path, "r", encoding="utf-8") as f:
        rdl_content = f.read()

    old_arb = """        val contributions = GameplayEngineRegistry.collect(frame)
        val netHold = AdapterSignalBus.netIsHold
        val best: EngineContribution? =
            contributions
                .filter { c -> if (netHold) c.actionClass == ActionClass.MOVE || c.actionClass == ActionClass.DEFEND else true }
                .maxByOrNull { it.weight * classScale(it.actionClass) }"""

    new_arb = """        val contributions = GameplayEngineRegistry.collect(frame)
        val netHold = AdapterSignalBus.netIsHold
        
        // Zero-alloc manual loop for filtering and max arbitration
        var best: EngineContribution? = null
        var bestScore = -1f
        for (c in contributions) {
            if (netHold && c.actionClass != ActionClass.MOVE && c.actionClass != ActionClass.DEFEND) continue
            val score = c.weight * classScale(c.actionClass)
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }"""

    if old_arb in rdl_content:
        rdl_content = rdl_content.replace(old_arb, new_arb, 1)
        with open(rdl_path, "w", encoding="utf-8") as f:
            f.write(rdl_content)
        print("SUCCESS: RuntimeDecisionLoop.kt patched with zero-alloc manual arbitration loop.")
    else:
        print("ERROR: Could not find RuntimeDecisionLoop arbitration anchor block.")
        sys.exit(1)
except Exception as e:
    print(f"Error processing RuntimeDecisionLoop.kt: {e}")
    sys.exit(1)

print("\nNext steps: Run './gradlew build' to verify compilation, then push to main.")
