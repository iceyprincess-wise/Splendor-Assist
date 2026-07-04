from pathlib import Path
import re

rpc = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimePerformanceCoordinator.kt")
sa  = Path("app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt")

src = rpc.read_text()
ui  = sa.read_text()

# ----------------------------------------------------------
# Runtime 0-1000 mapping
# ----------------------------------------------------------
if "fun runtimeAuthority()" not in src:
    src = src.replace(
        "fun authority():Int = masterAuthority",
        """fun authority():Int = masterAuthority

    fun runtimeAuthority():Int =
        (masterAuthority * 10).coerceIn(0,1000)
"""
    )

# ----------------------------------------------------------
# Shared runtime values for all engines
# ----------------------------------------------------------
if "goalkeeperAuthority()" not in src:
    src = src.replace(
        "fun runtimeAuthority():Int =\n        (masterAuthority * 10).coerceIn(0,1000)",
        """fun runtimeAuthority():Int =
        (masterAuthority * 10).coerceIn(0,1000)

    fun goalkeeperAuthority():Int =
        runtimeAuthority()

    fun interceptionAuthority():Int =
        runtimeAuthority()

    fun smartAssistAuthority():Int =
        runtimeAuthority()
"""
    )

rpc.write_text(src)

# ----------------------------------------------------------
# Runtime label only (preserve pass/cross/shot sliders)
# ----------------------------------------------------------
ui = re.sub(
    r'"\$\{config\.authority\}%\s*\(\$\{config\.authority\*10\}\s*Runtime\)"',
    '"${config.authority}% (${RuntimePerformanceCoordinator.runtimeAuthority()} Runtime)"',
    ui
)

ui = re.sub(
    r'"\$\{value\.toInt\(\)\}%\s*\(\$\{value\.toInt\(\)\*10\}\s*Runtime\)"',
    '"${value.toInt()}% (${RuntimePerformanceCoordinator.runtimeAuthority()} Runtime)"',
    ui
)

sa.write_text(ui)

print("PHASE10 RUNTIME AUTHORITY SYNC PATCH COMPLETE")
