path = "diagnostic_core/src/main/java/com/assistant/diagnostic/AdapterSignalBus.kt"
with open(path, "r") as f:
    src = f.read()

# Remove the orphaned tail that was appended outside the object
old_tail = (
    "}\n"
    "\n"
    "    // PHASE5B: memory → capture bridge signal\n"
    "    @Volatile var captureThrottle: Int = 0; private set\n"
    "    fun publishCaptureThrottle(level: Int) { captureThrottle = level.coerceIn(0, 3) }\n"
    "    val captureIsThrottled: Boolean get() = captureThrottle > 0\n"
    "\n"
    "    // PHASE5B: load shed → execution brake signal\n"
    "    @Volatile var executionBrake: Int = 0; private set\n"
    "    fun publishExecutionBrake(level: Int) { executionBrake = level.coerceIn(0, 2) }\n"
    "    val executionIsBraked: Boolean get() = executionBrake > 0\n"
    "    val executionIsFullyBraked: Boolean get() = executionBrake >= 2\n"
    "\n"
)
new_tail = (
    "    // PHASE5B: memory → capture bridge signal\n"
    "    @Volatile var captureThrottle: Int = 0; private set\n"
    "    fun publishCaptureThrottle(level: Int) { captureThrottle = level.coerceIn(0, 3) }\n"
    "    val captureIsThrottled: Boolean get() = captureThrottle > 0\n"
    "\n"
    "    // PHASE5B: load shed → execution brake signal\n"
    "    @Volatile var executionBrake: Int = 0; private set\n"
    "    fun publishExecutionBrake(level: Int) { executionBrake = level.coerceIn(0, 2) }\n"
    "    val executionIsBraked: Boolean get() = executionBrake > 0\n"
    "    val executionIsFullyBraked: Boolean get() = executionBrake >= 2\n"
    "}\n"
)

if old_tail in src:
    src = src.replace(old_tail, new_tail, 1)
    with open(path, "w") as f:
        f.write(src)
    print("OK: AdapterSignalBus — PHASE5B fields moved inside object body")
else:
    print("ERROR: target tail not found — printing last 400 chars for inspection")
    print(repr(src[-400:]))
