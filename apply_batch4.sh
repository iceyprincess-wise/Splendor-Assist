python3 - << 'EOF'
import sys

BASE = "/data/data/com.termux/files/home/projects/Splendor-Assist"
PKG  = f"{BASE}/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

# 1. FormationEngine: gate 7 → 4
old_f = """\
    if (players.size < 7) {
        return FormationResult(
            found = false,
            name = \"Unknown\",
            confidence = 0f
        )
    }

    val user =
        players.filter { it.isUserTeam }

    if (user.size < 7) {
        return FormationResult(
            found = false,
            name = \"Unknown\",
            confidence = 0f
        )
    }"""
new_f = """\
    if (players.size < 4) {
        return FormationResult(
            found = false,
            name = \"Unknown\",
            confidence = 0f
        )
    }

    val user =
        players.filter { it.isUserTeam }

    if (user.size < 4) {
        return FormationResult(
            found = false,
            name = \"Unknown\",
            confidence = 0f
        )
    }"""
path_f = f"{PKG}/FormationEngine.kt"
with open(path_f) as f: txt = f.read()
if old_f not in txt: print("FAIL FormationEngine"); sys.exit(1)
with open(path_f,"w") as f: f.write(txt.replace(old_f, new_f, 1))
print("FormationEngine: OK")

# 2. FrameScanner: default noise 2 → 0
old_fs = "        adaptiveNoiseVariance: Int = 2, // Dynamic micro-variance default"
new_fs = "        adaptiveNoiseVariance: Int = 0, // 0 = no noise; ±2px was fragmenting ball blobs before BFS"
path_fs = f"{PKG}/FrameScanner.kt"
with open(path_fs) as f: txt2 = f.read()
if old_fs not in txt2: print("FAIL FrameScanner"); sys.exit(1)
with open(path_fs,"w") as f: f.write(txt2.replace(old_fs, new_fs, 1))
print("FrameScanner: OK")

# 3. GameplayDecisionEngine: stale lock 120ms → 34ms
old_gd = "            } else if (now - previousTimestamp < 120L && previousConfidence >= confidence) {"
new_gd = "            } else if (now - previousTimestamp < 34L && previousConfidence >= confidence) {"
path_gd = f"{PKG}/GameplayDecisionEngine.kt"
with open(path_gd) as f: txt3 = f.read()
if old_gd not in txt3: print("FAIL GameplayDecisionEngine"); sys.exit(1)
with open(path_gd,"w") as f: f.write(txt3.replace(old_gd, new_gd, 1))
print("GameplayDecisionEngine: OK")
EOF
