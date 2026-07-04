from pathlib import Path
import re

ROOT = Path.home() / "projects" / "Splendor-Assist"

gk = ROOT / "app/src/main/java/com/assistant/controlroom/ui/GoalkeeperControlRoomActivity.kt"
itc = ROOT / "app/src/main/java/com/assistant/controlroom/ui/InterceptionControlRoomActivity.kt"
rpc = ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimePerformanceCoordinator.kt"

# -------------------------------------------------------
# Goalkeeper / Interception:
# keep runtime authority synchronized from Smart Assist
# -------------------------------------------------------

for f in (gk, itc):
    s = f.read_text()

    if "import com.assistant.adapter.smartassist.SmartAssistRepository" not in s:
        s = s.replace(
            "import com.assistant.adapter.smartassist.RuntimePerformanceCoordinator",
            "import com.assistant.adapter.smartassist.RuntimePerformanceCoordinator\n"
            "import com.assistant.adapter.smartassist.SmartAssistRepository"
        )

    s = re.sub(
        r'RuntimePerformanceCoordinator\.updateAuthority\s*\([^)]*\)',
        "RuntimePerformanceCoordinator.updateAuthority(SmartAssistRepository.configuration().authority)",
        s,
        flags=re.S
    )

    f.write_text(s)

# -------------------------------------------------------
# RuntimePerformanceCoordinator
# -------------------------------------------------------

s = rpc.read_text()

s = re.sub(
    r'fun\s+runtimeAuthority\(\)\s*:\s*Int\s*=\s*.*',
    'fun runtimeAuthority(): Int = authority().coerceIn(0,100) * 10',
    s
)

for fn in (
    "goalkeeperAuthority",
    "interceptionAuthority",
    "smartAssistAuthority"
):
    s = re.sub(
        rf'fun\s+{fn}\(\)\s*:\s*Int\s*=\s*.*',
        f'fun {fn}(): Int = runtimeAuthority()',
        s
    )

rpc.write_text(s)

print("PHASE10 FINAL AUTHORITY PATCH COMPLETE")
