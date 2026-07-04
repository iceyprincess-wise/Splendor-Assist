from pathlib import Path
import re

ROOT = Path.home() / "projects" / "Splendor-Assist"

GRADLE = ROOT / "app/build.gradle"
GRADLE_KTS = ROOT / "app/build.gradle.kts"
MAIN = ROOT / "app/src/main/java/com/assistant/MainActivity.kt"

gradle = GRADLE if GRADLE.exists() else GRADLE_KTS

if not gradle.exists():
    raise SystemExit("app/build.gradle(.kts) not found")

g = gradle.read_text()
m = MAIN.read_text()

# ---------- ViewBinding ----------
if "viewBinding true" not in g and "viewBinding = true" not in g:

    if "buildFeatures {" in g:
        g = g.replace(
            "buildFeatures {",
            "buildFeatures {\n        viewBinding true",
            1
        )
    elif "android {" in g:
        g = g.replace(
            "android {",
            "android {\n    buildFeatures {\n        viewBinding true\n    }\n",
            1
        )

# ---------- Material3 ----------
if "com.google.android.material:material" not in g:
    g = re.sub(
        r"dependencies\s*\{",
        "dependencies {\n    implementation 'com.google.android.material:material:1.12.0'",
        g,
        count=1
    )

# ---------- Runtime Dashboard ----------
marker = "PHASE10_RUNTIME_DASHBOARD_MARKER"

if marker not in m:

    block = """

    // PHASE10_RUNTIME_DASHBOARD_MARKER

    private fun updateRuntimeDashboardCards() {

        runCatching {
            refreshDashboardStatus()
        }

        runCatching {
            refreshRuntimeDashboard()
        }

        runCatching {
            RuntimePerformanceCoordinator.synchronizeExistingPerformanceEngines()
        }

        runCatching {
            RuntimePerformanceCoordinator.synchronizeRuntimePipeline()
        }
    }

"""

    if "private fun refreshDashboardStatus()" in m:
        m = m.replace(
            "private fun refreshDashboardStatus()",
            block + "\n    private fun refreshDashboardStatus()",
            1
        )

    m = re.sub(
        r"refreshRuntimeDashboard\(\)",
        "refreshRuntimeDashboard()\n        updateRuntimeDashboardCards()",
        m,
        count=1
    )

gradle.write_text(g)
MAIN.write_text(m)

print("PATCH COMPLETE")
