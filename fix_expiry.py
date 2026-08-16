path = "adapter_input/src/main/java/com/assistant/adapter/input/InputAdapterService.kt"
with open(path, "r") as f:
    src = f.read()
# Add expiry check to heartbeat runnable — it runs every 10s which is fine
# for detecting 350ms expired gestures (only logs, never blocks)
old_hb = (
    "            RuntimeLogger.log(\"InputAdapter heartbeat\", \"HEALTH\")\n"
    "            heartbeatHandler.postDelayed(this, 10000)"
)
new_hb = (
    "            RuntimeLogger.log(\"InputAdapter heartbeat\", \"HEALTH\")\n"
    "            try { GestureTimingFeedbackEngine.checkExpiry() } catch (_: Throwable) {}\n"
    "            heartbeatHandler.postDelayed(this, 10000)"
)
if old_hb in src:
    src = src.replace(old_hb, new_hb, 1)
    with open(path, "w") as f:
        f.write(src)
    print("OK: InputAdapterService — GestureTimingFeedbackEngine.checkExpiry() wired to heartbeat")
else:
    print("ERROR: heartbeat target not found in InputAdapterService")
