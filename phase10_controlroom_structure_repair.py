from pathlib import Path
import re

FILES = [
    "app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt",
    "app/src/main/java/com/assistant/controlroom/ui/GoalkeeperControlRoomActivity.kt",
    "app/src/main/java/com/assistant/controlroom/ui/InterceptionControlRoomActivity.kt",
    "app/src/main/java/com/assistant/controlroom/ui/FutureRoomsActivity.kt",
    "app/src/main/java/com/assistant/overlay/ui/AnalyticsTheaterActivity.kt",
]

def extract_tail(text):
    m = re.search(r'(\n\s*// PHASE10_(?:CONTROLROOM|ANALYTICS)_RUNTIME_MARKER.*)', text, re.S)
    return m.group(1) if m else ""

for f in FILES:
    p = Path(f)
    s = p.read_text()

    tail = extract_tail(s)
    if not tail:
        continue

    # remove malformed appended block
    s = s[:s.index(tail)]

    # strip final class brace
    m = re.search(r'\n\}\s*$', s)
    if not m:
        print("No class terminator:", f)
        continue

    s = s[:m.start()]

    # clean malformed declarations
    tail = re.sub(
        r'private fun refreshRuntimeStatus\(\)\s*refreshEngineStatus\(\)\s*\{',
        '''private fun refreshRuntimeStatus() {
        refreshEngineStatus()''',
        tail,
        flags=re.S
    )

    # remove leading blank lines
    tail = tail.lstrip("\n")

    fixed = s + "\n\n" + tail.rstrip() + "\n}\n"

    p.write_text(fixed)
    print("FIXED", f)

print("STRUCTURE REPAIR COMPLETE")
