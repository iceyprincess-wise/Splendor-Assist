from pathlib import Path
import re

f = Path("app/src/main/java/com/assistant/MainActivity.kt")
src = f.read_text()

# ------------------------------------------------------------------
# Ensure ComplianceState import
# ------------------------------------------------------------------
if "import com.assistant.compliance.ComplianceState" not in src:
    src = src.replace(
        "import com.assistant.adapter.smartassist.ConfidenceHeatmap",
        "import com.assistant.adapter.smartassist.ConfidenceHeatmap\nimport com.assistant.compliance.ComplianceState"
    )

# ------------------------------------------------------------------
# Stronger OEM detection
# ------------------------------------------------------------------
src = src.replace(
'''        val manufacturer =
            Build.MANUFACTURER.lowercase()

        if (
            manufacturer.contains("xiaomi") ||
            manufacturer.contains("redmi") ||
            manufacturer.contains("poco")
        ) {''',
'''        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()

        if (
            manufacturer.contains("xiaomi") ||
            manufacturer.contains("redmi") ||
            manufacturer.contains("poco") ||
            brand.contains("xiaomi") ||
            brand.contains("redmi") ||
            brand.contains("poco")
        ) {'''
)

# ------------------------------------------------------------------
# Replace duplicate PowerManager logic with ComplianceState
# ------------------------------------------------------------------
pattern = re.compile(
r'''    private fun checkBatteryAndProceed\(\)\s*\{.*?^\s*\}''',
re.S | re.M
)

replacement = r'''
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
'''

src = pattern.sub(replacement, src, count=1)

f.write_text(src)
print("BATTERY OPTIMIZATION PATCH APPLIED")
