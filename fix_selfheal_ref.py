path = "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeSelfHealEngine.kt"
with open(path, "r") as f:
    src = f.read()

old = (
    "                val restarted = try {\n"
    "                    // PHASE5B: direct companion call replaces fragile reflection.\n"
    "                    // WeakRef was GC-nulled under memory pressure — exactly the\n"
    "                    // scenario that triggers recovery. Direct nullable ref is\n"
    "                    // cleared in onDestroy() so no leak risk.\n"
    "                    com.assistant.OverlayService.restartCaptureIfAlive()\n"
    "                } catch (e: Throwable) {\n"
    "                    RuntimeLogger.log(\"AGENT: restartCaptureIfAlive failed: ${e.message}\", \"AGENT\")\n"
    "                    false\n"
    "                }"
)
new = (
    "                val restarted = try {\n"
    "                    // PHASE5B-FIX: reflection avoids circular module dependency.\n"
    "                    // adapter_smartassist cannot reference :app directly.\n"
    "                    // OverlayService.companion.restartCaptureIfAlive() is a static\n"
    "                    // method — invoke(null) is correct for companion object methods.\n"
    "                    val cls = Class.forName(\"com.assistant.OverlayService\")\n"
    "                    val method = cls.getDeclaredMethod(\"restartCaptureIfAlive\")\n"
    "                    (method.invoke(null) as? Boolean) ?: false\n"
    "                } catch (e: Throwable) {\n"
    "                    RuntimeLogger.log(\"AGENT: restartCaptureIfAlive failed: ${e.message}\", \"AGENT\")\n"
    "                    false\n"
    "                }"
)

if old in src:
    src = src.replace(old, new, 1)
    with open(path, "w") as f:
        f.write(src)
    print("OK: RuntimeSelfHealEngine — OverlayService reference replaced with reflection")
else:
    print("ERROR: target not found — printing surrounding context")
    idx = src.find("com.assistant.OverlayService.restartCaptureIfAlive")
    if idx != -1:
        print(repr(src[max(0,idx-300):idx+100]))
    else:
        print("restartCaptureIfAlive reference not found at all")
