from pathlib import Path
import re

ROOT = Path.home() / "projects" / "Splendor-Assist"

FILES = [
    ROOT / "app/src/main/java/com/assistant/controlroom/ui/GoalkeeperControlRoomActivity.kt",
    ROOT / "app/src/main/java/com/assistant/controlroom/ui/InterceptionControlRoomActivity.kt",
]

for f in FILES:
    s = f.read_text()

    # Repair malformed replacement:
    # RuntimePerformanceCoordinator.updateAuthority(...).authority
    s = re.sub(
        r'RuntimePerformanceCoordinator\.updateAuthority\s*\(\s*SmartAssistRepository\.configuration\(\)\.authority\s*\)\.authority',
        'RuntimePerformanceCoordinator.updateAuthority(SmartAssistRepository.configuration().authority)',
        s
    )

    # Remove any accidental duplicated ".authority"
    s = re.sub(
        r'\)\.authority\b',
        ')',
        s
    )

    f.write_text(s)

print("SYNTAX FIX COMPLETE")
