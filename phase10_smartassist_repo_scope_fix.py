from pathlib import Path
import re

f = Path("app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt")
src = f.read_text()

# Ensure a repository instance exists once inside onCreate()
if "val repo = SmartAssistRepository(this)" not in src:
    src = src.replace(
        "val config = SmartAssistRepository.configuration()",
        "val repo = SmartAssistRepository(this)\n        val config = SmartAssistRepository.configuration()",
        1
    )

# Remove accidental duplicate repository declarations later in the file
src = re.sub(
    r'\n\s*val repo = SmartAssistRepository\(this\)\s*\n\s*refreshEngineStatus\(\)',
    '\n        refreshEngineStatus()',
    src,
    count=1
)

# Ensure save block uses the onCreate() repository
src = src.replace("repo.updateEnabled(", "repo.updateEnabled(")
src = src.replace("repo.updatePanicMode(", "repo.updatePanicMode(")
src = src.replace("repo.updateThresholds(", "repo.updateThresholds(")

f.write_text(src)
print("PATCH COMPLETE")
