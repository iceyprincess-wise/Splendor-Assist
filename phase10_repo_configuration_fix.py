from pathlib import Path
import re

activity = Path("app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt")
repo = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/SmartAssistRepository.kt")

a = activity.read_text()
r = repo.read_text()

# ---------------------------------------------------------
# Determine the actual API exposed by SmartAssistRepository.
# ---------------------------------------------------------
api = None

if "fun configuration()" in r:
    api = "repo.configuration()"
elif "companion object" in r and "fun configuration()" in r:
    api = "SmartAssistRepository.configuration()"
elif "fun getCurrentState()" in r:
    api = "repo.getCurrentState().configuration"
elif "_state.value.configuration" in r:
    api = "repo.getCurrentState().configuration"

if api is None:
    raise SystemExit(
        "Unable to determine repository API. "
        "Do not guess architecture."
    )

# ---------------------------------------------------------
# Replace invalid call only.
# ---------------------------------------------------------
a = a.replace(
    "repo.configuration()",
    api
)

activity.write_text(a)

print("REPOSITORY API =", api)
print("PATCH COMPLETE")
