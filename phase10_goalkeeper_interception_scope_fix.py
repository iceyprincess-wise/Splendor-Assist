from pathlib import Path
import re

FILES = [
    Path("app/src/main/java/com/assistant/controlroom/ui/GoalkeeperControlRoomActivity.kt"),
    Path("app/src/main/java/com/assistant/controlroom/ui/InterceptionControlRoomActivity.kt"),
]

for f in FILES:
    s = f.read_text()

    # Remove any accidental top-level onResume() blocks
    s = re.sub(
        r'\noverride fun onResume\(\)\s*\{.*?(?=\n(class|private|companion|$))',
        '\n',
        s,
        flags=re.S
    )

    # Locate the Activity class body
    m = re.search(
        r'class\s+\w+Activity[^{]*\{',
        s
    )
    if not m:
        f.write_text(s)
        continue

    class_start = m.end()

    # Find last closing brace of class
    class_end = s.rfind("}")
    body = s[class_start:class_end]

    # Remove any duplicate onResume inside class
    body = re.sub(
        r'override\s+fun\s+onResume\(\)\s*\{.*?\n\s*\}',
        '',
        body,
        flags=re.S
    )

    inject = """

    override fun onResume() {
        super.onResume()

        RuntimePerformanceCoordinator.updateAuthority(
            SmartAssistRepository.configuration().authority
        )
    }

"""

    body = body.rstrip() + inject

    s = s[:class_start] + body + "\n}"

    f.write_text(s)

print("GOALKEEPER / INTERCEPTION STRUCTURE FIX COMPLETE")
