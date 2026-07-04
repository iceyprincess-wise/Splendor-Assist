from pathlib import Path
import re

repo_file = Path("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/SmartAssistRepository.kt")
overlay_file = Path("app/src/main/java/com/assistant/OverlayService.kt")
activity_file = Path("app/src/main/java/com/assistant/controlroom/ui/SmartAssistControlRoomActivity.kt")

repo = repo_file.read_text()
overlay = overlay_file.read_text()
activity = activity_file.read_text()

# ------------------------------------------------------------------
# PHASE10_PANIC_PERSISTENCE_FINAL_MARKER
# Persist static panic state through repository instead of volatile copy
# ------------------------------------------------------------------

if "PHASE10_PANIC_PERSISTENCE_FINAL_MARKER" not in repo:

    repo = repo.replace(
        "fun activatePanic() {\n            staticState = staticState.copy(panicMode = true)\n        }",
        """// PHASE10_PANIC_PERSISTENCE_FINAL_MARKER
        fun activatePanic() {
            instance?.updatePanicMode(true)
                ?: run {
                    staticState = staticState.copy(panicMode = true)
                }
        }"""
    )

    repo = repo.replace(
        "fun clearPanic() {\n            staticState = staticState.copy(panicMode = false)\n        }",
        """fun clearPanic() {
            instance?.updatePanicMode(false)
                ?: run {
                    staticState = staticState.copy(panicMode = false)
                }
        }"""
    )

# ------------------------------------------------------------------
# Remove unconditional panic resets in OverlayService
# ------------------------------------------------------------------

overlay = overlay.replace(
    "SmartAssistRepository.clearPanic()",
    "// PHASE10_PANIC_PERSISTENCE_KEEP_STATE"
)

overlay = overlay.replace(
    "// PHASE10_PANIC_PERSISTENCE_KEEP_STATE\n",
    "",
    2
)

# Preserve only runtime-invalidation clear
overlay = overlay.replace(
    "if (!panicActive && SmartAssistRepository.panicActive()) {\n                SmartAssistRepository.clearPanic()\n            }",
    """if (!panicActive && SmartAssistRepository.panicActive()) {
                SmartAssistRepository.clearPanic()
            }"""
)

# ------------------------------------------------------------------
# Restore panic toggle from repository every resume
# ------------------------------------------------------------------

if "findViewById<Switch>(R.id.swPanic).isChecked" not in activity:
    activity = activity.replace(
        """findViewById<Switch>(R.id.swEnabled).isChecked =
            true""",
        """findViewById<Switch>(R.id.swEnabled).isChecked =
            SmartAssistRepository.enabled()

        findViewById<Switch>(R.id.swPanic).isChecked =
            SmartAssistRepository.panicActive()"""
    )

repo_file.write_text(repo)
overlay_file.write_text(overlay)
activity_file.write_text(activity)

print("PATCH COMPLETE")
