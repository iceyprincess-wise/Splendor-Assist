import os

base_dir = os.path.expanduser("~/projects/Splendor-Assist")

def apply_patch(rel_path, old_str, new_str, desc):
    path = os.path.join(base_dir, rel_path)
    if not os.path.exists(path):
        print(f"MISSING: {path}")
        return False
    with open(path, "r") as f:
        content = f.read()
    if old_str in content and new_str not in content:
        content = content.replace(old_str, new_str)
        with open(path, "w") as f:
            f.write(content)
        print(f"PATCHED: {desc}")
        return True
    elif new_str in content:
        print(f"ALREADY PATCHED: {desc}")
        return True
    else:
        print(f"MISMATCH: {desc}")
        return False

# 1. Inject velocity fields into RuntimeFrame
apply_patch(
    "core/src/main/java/com/assistant/runtime/RuntimeFrame.kt",
    "    val hasBall: Boolean,\n    val ballX: Float,\n    val ballY: Float,",
    "    val hasBall: Boolean,\n    val ballX: Float,\n    val ballY: Float,\n    val ballVelocityX: Float = 0f,\n    val ballVelocityY: Float = 0f,",
    "RuntimeFrame.kt (ballVelocity)"
)

# 2. Wire FrameAssembler to read and pass velocity
apply_patch(
    "app/src/main/java/com/assistant/adapter/smartassist/FrameAssembler.kt",
    "        val telemetry = try { TelemetryRepository.current() } catch (_: Throwable) { null }\n        val ballX = telemetry?.ballX ?: 0f\n        val ballY = telemetry?.ballY ?: 0f",
    "        val telemetry = try { TelemetryRepository.current() } catch (_: Throwable) { null }\n        val ballX = telemetry?.ballX ?: 0f\n        val ballY = telemetry?.ballY ?: 0f\n        val ballVx = telemetry?.ballVelocityX ?: 0f\n        val ballVy = telemetry?.ballVelocityY ?: 0f",
    "FrameAssembler.kt (read velocity)"
)

apply_patch(
    "app/src/main/java/com/assistant/adapter/smartassist/FrameAssembler.kt",
    "            hasBall = hasBall,\n            ballX = ballX,\n            ballY = ballY,",
    "            hasBall = hasBall,\n            ballX = ballX,\n            ballY = ballY,\n            ballVelocityX = ballVx,\n            ballVelocityY = ballVy,",
    "FrameAssembler.kt (pass velocity)"
)

# 3. Fix enum typo in SharpTouchTimingContributor
apply_patch(
    "app/src/main/java/com/assistant/adapter/smartassist/contributors/SharpTouchTimingContributor.kt",
    "            else -> ActionClass.DEFENSE",
    "            else -> ActionClass.DEFEND",
    "SharpTouchTimingContributor.kt (DEFEND)"
)

# 4. Connect engines to AppContributorRegistration
ar_path = os.path.join(base_dir, "app/src/main/java/com/assistant/AppContributorRegistration.kt")
if os.path.exists(ar_path):
    with open(ar_path, "r") as f:
        ar_content = f.read()
    
    changed = False
    if "const val EXPECTED_CONTRIBUTOR_COUNT = 37" in ar_content:
        ar_content = ar_content.replace("const val EXPECTED_CONTRIBUTOR_COUNT = 37", "const val EXPECTED_CONTRIBUTOR_COUNT = 39")
        changed = True
        
    if "\"SAUltimateCorrector\"\n    )" in ar_content and "\"SharpTouchTiming\"" not in ar_content:
        ar_content = ar_content.replace("\"SAUltimateCorrector\"\n    )", "\"SAUltimateCorrector\",\n        \"SharpTouchTiming\", \"KickingPosture\"\n    )")
        changed = True
        
    if "com.assistant.adapter.smartassist.contributors.SmartAssistUltimateCorrectorContributor\n                    )" in ar_content and "SharpTouchTimingContributor" not in ar_content:
        ar_content = ar_content.replace(
            "com.assistant.adapter.smartassist.contributors.SmartAssistUltimateCorrectorContributor\n                    )",
            "com.assistant.adapter.smartassist.contributors.SmartAssistUltimateCorrectorContributor,\n                        com.assistant.adapter.smartassist.contributors.SharpTouchTimingContributor,\n                        com.assistant.adapter.smartassist.contributors.KickingPostureContributor\n                    )"
        )
        changed = True
        
    if changed:
        with open(ar_path, "w") as f:
            f.write(ar_content)
        print("PATCHED: AppContributorRegistration.kt")
    else:
        print("ALREADY PATCHED or MISMATCH: AppContributorRegistration.kt")

print("\n[SUCCESS] Patch execution complete.")
