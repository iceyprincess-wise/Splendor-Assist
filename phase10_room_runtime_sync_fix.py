from pathlib import Path
import re

FILES = [
    Path("app/src/main/java/com/assistant/controlroom/ui/GoalkeeperControlRoomActivity.kt"),
    Path("app/src/main/java/com/assistant/controlroom/ui/InterceptionControlRoomActivity.kt"),
]

IMPORT = "import com.assistant.adapter.smartassist.SmartAssistRepository"

for f in FILES:
    s = f.read_text()

    # ----------------------------------------------------------
    # add missing import
    # ----------------------------------------------------------
    if IMPORT not in s:
        pkg = re.search(r'^(package[^\n]*\n)', s, re.M)
        if pkg:
            pos = pkg.end(1)
            s = s[:pos] + IMPORT + "\n" + s[pos:]

    # ----------------------------------------------------------
    # remove bad top-level onResume injected previously
    # ----------------------------------------------------------
    s = re.sub(
        r'\n\s*override\s+fun\s+onResume\(\)\s*\{.*?\n\s*\}\s*$',
        '\n',
        s,
        flags=re.S
    )

    # ----------------------------------------------------------
    # remove duplicated authority sync after super.onCreate
    # ----------------------------------------------------------
    s = re.sub(
        r'\n\s*RuntimePerformanceCoordinator\.updateAuthority\(\s*'
        r'SmartAssistRepository\.configuration\(\)\.authority\s*\)\s*',
        '\n',
        s,
        count=1,
        flags=re.S
    )

    # ----------------------------------------------------------
    # inject runtime sync INSIDE existing onResume()
    # ----------------------------------------------------------
    if "RuntimePerformanceCoordinator.updateAuthority(" not in s:
        s = re.sub(
            r'(override\s+fun\s+onResume\(\)\s*\{\s*super\.onResume\(\))',
            r'\1\n'
            r'        RuntimePerformanceCoordinator.updateAuthority(\n'
            r'            SmartAssistRepository.configuration().authority\n'
            r'        )',
            s,
            count=1
        )

    f.write_text(s)

rpc = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimePerformanceCoordinator.kt")
t = rpc.read_text()

t = re.sub(
    r'fun\s+runtimeAuthority\(\):Int\s*=\s*.*',
    'fun runtimeAuthority():Int = masterAuthority.coerceIn(0,100) * 10',
    t,
    count=1
)

rpc.write_text(t)

print("PHASE10 RUNTIME FIX COMPLETE")
