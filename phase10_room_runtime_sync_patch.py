from pathlib import Path
import re

ROOT = Path(".")

rpc = ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimePerformanceCoordinator.kt"
gk = ROOT / "app/src/main/java/com/assistant/controlroom/ui/GoalkeeperControlRoomActivity.kt"
it = ROOT / "app/src/main/java/com/assistant/controlroom/ui/InterceptionControlRoomActivity.kt"

# ------------------------------------------------------------------
# RuntimePerformanceCoordinator
# ------------------------------------------------------------------

s = rpc.read_text()

if "fun runtimeAuthority():Int" in s and "* 10" not in s:
    s = re.sub(
        r'fun\s+runtimeAuthority\(\):Int\s*=\s*.*',
        'fun runtimeAuthority():Int = masterAuthority.coerceIn(0,100) * 10',
        s,
        count=1
    )

rpc.write_text(s)

# ------------------------------------------------------------------
# Goalkeeper runtime sync
# ------------------------------------------------------------------

if gk.exists():
    s = gk.read_text()

    if "RuntimePerformanceCoordinator.updateAuthority" not in s:
        s = s.replace(
            "super.onCreate(savedInstanceState)",
            """super.onCreate(savedInstanceState)

        RuntimePerformanceCoordinator.updateAuthority(
            SmartAssistRepository.configuration().authority
        )
"""
        )

    if "override fun onResume()" not in s:
        s += """

    override fun onResume() {
        super.onResume()
        RuntimePerformanceCoordinator.updateAuthority(
            SmartAssistRepository.configuration().authority
        )
    }
"""

    gk.write_text(s)

# ------------------------------------------------------------------
# Interception runtime sync
# ------------------------------------------------------------------

if it.exists():
    s = it.read_text()

    if "RuntimePerformanceCoordinator.updateAuthority" not in s:
        s = s.replace(
            "super.onCreate(savedInstanceState)",
            """super.onCreate(savedInstanceState)

        RuntimePerformanceCoordinator.updateAuthority(
            SmartAssistRepository.configuration().authority
        )
"""
        )

    if "override fun onResume()" not in s:
        s += """

    override fun onResume() {
        super.onResume()
        RuntimePerformanceCoordinator.updateAuthority(
            SmartAssistRepository.configuration().authority
        )
    }
"""

    it.write_text(s)

print("PHASE10 RUNTIME SYNC PATCH COMPLETE")
