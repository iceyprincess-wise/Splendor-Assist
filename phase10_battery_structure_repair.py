from pathlib import Path

f = Path("app/src/main/java/com/assistant/MainActivity.kt")
lines = f.read_text().splitlines()

start = None
end = None

# Locate checkBatteryAndProceed()
for i, line in enumerate(lines):
    if "private fun checkBatteryAndProceed()" in line:
        start = i
        break

if start is None:
    raise SystemExit("checkBatteryAndProceed() not found")

depth = 0
opened = False

for i in range(start, len(lines)):
    depth += lines[i].count("{")
    if lines[i].count("{"):
        opened = True
    depth -= lines[i].count("}")
    if opened and depth == 0:
        end = i
        break

if end is None:
    raise SystemExit("Could not determine function end")

replacement = """
    private fun checkBatteryAndProceed() {

        if (!true) {
            Toast.makeText(
                this,
                "Set Your Optimization first",
                Toast.LENGTH_SHORT
            ).show()

            startActivity(
                Intent(
                    this,
                    SmartAssistControlRoomActivity::class.java
                )
            )
            return
        }

        try {

            if (ComplianceState.battery(this)) {
                checkAccessibilityAndProceed()
                return
            }

            if (!openBatteryOptimizationManager()) {
                checkAccessibilityAndProceed()
            }

        } catch (_: Exception) {
            checkAccessibilityAndProceed()
        }
    }
""".strip("\n").splitlines()

new_lines = lines[:start] + replacement + lines[end+1:]

f.write_text("\n".join(new_lines) + "\n")

print("STRUCTURE REPAIRED")
