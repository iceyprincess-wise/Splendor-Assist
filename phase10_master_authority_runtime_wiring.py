from pathlib import Path
import re

ROOT = Path(".")

bridge = ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/SmartAssistControlRoomBridge.kt"
activity = ROOT / "app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt"

# --------------------------------------------------------------------
# Bridge wiring
# --------------------------------------------------------------------
if bridge.exists():
    t = bridge.read_text()

    if "updateAuthority(" not in t:
        marker = "fun updateThresholds(pass: Int, shot: Int, cross: Int)"
        if marker in t:
            t = t.replace(
                marker,
                """
    fun updateAuthority(authority:Int){
        repository.updateAuthority(authority)
        RuntimePerformanceCoordinator.updateAuthority(authority)
    }

""" + marker
            )

    if "import com.assistant.adapter.smartassist.RuntimePerformanceCoordinator" not in t:
        t = t.replace(
            "import com.assistant.adapter.smartassist.SmartAssistState",
            "import com.assistant.adapter.smartassist.SmartAssistState\n"
            "import com.assistant.adapter.smartassist.RuntimePerformanceCoordinator"
        )

    bridge.write_text(t)

# --------------------------------------------------------------------
# Activity startup synchronization
# --------------------------------------------------------------------
if activity.exists():
    t = activity.read_text()

    sync = (
        "RuntimePerformanceCoordinator.updateAuthority("
        "bridge.state.value.configuration.authority)"
    )

    if sync not in t:
        t = re.sub(
            r"(super\.onCreate\(savedInstanceState\))",
            r"\1\n\n        " + sync,
            t,
            count=1
        )

    if "import com.assistant.adapter.smartassist.RuntimePerformanceCoordinator" not in t:
        pkg = re.search(r"^(package .+?\n)", t, re.M)
        if pkg:
            pos = pkg.end()
            t = (
                t[:pos]
                + "\nimport com.assistant.adapter.smartassist.RuntimePerformanceCoordinator"
                + t[pos:]
            )

    activity.write_text(t)

print("MASTER AUTHORITY RUNTIME WIRING COMPLETE")
