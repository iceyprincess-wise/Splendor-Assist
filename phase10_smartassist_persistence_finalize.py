from pathlib import Path
import re

f = Path("app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt")
t = f.read_text(encoding="utf-8")

# -------------------------------------------------------
# Remove unused duplicate repository declarations
# -------------------------------------------------------
t = re.sub(
    r'^\s*val repo = SmartAssistRepository\(this\)\s*$',
    '',
    t,
    flags=re.M
)

# -------------------------------------------------------
# Remove shadowed repository declarations inside methods
# -------------------------------------------------------
t = re.sub(
    r'^\s*val repo = SmartAssistRepository\(this\)\s*$',
    '',
    t,
    flags=re.M
)

# -------------------------------------------------------
# Clean excessive blank lines
# -------------------------------------------------------
t = re.sub(r'\n{3,}', '\n\n', t)

# -------------------------------------------------------
# Marker
# -------------------------------------------------------
if "PHASE10_SMARTASSIST_PERSISTENCE_FINAL_MARKER" not in t:
    t = t.replace(
        "// PHASE10_SMARTASSIST_PERSISTENCE_MARKER",
        "// PHASE10_SMARTASSIST_PERSISTENCE_FINAL_MARKER\n    // PHASE10_SMARTASSIST_PERSISTENCE_MARKER"
    )

f.write_text(t, encoding="utf-8")
print("SMARTASSIST FINALIZE PATCH COMPLETE")
