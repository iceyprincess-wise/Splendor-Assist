import os, sys

REPO = os.path.expanduser("~/projects/Splendor-Assist")
TARGET = "/sdcard/Splendor-Assist"

def patch(rel, old, new, label):
    path = os.path.join(REPO, rel)
    if not os.path.exists(path):
        print(f"  MISSING: {rel}")
        return False
    content = open(path, "r", encoding="utf-8").read()
    if old not in content:
        print(f"  MISS [{label}] in {rel}")
        return False
    content = content.replace(old, new, 1)
    open(path, "w", encoding="utf-8").write(content)
    print(f"  OK [{label}] {rel}")
    return True

ok = True

# ── 1. RuntimeLogger.kt ───────────────────────────────────────────────────────
RL = "diagnostic_core/src/main/java/com/assistant/diagnostic/RuntimeLogger.kt"

ok &= patch(RL,
    '''externalLogFile = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "Splendor_Field_Logs.txt"
            )''',
    '''externalLogFile = File("/sdcard/Splendor-Assist", "Splendor_Field_Logs.txt").also { it.parentFile?.mkdirs() }''',
    "RuntimeLogger externalLogFile → /sdcard/Splendor-Assist/")

ok &= patch(RL,
    '"/storage/emulated/0/SplendorAssist/Forensics"',
    '"/sdcard/Splendor-Assist/Forensics"',
    "RuntimeLogger forensicDir → /sdcard/Splendor-Assist/Forensics/")

# ── 2. RuntimeSelfHealEngine.kt ───────────────────────────────────────────────
RSH = "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeSelfHealEngine.kt"

# patch both the KDoc comment and the actual hardcoded path used in writeToFile
ok &= patch(RSH,
    "/sdcard/Download/SplendorHealLog.txt",
    "/sdcard/Splendor-Assist/SplendorHealLog.txt",
    "RuntimeSelfHealEngine HealLog path (comment)")

# second occurrence (the actual File(...) inside writeToFile) — replace again
path = os.path.join(REPO, RSH)
content = open(path, "r", encoding="utf-8").read()
if "/sdcard/Download/SplendorHealLog.txt" in content:
    content = content.replace("/sdcard/Download/SplendorHealLog.txt",
                              "/sdcard/Splendor-Assist/SplendorHealLog.txt")
    open(path, "w", encoding="utf-8").write(content)
    print("  OK [RuntimeSelfHealEngine HealLog path (actual)] " + RSH)

# ── 3. OverlayService.kt ─────────────────────────────────────────────────────
OS = "app/src/main/java/com/assistant/OverlayService.kt"

ok &= patch(OS,
    'val logFile = File(getExternalFilesDir(null), "crash_log.txt")',
    'val logFile = File("/sdcard/Splendor-Assist", "crash_log.txt").also { it.parentFile?.mkdirs() }',
    "OverlayService crash_log.txt → /sdcard/Splendor-Assist/")

# ── 4. DeathWatch.kt ─────────────────────────────────────────────────────────
DW = "app/src/main/java/com/assistant/DeathWatch.kt"

ok &= patch(DW,
    'try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) } catch (_: Throwable) { null },',
    'try { java.io.File("/sdcard/Splendor-Assist").apply { mkdirs() } } catch (_: Throwable) { null },',
    "DeathWatch reportFile → /sdcard/Splendor-Assist/")

# ── 5. CrashInspector.kt ─────────────────────────────────────────────────────
CI = "diagnostic_core/src/main/java/com/assistant/diagnostic/CrashInspector.kt"

ok &= patch(CI,
    'val LOG_DIR = File("/sdcard/Splendor Assist/data/logs")',
    'val LOG_DIR = File("/sdcard/Splendor-Assist")',
    "CrashInspector LOG_DIR → /sdcard/Splendor-Assist/")

print()
if ok:
    print("ALL PATCHES OK")
else:
    print("ONE OR MORE PATCHES MISSED — check MISS lines above")
    sys.exit(1)
