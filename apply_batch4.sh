python3 - << 'EOF'
import sys
BASE = "/data/data/com.termux/files/home/projects/Splendor-Assist"
PKG  = f"{BASE}/adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"

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
