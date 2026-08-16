import os

ROOT = os.path.expanduser("~/projects/Splendor-Assist")
OVERLAY = os.path.join(ROOT, "app/src/main/java/com/assistant/OverlayService.kt")

with open(OVERLAY, "r") as f:
    content = f.read()
orig = content

UUID_IMPORT = "import java.util.UUID\n"
if UUID_IMPORT in content:
    content = content.replace(UUID_IMPORT, "")
    print("OK: removed UUID import")
else:
    print("INFO: UUID import already gone")

BROKEN_START = "                            val recording =\n"
KEEP_FROM = "                            updateOverlayVisuals(\n"

CLEAN = (
    "                            RuntimeNotificationCoordinator.update(\n"
    "                                context = applicationContext,\n"
    "                                antiban = true,\n"
    "                                matchDetected = true,\n"
    "                                recording = false,\n"
    "                                saved = false\n"
    "                            )\n"
    "\n"
    "                            RuntimeLogger.log(\n"
    "                                \"\U0001f576\ufe0f\",\n"
    "                                \"SMART_ASSIST\"\n"
    "                            )\n"
    "\n"
)

if BROKEN_START not in content:
    print("MISS: BROKEN_START not found")
    idx = content.find("val recording")
    if idx >= 0:
        print("  nearby:", repr(content[idx-30:idx+80]))
elif KEEP_FROM not in content:
    print("MISS: KEEP_FROM not found")
    idx = content.find("updateOverlayVisuals")
    if idx >= 0:
        print("  nearby:", repr(content[idx-30:idx+80]))
else:
    idx_start = content.index(BROKEN_START)
    idx_keep = content.index(KEEP_FROM, idx_start)
    removed = content[idx_start:idx_keep]
    print(f"OK: found broken block — {len(removed.splitlines())} lines removed")
    content = content[:idx_start] + CLEAN + content[idx_keep:]
    print("OK: replaced with clean notification call")

if content != orig:
    with open(OVERLAY, "w") as f:
        f.write(content)
    print("SAVED: OverlayService.kt")
else:
    print("WARNING: no changes written")

GRID = os.path.join(ROOT,
    "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/GridRecentsInterceptor.kt")
if os.path.exists(GRID):
    os.remove(GRID)
    print("DELETED: GridRecentsInterceptor.kt")
else:
    print("INFO: GridRecentsInterceptor.kt already gone")

print("Done.")
