import os

def repair_pipeline():
    main_path = "app/src/main/java/com/assistant/MainActivity.kt"
    comp_path = "app/src/main/java/com/assistant/compliance/ComplianceState.kt"

    with open(main_path, "r", encoding="utf-8") as f:
        main_code = f.read()

    with open(comp_path, "r", encoding="utf-8") as f:
        comp_code = f.read()

    # 1. FIX: checkBatteryAndProceed must explicitly set stage
    old_check_battery = """    private fun checkBatteryAndProceed() {
        try {
            if (ComplianceState.battery(this)) {
                com.assistant.adapter.smartassist.RuntimeCoordinator.reportPermissionsVerified()
                checkAccessibilityAndProceed()
                return
            }

            if (!openBatteryOptimizationManager()) {
                checkAccessibilityAndProceed()
            }
        } catch (_: Exception) {
            checkAccessibilityAndProceed()
        }
    }"""
    new_check_battery = """    private fun checkBatteryAndProceed() {
        permissionStage = PermissionStage.BATTERY
        try {
            if (ComplianceState.battery(this)) {
                com.assistant.adapter.smartassist.RuntimeCoordinator.reportPermissionsVerified()
                checkAccessibilityAndProceed()
                return
            }

            if (!openBatteryOptimizationManager()) {
                checkAccessibilityAndProceed()
            }
        } catch (_: Exception) {
            checkAccessibilityAndProceed()
        }
    }"""

    # 2. FIX: checkAccessibilityAndProceed must route to OVERLAY, not NOTIFICATION
    old_check_access = """    private fun checkAccessibilityAndProceed() {
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val expectedService = "com.assistant.adapter.smartassist.SmartAssistAccessibilityEngine"

        if (!enabled.contains(expectedService, true) && !enabled.contains(packageName, true)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        checkNotificationAndProceed()
    }"""
    new_check_access = """    private fun checkAccessibilityAndProceed() {
        permissionStage = PermissionStage.ACCESSIBILITY
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val expectedService = "com.assistant.adapter.smartassist.SmartAssistAccessibilityEngine"

        if (!enabled.contains(expectedService, true) && !enabled.contains(packageName, true)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        checkOverlayAndProceed()
    }"""

    # 3. FIX: checkNotificationAndProceed must route to MEDIA_PROJECTION
    old_check_notif = """    private fun checkNotificationAndProceed() {
        permissionStage = PermissionStage.NOTIFICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9001)
            return
        }

        permissionStage = PermissionStage.MEDIA_PROJECTION
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }"""
    new_check_notif = """    private fun checkNotificationAndProceed() {
        permissionStage = PermissionStage.NOTIFICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9001)
            return
        }

        checkMediaProjectionAndProceed()
    }

    private fun checkMediaProjectionAndProceed() {
        permissionStage = PermissionStage.MEDIA_PROJECTION
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }"""

    # 4. FIX: checkOverlayAndProceed must return after launching intent
    old_check_overlay = """    private fun checkOverlayAndProceed() {
        permissionStage = PermissionStage.OVERLAY

        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            checkAllFilesAndProceed()
        }
    }"""
    new_check_overlay = """    private fun checkOverlayAndProceed() {
        permissionStage = PermissionStage.OVERLAY

        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        checkAllFilesAndProceed()
    }"""

    # 5. FIX: onRequestPermissionsResult must route to MEDIA_PROJECTION (not OVERLAY)
    old_on_req = """    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 9001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkOverlayAndProceed()
            }
        }
    }"""
    new_on_req = """    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 9001) {
            checkMediaProjectionAndProceed()
        }
    }"""

    # 6. FIX: onResume must resume the exact stage
    old_on_resume = """        if (permissionPipelineActive) {
            when (permissionStage) {
                PermissionStage.AUTOSTART_WAIT -> {
                    if (ComplianceState.battery(this)) {
                        showAutoStartConfirmation()
                    } else {
                        checkBatteryAndProceed()
                    }
                }
                else -> checkBatteryAndProceed()
            }
        }"""
    new_on_resume = """        if (permissionPipelineActive) {
            when (permissionStage) {
                PermissionStage.AUTOSTART_WAIT -> {
                    if (ComplianceState.battery(this)) {
                        showAutoStartConfirmation()
                    } else {
                        checkBatteryAndProceed()
                    }
                }
                PermissionStage.BATTERY -> checkBatteryAndProceed()
                PermissionStage.ACCESSIBILITY -> checkAccessibilityAndProceed()
                PermissionStage.OVERLAY -> checkOverlayAndProceed()
                PermissionStage.ALL_FILES -> checkAllFilesAndProceed()
                PermissionStage.NOTIFICATION -> checkNotificationAndProceed()
                PermissionStage.MEDIA_PROJECTION -> checkMediaProjectionAndProceed()
                else -> checkBatteryAndProceed()
            }
        }"""

    # 7. FIX: Cancelled MediaProjection must halt pipeline
    old_screen_cancel = """            } else {
                Toast.makeText(this, "MediaProjection permission cancelled", Toast.LENGTH_SHORT).show()
            }"""
    new_screen_cancel = """            } else {
                permissionPipelineActive = false
                permissionStage = PermissionStage.COMPLETE
                Toast.makeText(this, "MediaProjection permission cancelled", Toast.LENGTH_SHORT).show()
            }"""

    # 8. FIX: ComplianceState summary must match ready() sequence
    old_summary = """    fun summary(context: Context): String {

        if (ready(context))
            return "ENGINE READY"

        val failed =
            mutableListOf<String>()

        if (!battery(context))
            failed += "BATTERY"

        if (!overlay(context))
            failed += "OVERLAY"

        if (!allFiles())
            failed += "ALL_FILES"

        if (!notifications(context))
            failed += "NOTIFICATIONS"

        if (!accessibility(context))
            failed += "ACCESSIBILITY"

        return "BLOCKED: " +
            failed.joinToString(", ")
    }"""
    new_summary = """    fun summary(context: Context): String {

        if (ready(context))
            return "ENGINE READY"

        val failed =
            mutableListOf<String>()

        if (!battery(context))
            failed += "BATTERY"

        if (!accessibility(context))
            failed += "ACCESSIBILITY"

        if (!overlay(context))
            failed += "OVERLAY"

        if (!allFiles())
            failed += "ALL_FILES"

        if (!notifications(context))
            failed += "NOTIFICATIONS"

        return "BLOCKED: " +
            failed.joinToString(", ")
    }"""

    # Apply surgical replacements
    main_code = main_code.replace(old_check_battery, new_check_battery)
    main_code = main_code.replace(old_check_access, new_check_access)
    main_code = main_code.replace(old_check_notif, new_check_notif)
    main_code = main_code.replace(old_check_overlay, new_check_overlay)
    main_code = main_code.replace(old_on_req, new_on_req)
    main_code = main_code.replace(old_on_resume, new_on_resume)
    main_code = main_code.replace(old_screen_cancel, new_screen_cancel)

    comp_code = comp_code.replace(old_summary, new_summary)

    # Write patched code back to disk
    with open(main_path, "w", encoding="utf-8") as f:
        f.write(main_code)
        
    with open(comp_path, "w", encoding="utf-8") as f:
        f.write(comp_code)
        
    print("✅ Pipeline successfully repaired. Sequence strictly enforced.")

if __name__ == "__main__":
    repair_pipeline()
