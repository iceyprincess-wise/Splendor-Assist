from pathlib import Path
import re

f = Path("app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt")
src = f.read_text()

# ----------------------------------------------------------
# Remove invalid startup reference inserted before bridge exists.
# ----------------------------------------------------------
src = re.sub(
    r'^\s*RuntimePerformanceCoordinator\.updateAuthority\(bridge\.state\.value\.configuration\.authority\)\s*\n?',
    '',
    src,
    flags=re.M
)

# ----------------------------------------------------------
# Ensure startup sync uses existing repository instance.
# ----------------------------------------------------------
anchor = "val repo = SmartAssistRepository(this)"

inject = """val repo = SmartAssistRepository(this)

        RuntimePerformanceCoordinator.updateAuthority(
            repo.configuration().authority
        )"""

src = src.replace(anchor, inject, 1)

f.write_text(src)
print("BRIDGE FIX COMPLETE")
