from pathlib import Path
import re

root = Path.home() / "projects" / "Splendor-Assist"
target = root / "app/src/main/java/com/assistant/MainActivity.kt"

src = target.read_text(encoding="utf-8")

imports = {
    "import android.content.ComponentName": "import android.content.ComponentName",
    "import android.content.pm.ResolveInfo": "import android.content.pm.ResolveInfo",
}

for imp in imports.values():
    if imp not in src:
        m = re.search(r"import android\.content\.pm\.PackageManager\n", src)
        if m:
            src = src[:m.end()] + imp + "\n" + src[m.end():]

if "PHASE10_BATTERY_VENDOR_MARKER" not in src:
    helper = r'''

    // PHASE10_BATTERY_VENDOR_MARKER

    private fun launchIfExists(intent: Intent): Boolean {
        return if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
            true
        } else {
            false
        }
    }

    private fun openBatteryOptimizationManager(): Boolean {

        val manufacturer =
            Build.MANUFACTURER.lowercase()

        if (
            manufacturer.contains("xiaomi") ||
            manufacturer.contains("redmi") ||
            manufacturer.contains("poco")
        ) {

            val vendorIntents = listOf(

                Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                },

                Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.powercenter.PowerSettings"
                    )
                },

                Intent().apply {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.parse("package:$packageName")
                }
            )

            vendorIntents.forEach {
                if (launchIfExists(it))
                    return true
            }
        }

        val fallback = listOf(

            Intent(
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            ),

            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            ),

            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )

        fallback.forEach {
            if (launchIfExists(it))
                return true
        }

        return false
    }
'''

    anchor = "    private fun checkBatteryAndProceed()"
    src = src.replace(anchor, helper + "\n" + anchor, 1)

pattern = re.compile(
    r'''try\s*\{
\s*val pm = getSystemService\(Context\.POWER_SERVICE\) as PowerManager
\s*
\s*if\s*\(
\s*Build\.VERSION\.SDK_INT >= Build\.VERSION_CODES\.M &&
\s*!pm\.isIgnoringBatteryOptimizations\(packageName\)
\s*\)\s*\{
\s*startActivity\(
\s*Intent\(
\s*Settings\.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
\s*Uri\.parse\("package:\$packageName"\)
\s*\)
\s*\)
\s*\}\s*else\s*\{
\s*checkAccessibilityAndProceed\(\)
\s*\}
\s*\}\s*catch\s*\(_: Exception\)\s*\{
\s*checkAccessibilityAndProceed\(\)
\s*\}''',
    re.S,
)

replacement = '''
        try {
            val pm =
                getSystemService(Context.POWER_SERVICE) as PowerManager

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !pm.isIgnoringBatteryOptimizations(packageName)
            ) {

                if (!openBatteryOptimizationManager()) {
                    checkAccessibilityAndProceed()
                }

            } else {
                checkAccessibilityAndProceed()
            }

        } catch (_: Exception) {
            checkAccessibilityAndProceed()
        }
'''

src, count = pattern.subn(replacement, src, count=1)

if count != 1:
    raise SystemExit("BATTERY FLOW NOT PATCHED")

target.write_text(src, encoding="utf-8")
print("BATTERY FLOW PATCH COMPLETE")
