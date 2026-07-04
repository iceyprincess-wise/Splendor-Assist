from pathlib import Path

f = Path("app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt")

src = f.read_text()

src = src.replace(
    "repo.configuration().authority",
    "SmartAssistRepository.configuration().authority"
)

f.write_text(src)

print("STATIC CONFIGURATION FIX COMPLETE")
