from pathlib import Path
import re

f = Path("app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt")
src = f.read_text()

MARKER = "// PHASE10_MASTER_AUTHORITY_MARKER"

if MARKER not in src:
    raise SystemExit("MARKER NOT FOUND")

# ------------------------------------------------------------------
# Remove malformed top-level authority insertion if present.
# ------------------------------------------------------------------
src = re.sub(
    r'\n\s*// PHASE10_MASTER_AUTHORITY_MARKER.*?(?=\n\s*override\s+fun\s+onResume|\n\s*private\s+fun\s+refreshRuntimeStatus|\n\s*private\s+fun\s+refreshEngineStatus)',
    '\n',
    src,
    flags=re.S
)

# ------------------------------------------------------------------
# Repair malformed refreshEngineStatus declaration.
# ------------------------------------------------------------------
src = re.sub(
    r'private\s+fun\s*\n',
    'private fun refreshEngineStatus() {\n',
    src,
    count=1
)

# Remove isolated orphan braces created by previous patch.
src = re.sub(
    r'^\s*\{\s*$',
    '',
    src,
    flags=re.M
)

src = re.sub(
    r'^\s*\}\s*$',
    lambda m: m.group(0),
    src
)

# ------------------------------------------------------------------
# Inject Master Authority wiring ONLY inside onCreate().
# ------------------------------------------------------------------
if "authoritySeek" not in src:
    anchor = "refreshEngineStatus()"

    block = r'''

        // PHASE10_MASTER_AUTHORITY_MARKER
        val authoritySlider =
            findViewById<com.google.android.material.slider.Slider>(R.id.authoritySeek)

        authoritySlider.value =
            repo.configuration().authority.toFloat()

        authoritySlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener

            repo.updateAuthority(value.toInt())
            RuntimePerformanceCoordinator.updateAuthority(value.toInt())
        }

'''

    src = src.replace(anchor, block + "\n        " + anchor, 1)

f.write_text(src)
print("PATCH COMPLETE")
