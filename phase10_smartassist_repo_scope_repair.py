from pathlib import Path
import re

p = Path("app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt")
s = p.read_text()

# Force a single repository declaration immediately after super/setContentView block.
anchor = "setContentView(R.layout.activity_smartassist_control_room)"
if "val repo = SmartAssistRepository(this)" not in s:
    s = s.replace(
        anchor,
        anchor + "\n\n        val repo = SmartAssistRepository(this)",
        1
    )

# Remove any repo declarations outside onCreate.
lines = s.splitlines()
out = []
seen = False
inside_oncreate = False

for line in lines:
    if "override fun onCreate" in line:
        inside_oncreate = True
    if inside_oncreate and line.strip() == "}":
        inside_oncreate = False

    if "val repo = SmartAssistRepository(this)" in line:
        if inside_oncreate and not seen:
            seen = True
            out.append(line)
        else:
            continue
    else:
        out.append(line)

p.write_text("\n".join(out))
print("PATCH COMPLETE")
