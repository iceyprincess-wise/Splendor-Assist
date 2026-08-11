python3 - << 'EOF'
import sys
BASE = "/data/data/com.termux/files/home/projects/Splendor-Assist"
PKG  = f"{BASE}/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

# F1: FormationEngine gate 7→4
old_f1 = """\
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
new_f1 = """\
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
p = f"{PKG}/FormationEngine.kt"
with open(p) as f: t = f.read()
if old_f1 not in t: print("FAIL FormationEngine"); sys.exit(1)
with open(p,"w") as f: f.write(t.replace(old_f1, new_f1, 1))
print("FormationEngine: OK")

# F2: FrameScanner noise 2→0
old_f2 = "        adaptiveNoiseVariance: Int = 2, // Dynamic micro-variance default"
new_f2 = "        adaptiveNoiseVariance: Int = 0, // 0 = no scatter; ±2px was fragmenting ball blobs before BFS"
p2 = f"{PKG}/FrameScanner.kt"
with open(p2) as f: t2 = f.read()
if old_f2 not in t2: print("FAIL FrameScanner"); sys.exit(1)
with open(p2,"w") as f: f.write(t2.replace(old_f2, new_f2, 1))
print("FrameScanner: OK")

# G: GameplayDecisionEngine 120ms→34ms
old_g = "            } else if (now - previousTimestamp < 120L && previousConfidence >= confidence) {"
new_g = "            } else if (now - previousTimestamp < 34L && previousConfidence >= confidence) {"
p3 = f"{PKG}/GameplayDecisionEngine.kt"
with open(p3) as f: t3 = f.read()
if old_g not in t3: print("FAIL GameplayDecisionEngine"); sys.exit(1)
with open(p3,"w") as f: f.write(t3.replace(old_g, new_g, 1))
print("GameplayDecisionEngine: OK")

# H: HybridResponseCompensationEngine — clamp compensated coords to screen
old_h = """\
        val compensatedX=
            endX + dx*predictiveFactor

        val compensatedY=
            endY + dy*predictiveFactor"""
new_h = """\
        val compensatedX =
            (endX + dx * predictiveFactor).coerceIn(0f, 1650f)

        val compensatedY =
            (endY + dy * predictiveFactor).coerceIn(0f, 720f)"""
p4 = f"{PKG}/HybridResponseCompensationEngine.kt"
with open(p4) as f: t4 = f.read()
if old_h not in t4: print("FAIL HybridResponseCompensationEngine"); sys.exit(1)
with open(p4,"w") as f: f.write(t4.replace(old_h, new_h, 1))
print("HybridResponseCompensationEngine: OK")
EOF
