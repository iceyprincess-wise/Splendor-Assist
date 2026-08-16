import os, re

ROOT = os.path.expanduser("~/projects/Splendor-Assist")

# ── 1. OverlayService.kt ──────────────────────────────────────────────────
OVERLAY = os.path.join(ROOT, "app/src/main/java/com/assistant/OverlayService.kt")
with open(OVERLAY, "r") as f:
    ov = f.read()
orig_ov = ov

# 1a. Delete entire startRuntimeRecorder + stopRuntimeRecorder methods.
# Anchor: from the blank line before startRuntimeRecorder
# through to the line just before "override fun onDestroy()"
START_METHODS = "\n\nprivate fun startRuntimeRecorder() {"
END_METHODS   = "\noverride fun onDestroy() {"

# Try with leading spaces too
for start_anchor in [
    "\n\n    private fun startRuntimeRecorder() {",
    "\n    private fun startRuntimeRecorder() {",
]:
    if start_anchor in ov:
        START_METHODS = start_anchor
        break

for end_anchor in [
    "\n    override fun onDestroy() {",
    "\noverride fun onDestroy() {",
]:
    if end_anchor in ov:
        END_METHODS = end_anchor
        break

if START_METHODS in ov and END_METHODS in ov:
    idx_s = ov.index(START_METHODS)
    idx_e = ov.index(END_METHODS, idx_s)
    removed = ov[idx_s:idx_e]
    ov = ov[:idx_s] + "\n" + ov[idx_e:]
    print(f"OK: deleted startRuntimeRecorder+stopRuntimeRecorder ({removed.count(chr(10))} lines)")
else:
    print(f"MISS startRuntimeRecorder: found={START_METHODS in ov}")
    print(f"MISS onDestroy anchor: found={END_METHODS in ov}")

# 1b. Remove stopRuntimeRecorder() call from onDestroy
for pat in [
    "        stopRuntimeRecorder()\n",
    "    stopRuntimeRecorder()\n",
]:
    if pat in ov:
        ov = ov.replace(pat, "")
        print("OK: removed stopRuntimeRecorder() call from onDestroy")
        break
else:
    if "stopRuntimeRecorder" in ov:
        print("MISS: stopRuntimeRecorder call pattern differs — still present!")
    else:
        print("INFO: stopRuntimeRecorder call already gone")

if ov != orig_ov:
    with open(OVERLAY, "w") as f:
        f.write(ov)
    print("SAVED: OverlayService.kt")

# ── 2. PerformanceGovernor.kt — strip DVR, return false ──────────────────
PG = os.path.join(ROOT,
    "app/src/main/java/com/assistant/overlay/runtime/PerformanceGovernor.kt")
PG_NEW = """package com.assistant.overlay.runtime

import android.content.Context

object PerformanceGovernor {

    // DVR removed (Item 3) — recording is permanently disabled.
    @Suppress("UNUSED_PARAMETER")
    fun allowRecording(
        context: Context,
        thermalLevel: Int
    ): Boolean = false
}
"""
with open(PG, "w") as f:
    f.write(PG_NEW)
print("SAVED: PerformanceGovernor.kt (DVR stripped, allowRecording=false)")

# ── 3. FutureRoomsActivity.kt — remove AdminSettingsActivity import+button ─
FRA = os.path.join(ROOT,
    "app/src/main/java/com/assistant/controlroom/ui/FutureRoomsActivity.kt")
with open(FRA, "r") as f:
    fra = f.read()
orig_fra = fra

# Remove import
fra = fra.replace("import com.assistant.admin.AdminSettingsActivity\n", "")
print("OK: removed AdminSettingsActivity import" if fra != orig_fra else "MISS: AdminSettingsActivity import")

# Replace the Admin Settings button + its description text with a placeholder text only
OLD_BTN = (
            'btn(root, "\u2699\uFE0F   Admin Settings  \u2014  Engine Tuning", Color.parseColor("#1565C0")) {\n'
            '            startActivity(Intent(this, AdminSettingsActivity::class.java))\n'
            '        }\n'
            '        text(root, "   Live-tunable engine constants \u2014 no rebuild needed", 11f, color = Color.parseColor("#AAAAAA"))'
)
NEW_BTN = (
            'text(root, "  \u2699\uFE0F  Admin Settings \u2014 removed", 11f, color = Color.parseColor("#444444"))'
)
if OLD_BTN in fra:
    fra = fra.replace(OLD_BTN, NEW_BTN)
    print("OK: replaced AdminSettings button with placeholder text")
else:
    # Fallback: line-level removal of any remaining AdminSettingsActivity reference
    lines = fra.split("\n")
    cleaned = [l for l in lines if "AdminSettingsActivity" not in l]
    if len(cleaned) < len(lines):
        fra = "\n".join(cleaned)
        print(f"OK: removed {len(lines)-len(cleaned)} lines referencing AdminSettingsActivity (fallback)")
    else:
        print("MISS: AdminSettingsActivity reference not found in FutureRoomsActivity")

if fra != orig_fra:
    with open(FRA, "w") as f:
        f.write(fra)
    print("SAVED: FutureRoomsActivity.kt")

print("\nDone.")
