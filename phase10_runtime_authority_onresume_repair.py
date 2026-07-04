from pathlib import Path
import re

ROOT = Path.home() / "projects" / "Splendor-Assist"

FILES = [
    ROOT / "app/src/main/java/com/assistant/controlroom/ui/GoalkeeperControlRoomActivity.kt",
    ROOT / "app/src/main/java/com/assistant/controlroom/ui/InterceptionControlRoomActivity.kt",
]

for f in FILES:
    s = f.read_text()

    # Completely rebuild onResume() to eliminate malformed statements.
    s = re.sub(
        r'override\s+fun\s+onResume\(\)\s*\{.*?\n\s*\}',
        '''override fun onResume() {
        super.onResume()

        RuntimePerformanceCoordinator.updateAuthority(
            SmartAssistRepository.configuration().authority
        )
    }''',
        s,
        flags=re.S,
        count=1
    )

    f.write_text(s)

print("ONRESUME REPAIRED")
