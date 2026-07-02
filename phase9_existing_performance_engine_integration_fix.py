from pathlib import Path
import re
import sys

ROOT = Path.home() / "projects" / "Splendor-Assist"
FILE = ROOT / "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimePerformanceCoordinator.kt"

text = FILE.read_text(encoding="utf-8")

replacement = """    fun synchronizeExistingPerformanceEngines() {

        /*
         * Existing performance engines are preserved.
         * Integration remains indirect until concrete module-visible
         * APIs are audited. This avoids introducing unresolved
         * cross-module references while keeping the orchestration
         * entry point stable.
         */

        synchronizeRuntimePipeline()
    }
"""

pattern = re.compile(
    r'    fun synchronizeExistingPerformanceEngines\(\)\s*\{.*?^\s*\}',
    re.S | re.M
)

text, count = pattern.subn(replacement, text, count=1)

if count != 1:
    print("PATCH FAILED")
    sys.exit(2)

FILE.write_text(text, encoding="utf-8")
print("PATCH COMPLETE")
