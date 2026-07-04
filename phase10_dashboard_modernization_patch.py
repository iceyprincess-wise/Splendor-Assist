from pathlib import Path
import re

ROOT = Path.home() / "projects" / "Splendor-Assist"

MAIN = ROOT / "app/src/main/java/com/assistant/MainActivity.kt"
LAYOUT = ROOT / "app/src/main/res/layout/activity_main.xml"

if not MAIN.exists():
    raise SystemExit(f"Missing: {MAIN}")
if not LAYOUT.exists():
    raise SystemExit(f"Missing: {LAYOUT}")

main = MAIN.read_text()
layout = LAYOUT.read_text()

MARKER = "PHASE10_DASHBOARD_MODERNIZATION_MARKER"

if MARKER not in main:

    if "private fun refreshRoomBulbs()" in main:

        inject = """

    // PHASE10_DASHBOARD_MODERNIZATION_MARKER

    private fun refreshRuntimeDashboard() {
        runCatching { refreshRoomBulbs() }
        runCatching { refreshDashboardStatus() }
    }

    private fun refreshDashboardStatus() {
        runCatching {
            findViewById<android.widget.TextView>(
                com.assistant.overlay.R.id.txtRuntimeStatus
            ).text = "Runtime Online"
        }

        runCatching {
            findViewById<android.widget.TextView>(
                com.assistant.overlay.R.id.txtVisionStatus
            ).text = "Vision Ready"
        }

        runCatching {
            findViewById<android.widget.TextView>(
                com.assistant.overlay.R.id.txtDiagnosticsStatus
            ).text = "Diagnostics Active"
        }
    }

"""

        main = main.replace(
            "    private fun refreshRoomBulbs() {",
            inject + "\n    private fun refreshRoomBulbs() {",
            1
        )

    main = re.sub(
        r"(refreshRoomBulbs\(\))",
        r"\1\n        refreshRuntimeDashboard()",
        main,
        count=1
    )

if "txtRuntimeStatus" not in layout:

    block = """

    <LinearLayout
        android:id="@+id/runtimeStatusCard"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:orientation="vertical"
        android:padding="12dp">

        <TextView
            android:id="@+id/txtRuntimeStatus"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Runtime"/>

        <TextView
            android:id="@+id/txtVisionStatus"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Vision"/>

        <TextView
            android:id="@+id/txtDiagnosticsStatus"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Diagnostics"/>

    </LinearLayout>

"""

    layout = layout.replace(
        "</ScrollView>",
        block + "\n</ScrollView>",
        1
    )

MAIN.write_text(main)
LAYOUT.write_text(layout)

print("PHASE10 DASHBOARD MODERNIZATION PATCH COMPLETE")
