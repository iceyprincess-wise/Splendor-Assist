from pathlib import Path
import re

f = Path("app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt")
t = f.read_text(encoding="utf-8")

# ------------------------------------------------------------------
# Recreate the repository only inside the method that uses it.
# The previous cleanup removed its declaration but left repo.* calls.
# ------------------------------------------------------------------

m = re.search(
    r'(fun\s+refreshRuntimeStatus\s*\(\)\s*\{)',
    t
)

if not m:
    raise SystemExit("refreshRuntimeStatus() not found")

start = m.end()

if "val repo = SmartAssistRepository(this)" not in t[start:start+500]:
    t = (
        t[:start] +
        "\n        val repo = SmartAssistRepository(this)\n" +
        t[start:]
    )

# ------------------------------------------------------------------
# Remove any duplicate consecutive declarations.
# ------------------------------------------------------------------

t = re.sub(
    r'(val repo = SmartAssistRepository\(this\)\n)(\s*val repo = SmartAssistRepository\(this\)\n)+',
    r'\1',
    t
)

# ------------------------------------------------------------------
# Marker
# ------------------------------------------------------------------

if "PHASE10_SMARTASSIST_REPO_RESTORE_FIX_MARKER" not in t:
    t = t.replace(
        "PHASE10_SMARTASSIST_PERSISTENCE_FINAL_MARKER",
        "PHASE10_SMARTASSIST_REPO_RESTORE_FIX_MARKER\n    // PHASE10_SMARTASSIST_PERSISTENCE_FINAL_MARKER"
    )

f.write_text(t, encoding="utf-8")
print("PATCH COMPLETE")
