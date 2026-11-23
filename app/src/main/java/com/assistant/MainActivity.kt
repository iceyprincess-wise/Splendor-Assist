package com.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ComponentName
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.Manifest
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.assistant.controlroom.AdapterControlRoomRegistry
import com.assistant.controlroom.ui.FutureRoomsActivity
import com.assistant.controlroom.ui.AgentHubActivity
import com.assistant.controlroom.ui.GameplayRoomActivity
import com.assistant.controlroom.ui.DiagnosisRoomActivity
import com.assistant.controlroom.ui.NotificationRoomActivity
import com.assistant.controlroom.ui.GoalkeeperControlRoomActivity
import com.assistant.controlroom.ui.InterceptionControlRoomActivity
import com.assistant.controlroom.ui.SmartAssistControlRoomActivity
import com.assistant.SmartAssistRepository
import com.assistant.RuntimePerformanceCoordinator
import com.assistant.RuntimeDiagnosticsRegistry
import com.assistant.RuntimeVisualizationRegistry
import com.assistant.FPSMonitor
import com.assistant.VisionLatencyMonitor
import com.assistant.ConfidenceHeatmap
import com.assistant.compliance.ComplianceState

class MainActivity : AppCompatActivity() {

    private enum class PermissionStage {
        BATTERY,
        AUTOSTART_WAIT,
        ACCESSIBILITY,
        OVERLAY,
        ALL_FILES,
        NOTIFICATION,
        MEDIA_PROJECTION,
        COMPLETE
    }

    private var permissionStage = PermissionStage.BATTERY

    companion object {
        // Batch booster: READ_PHONE_STATE is asked once per process in the
        // notification stage. A denial never blocks the pipeline and is
        // never re-asked (the booster then runs with signal unknown and
        // logs the Settings path once).
        private var phonePermissionAskedOnce = false

        // F-AUTOSTART-1: keys for the permission-pipeline state saved
        // across the settings round-trip (see onSaveInstanceState).
        private const val KEY_PIPELINE_ACTIVE = "permissionPipelineActive"
        private const val KEY_PERMISSION_STAGE = "permissionStage"

        // F-PERMS2-1: the autostart confirm is ONE-TIME — Ed confirms
        // once and is never asked again. Persisted in SharedPreferences
        // (not the instance-state bundle) so it survives process death,
        // app restarts and any re-entry into AUTOSTART_WAIT.
        private const val PREFS_NAME = "splendor_permissions"
        private const val KEY_AUTOSTART_CONFIRMED = "autostartConfirmed"
    }

    private val hubRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hubRefreshTick = object : Runnable {
        override fun run() {
            refreshRuntimeHub()
            // UPGRADE: Increased from 1000L to 3000L. 
            // Polling 8 engine registries every 1 second creates massive String/Map 
            // allocations on the Main Thread, triggering GC pauses that stutter 
            // eFootball 2027. 3 seconds provides smooth UI updates without CPU theft.
            // no59 ITEM 4: STOPPED — the 3s live refresh no longer re-arms.
            // InAppLiveDisplay is the single switch (default OFF): with it
            // off the hub refreshes exactly once, when the tick is first
            // posted, and then only when the user re-opens the screen.
            // File logging is untouched by this flag.
            if (com.assistant.diagnostic.InAppLiveDisplay.liveRefreshAllowed()) {
                hubRefreshHandler.postDelayed(this, 3000L)
            }
        }
    }

    private var permissionPipelineActive = false
    private var projectionRecoveryFlow = false


    private lateinit var projectionManager: MediaProjectionManager

    private val screenCaptureLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                permissionPipelineActive = false


                // EngineData now safely handles WeakReferences to prevent Context leaks
                EngineData.code = result.resultCode
                EngineData.intent = result.data

                val recoveryAccepted = projectionRecoveryFlow
                projectionRecoveryFlow = false

                val serviceIntent =
                    Intent(this, OverlayService::class.java).apply {
                        putExtra("CROSS_PROCESS_CODE", result.resultCode)
                        putExtra("CROSS_PROCESS_DATA", result.data)
                        putExtra("CROSS_PROCESS_RECOVERY", recoveryAccepted)
                    }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                Toast.makeText(this, "Engine Linked", Toast.LENGTH_LONG).show()

            } else {
                permissionPipelineActive = false
                permissionStage = PermissionStage.COMPLETE
                Toast.makeText(this, "MediaProjection permission cancelled", Toast.LENGTH_SHORT).show()

            }
        }

    // Batch 6b VERDICT — PingEliminatorVpnService REMOVED, do not
    // re-add a start path here. The service was a real VpnService that
    // captured ALL traffic (route 0.0.0.0/0) and wrote every TUN packet
    // back to the same fd — no socket, no server, no forwarding, no
    // protect() — so it could not reduce ping or pick a route, and its
    // "Network latency masking enabled" notification was false. A real
    // upstream needs a server decision from Ed (not invented here), so
    // the tunnel goes instead of being faked. The consent launcher,
    // the REQUEST_VPN_CONSENT resume branch and the manifest entry
    // are all gone with it; the class itself is deleted.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GlobalCrashHandler.install(this)

        // F-AUTOSTART-1: restore the permission-pipeline state if the
        // process died while Ed was in a settings page (HyperOS kills
        // background apps aggressively, and this app has no battery
        // exemption or autostart yet at that point). Without this the
        // pipeline was silently dead on return and the app just sat
        // there — "the app will just off". The bundle is only delivered
        // when the system recreates this activity after it was stopped,
        // never when the user swipes the app away, so this cannot
        // auto-resume the pipeline on a fresh launch.
        savedInstanceState?.let { state ->
            permissionPipelineActive = state.getBoolean(KEY_PIPELINE_ACTIVE, false)
            runCatching {
                permissionStage = PermissionStage.valueOf(
                    state.getString(KEY_PERMISSION_STAGE) ?: PermissionStage.BATTERY.name
                )
            }
        }

        // UI-1: edge-to-edge (targetSdk 36 enforces it) — draw behind the
        // system bars; the insets are applied to dashboardRoot below so
        // content is never hidden behind the status / navigation bars.
        enableEdgeToEdge()
        setContentView(com.assistant.overlay.R.layout.activity_main)
        applyDashboardInsets()

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        bindHomeButtons()

        refreshRoomBulbs()
        refreshRuntimeHub()
    }

    // UI-1: edge-to-edge insets — the ScrollView root gets the system-bars
    // insets as padding (top = status bar, bottom = navigation bar) so no
    // dashboard content sits under a system bar. The inner content keeps
    // its own 24dp padding; this only adds the bars.
    private fun applyDashboardInsets() {
        val root = findViewById<android.view.View>(com.assistant.overlay.R.id.dashboardRoot) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun synchronizeApplicationRuntime() {
        runCatching { RuntimePerformanceCoordinator.synchronizeExistingPerformanceEngines() }
        runCatching { RuntimePerformanceCoordinator.synchronizeRuntimePipeline() }
        runCatching { RuntimeDiagnosticsRegistry.refresh() }
        runCatching { RuntimeVisualizationRegistry.refresh() }
        runCatching { FPSMonitor.refresh() }
        runCatching { ConfidenceHeatmap.refresh() }
    }

    override fun onResume() {
        super.onResume()

        // Batch 6b: the REQUEST_VPN_CONSENT branch is gone with
        // PingEliminatorVpnService (see the verdict note above) — no
        // in-repo code can set that extra any more, so a stale extra
        // from an old install is ignored instead of starting a tunnel.

        if (intent?.getBooleanExtra("REQUEST_MEDIA_PROJECTION_RECOVERY", false) == true) {
            intent.removeExtra("REQUEST_MEDIA_PROJECTION_RECOVERY")

            projectionRecoveryFlow = true
            permissionPipelineActive = false
            permissionStage = PermissionStage.MEDIA_PROJECTION

            projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
            return
        }

        if (permissionPipelineActive) {
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
        }

        synchronizeApplicationRuntime()
        refreshRoomBulbs()
        
        hubRefreshHandler.removeCallbacks(hubRefreshTick)
        hubRefreshHandler.post(hubRefreshTick)
    }

    private fun bindHomeButtons() {
        findViewById<Button>(com.assistant.overlay.R.id.btnStartEngine).setOnClickListener {
            permissionPipelineActive = true
            checkBatteryAndProceed()
        }

        findViewById<Button>(com.assistant.overlay.R.id.btnStopEngine).setOnClickListener {
            permissionPipelineActive = false
            // L4 (batch 1): teardown attribution — the Stop tap is a user
            // teardown event; record it BEFORE shutdown() so the TEARDOWN
            // line can correlate this shutdown to the tap.
            com.assistant.TeardownAttributionEngine.noteUserStop("MainActivity.btnStopEngine")
            stopService(Intent(this, OverlayService::class.java))
            com.assistant.RuntimeCoordinator.shutdown(caller = "MainActivity.btnStopEngine")
            refreshRuntimeHub()
        }

        // Log viewer removed: /sdcard/Splendor-Assist is the only log destination.

        findViewById<View>(com.assistant.overlay.R.id.cardSmartAssist).setOnClickListener {
            startActivity(Intent(this, SmartAssistControlRoomActivity::class.java))
        }

        findViewById<View>(com.assistant.overlay.R.id.cardGoalkeeper).setOnClickListener {
            startActivity(Intent(this, GoalkeeperControlRoomActivity::class.java))
        }

        findViewById<View>(com.assistant.overlay.R.id.cardInterception).setOnClickListener {
            startActivity(Intent(this, InterceptionControlRoomActivity::class.java))
        }

        fun openFutureRoom() {
            startActivity(Intent(this, FutureRoomsActivity::class.java))
        }

        findViewById<View>(com.assistant.overlay.R.id.cardOverlay).setOnClickListener { startActivity(Intent(this, GameplayRoomActivity::class.java)) }
        findViewById<View>(com.assistant.overlay.R.id.cardAccessibility).setOnClickListener { startActivity(Intent(this, AgentHubActivity::class.java)) }
        findViewById<View>(com.assistant.overlay.R.id.cardMediaProjection).setOnClickListener {
            // F-PERMS-4: arm the pipeline — without this, onResume never
            // re-enters after the first settings page and the flow stalls.
            permissionPipelineActive = true
            checkBatteryAndProceed()
        }
        // UI-1: cardDiagnostics removed (duplicate of cardOverlay). The
        // Future Rooms card is now Settings (opens FutureRoomsActivity
        // for now; the full Settings page is a later piece).
        findViewById<View>(com.assistant.overlay.R.id.cardSettings).setOnClickListener { openFutureRoom() }
        // UI-2: Diagnosis Room (live engine/file status) and Notification
        // Room (app issues; the badge shows how many there are).
        findViewById<View>(com.assistant.overlay.R.id.cardDiagnosis).setOnClickListener {
            startActivity(Intent(this, DiagnosisRoomActivity::class.java))
        }
        findViewById<View>(com.assistant.overlay.R.id.cardNotifications).setOnClickListener {
            startActivity(Intent(this, NotificationRoomActivity::class.java))
        }
    }

    private fun refreshRoomBulbs() {
        // Project live runtime state into the registry before reading it,
        // so these bulbs reflect reality instead of the bootstrap snapshot.
        // D-R5 (batch 5): the old code discarded snapshot()'s return and
        // re-read the registry (and SmartAssistRepository) directly. The
        // bulbs now drive from the returned list — same values, one read.
        val rooms = com.assistant.controlroom.AdapterControlRoomSync.snapshot()
        val smart = rooms.find { it.adapterId == "smart_assist" }
        val smartReady = smart?.enabled ?: SmartAssistRepository.enabled()
        setBulb(
            com.assistant.overlay.R.id.tvSmartAssistBulb,
            com.assistant.overlay.R.id.tvSmartAssistState,
            smartReady,
            if (smartReady) "READY ${smart?.intensity ?: 50}%" else "LOCKED"
        )

        val goalkeeper = rooms.find { it.adapterId == "goalkeeper" }
        val goalkeeperReady = goalkeeper?.enabled ?: (AdapterControlRoomRegistry.get("goalkeeper")?.enabled == true)
        setBulb(
            com.assistant.overlay.R.id.tvGoalkeeperBulb,
            com.assistant.overlay.R.id.tvGoalkeeperState,
            goalkeeperReady,
            if (goalkeeperReady) "ACTIVE ${goalkeeper?.intensity ?: 50}%" else "OFF"
        )

        val interception = rooms.find { it.adapterId == "interception" }
        val interceptionReady = interception?.enabled ?: (AdapterControlRoomRegistry.get("interception")?.enabled == true)
        setBulb(
            com.assistant.overlay.R.id.tvInterceptionBulb,
            com.assistant.overlay.R.id.tvInterceptionState,
            interceptionReady,
            if (interceptionReady) "ACTIVE ${interception?.intensity ?: 50}%" else "OFF"
        )

    }

    private fun setBulb(bulbId: Int, stateId: Int, active: Boolean, label: String) {
        val bulb = findViewById<android.widget.TextView>(bulbId)
        val state = findViewById<android.widget.TextView>(stateId)

        bulb.setTextColor(
            if (active) "#00E676".toColorInt()
            else "#C7D2E5".toColorInt()
        )
        state.text = label
    }

    private fun launchIfExists(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun openBatteryOptimizationManager(): Boolean {
        permissionStage = PermissionStage.AUTOSTART_WAIT

        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()

        // F-PERMS-3: the direct exemption request FIRST — it opens the
        // system allow dialog for this exact app (the page
        // ComplianceState.battery() checks). The old code only tried the
        // MIUI autostart activities and the system app LIST, so Ed landed
        // on a list and had to find the app by hand. Only when the
        // exemption is not yet granted; the "Open Settings Again" path
        // (battery already granted) goes straight to the vendor pages.
        if (!ComplianceState.battery(this)) {
            val direct = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:$packageName".toUri()
            )
            if (launchIfExists(direct)) return true
        }

        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || 
            manufacturer.contains("poco") || brand.contains("xiaomi") || 
            brand.contains("redmi") || brand.contains("poco")) {

            val vendorIntents = listOf(
                Intent().apply {
                    component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                },
                Intent().apply {
                    component = ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")
                },
                // Battery-page fix: the explicit AOSP app-info activity
                // opens the NEW-style editable page (battery usage can be
                // changed there). Ed confirmed in Termux that
                // InstalledAppDetails opens it; the generic
                // ACTION_APPLICATION_DETAILS_SETTINGS below resolves to
                // the OLD MIUI page, where the battery setting cannot be
                // edited. Tried before the generic intent; launchIfExists
                // falls through if the component is absent.
                Intent().apply {
                    component = ComponentName("com.android.settings", "com.android.settings.applications.InstalledAppDetails")
                    data = "package:$packageName".toUri()
                },
                Intent().apply {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = "package:$packageName".toUri()
                }
            )

            vendorIntents.forEach {
                if (launchIfExists(it)) return true
            }
        }

        val fallback = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            // Battery-page fix: explicit AOSP app-info activity first (see
            // the vendor list above — it opens the NEW-style editable
            // page); the generic intent below it is the fallback.
            Intent().apply {
                component = ComponentName("com.android.settings", "com.android.settings.applications.InstalledAppDetails")
                data = "package:$packageName".toUri()
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
        )

        fallback.forEach {
            if (launchIfExists(it)) return true
        }

        return false
    }

    private fun checkBatteryAndProceed() {
        permissionStage = PermissionStage.BATTERY
        try {
            if (ComplianceState.battery(this)) {
                com.assistant.RuntimeCoordinator.reportPermissionsVerified()
                checkAccessibilityAndProceed()
                return
            }

            if (!openBatteryOptimizationManager()) {
                checkAccessibilityAndProceed()
            }
        } catch (_: Exception) {
            checkAccessibilityAndProceed()
        }
    }

    private fun checkAccessibilityAndProceed() {
        permissionStage = PermissionStage.ACCESSIBILITY
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val expectedService = "com.assistant.SmartAssistAccessibilityEngine"

        if (!enabled.contains(expectedService, true) && !enabled.contains(packageName, true)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        checkOverlayAndProceed()
    }

    private fun checkNotificationAndProceed() {
        permissionStage = PermissionStage.NOTIFICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9001)
            return
        }

        checkPhoneStateAndProceed()
    }

    private fun checkPhoneStateAndProceed() {
        // Batch booster: ask for READ_PHONE_STATE once per process so the
        // free booster can read real signal level/dBm. This check sits
        // AFTER the notification gate, so a denial here can never loop
        // the pipeline: onResume re-enters by stage, the flag is already
        // set, and the flow proceeds to media projection.
        if (!phonePermissionAskedOnce &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            phonePermissionAskedOnce = true
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 9002)
            return
        }

        checkMediaProjectionAndProceed()
    }

    private fun checkMediaProjectionAndProceed() {
        permissionStage = PermissionStage.MEDIA_PROJECTION

        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun checkOverlayAndProceed() {
        permissionStage = PermissionStage.OVERLAY

        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
            return
        }
        checkAllFilesAndProceed()
    }

    private fun checkAllFilesAndProceed() {
        permissionStage = PermissionStage.ALL_FILES

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
            val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri())
            try {
                startActivity(appIntent)
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                    checkNotificationAndProceed()
                }
            }
            return
        }

        // S17-D3 (batch 5): DeathWatch.install() is guarded — it returns
        // early when storage is not ready, and App.onCreate only calls it
        // once, so a phone that boots before all-files access is granted
        // never arms the death watcher. All-files access is confirmed on
        // this path (the missing-permission branch returned above), so
        // retry the install here: initialize() re-checks the permission
        // and install() is idempotent (returns at once if already armed).
        // Guarded so a storage failure can never break the permission
        // flow. Note: the :survival process has no activity to retry
        // from — its install attempt stays one-shot (App.kt:179).
        try {
            com.assistant.storage.SplendorStorageRoot.initialize()
            DeathWatch.install(this)
        } catch (_: Throwable) {}

        checkNotificationAndProceed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 9001) {
            // F-PERMS-1: re-enter the notification stage when granted, so
            // the READ_PHONE_STATE prompt (9002) still comes up — the old
            // code jumped straight to media projection, so 9002 never
            // appeared when the notification prompt was shown first.
            // When denied, go to the phone-state gate directly: re-entering
            // checkNotificationAndProceed would re-prompt 9001 forever.
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                checkNotificationAndProceed()
            } else {
                checkPhoneStateAndProceed()
            }
        }

        if (requestCode == 9002) {
            // F-PERMS-2: 9002 was unhandled — the flow only recovered via
            // onResume re-entry. Advance explicitly.
            checkMediaProjectionAndProceed()
        }
    }

    private fun showAutoStartConfirmation() {
        // F-PERMS2-1: one-time confirm — if Ed already confirmed, never
        // show this dialog again; go straight to the next stage.
        if (getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTOSTART_CONFIRMED, false)) {
            permissionStage = PermissionStage.ACCESSIBILITY
            checkAccessibilityAndProceed()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Background Auto Start")
            .setMessage("Before continuing, please confirm that you enabled:\n\n• Auto Start\n• Background Activity\n• No Restrictions (if available)")
            .setCancelable(false)
            .setPositiveButton("Done") { _, _ ->
                // F-PERMS2-1: persist the confirm — this dialog is never
                // shown again after the first "Done".
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean(KEY_AUTOSTART_CONFIRMED, true)
                }
                permissionStage = PermissionStage.ACCESSIBILITY
                checkAccessibilityAndProceed()
            }
            .setNegativeButton("Open Settings Again") { _, _ ->
                permissionStage = PermissionStage.AUTOSTART_WAIT
                openBatteryOptimizationManager()
            }
            .show()
    }

    // UI-1: the dashboard bulbs. Same live sources the old monospace dump
    // read (RuntimeCoordinator.runtimeState, RuntimeHealthMonitor.
    // runtimeHealthSnapshot, GameplayEngineRegistry.registryRuntimeSnapshot)
    // — the values are identical, only the presentation changed from a
    // key=value dump to bulbs. Runs on the 3s hub tick (when live refresh
    // is allowed) and on resume, like the dump did. NOTE: the old dump
    // also surfaced ControlMappingTrainer + EventHubs snapshots; those no
    // longer appear on the dashboard (they remain in the file logs).
    private fun refreshRuntimeHub() {
        val runtime = com.assistant.RuntimeCoordinator.runtimeState()
        val health = com.assistant.RuntimeHealthMonitor.runtimeHealthSnapshot()
        val registry = com.assistant.runtime.GameplayEngineRegistry.registryRuntimeSnapshot()
        renderRuntimeBulbs(runtime)
        renderHealthBulbs(health)
        renderRegistryBulbs(registry)
        // UI-2: the Notifications badge and the Diagnosis card subtitle
        // read the SAME health snapshot the HEALTH bulbs just rendered —
        // one evaluation per tick, and the badge always agrees with the
        // Notification Room (same degraded-reasons source).
        updateIssueBadges(health)
    }

    // UI-2: the Notifications badge is the count of active issues (the
    // health monitor's degraded reasons); the Diagnosis card subtitle
    // shows the same count, or "All live" when there are none. Hidden
    // badge when zero — no fake notifications.
    private fun updateIssueBadges(health: Map<String, Any>) {
        val reasons = (health["degradedReasons"] as? String ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val count = reasons.size
        val badge = findViewById<android.widget.TextView>(com.assistant.overlay.R.id.tvNotificationsBadge)
        if (count > 0) {
            badge.text = "$count"
            badge.visibility = android.view.View.VISIBLE
        } else {
            badge.visibility = android.view.View.GONE
        }
        val diagnosisState = findViewById<android.widget.TextView>(com.assistant.overlay.R.id.tvDiagnosisState)
        if (count > 0) {
            diagnosisState.text = "$count issue${if (count == 1) "" else "s"}"
            diagnosisState.setTextColor("#FF5252".toColorInt())
        } else {
            diagnosisState.text = getString(com.assistant.overlay.R.string.diagnosis_card_subtitle)
            diagnosisState.setTextColor(themeColor(android.R.attr.textColorSecondary))
        }
    }

    // UI-1 bulb model: GREEN = true/alive (or a live counter > 0),
    // YELLOW = false/waiting (or a counter at 0), RED = an issue — a
    // health value that is false AND has a matching degraded reason.
    // Nothing here is hardcoded: every value comes from the live
    // snapshot maps passed in.
    private enum class BulbState { GREEN, YELLOW, RED }

    private data class BulbEntry(val label: String, val state: BulbState, val value: String)

    private fun bulbColor(state: BulbState): Int = when (state) {
        BulbState.GREEN -> "#00E676".toColorInt()
        BulbState.YELLOW -> "#FFC107".toColorInt()
        BulbState.RED -> "#FF5252".toColorInt()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun renderBulbSection(containerId: Int, entries: List<BulbEntry>) {
        val container = findViewById<android.widget.LinearLayout>(containerId) ?: return
        container.removeAllViews()
        entries.chunked(2).forEach { row ->
            val rowLayout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4); bottomMargin = dp(4) }
            }
            row.forEach { entry -> rowLayout.addView(buildBulbCell(entry)) }
            if (row.size == 1) {
                rowLayout.addView(android.view.View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            container.addView(rowLayout)
        }
    }

    private fun buildBulbCell(entry: BulbEntry): android.widget.LinearLayout {
        val cell = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(4); marginEnd = dp(4) }
            setBackgroundResource(com.assistant.overlay.R.drawable.panel_surface)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val top = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val dot = android.widget.TextView(this).apply {
            text = "●"
            textSize = 14f
            setTextColor(bulbColor(entry.state))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }
        val label = android.widget.TextView(this).apply {
            text = entry.label
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(android.R.attr.textColorPrimary))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        top.addView(dot)
        top.addView(label)
        val value = android.widget.TextView(this).apply {
            text = entry.value
            textSize = 11f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }
        cell.addView(top)
        cell.addView(value)
        return cell
    }

    private fun renderRuntimeBulbs(runtime: Map<String, Any>) {
        fun bool(key: String, label: String): BulbEntry {
            val v = runtime[key] as? Boolean ?: false
            return BulbEntry(label, if (v) BulbState.GREEN else BulbState.YELLOW, if (v) "Ready" else "Waiting")
        }
        val pending = (runtime["busPending"] as? Number)?.toLong() ?: 0L
        renderBulbSection(
            com.assistant.overlay.R.id.runtimeBulbGrid,
            listOf(
                bool("permissionsVerified", "Permissions"),
                bool("accessibilityReady", "Accessibility"),
                bool("captureReady", "Capture"),
                bool("boosterReady", "Booster"),
                bool("enginesWarm", "Engines warm"),
                bool("busEnabled", "Bus"),
                bool("runtimeReady", "Runtime ready"),
                BulbEntry("Bus pending", if (pending == 0L) BulbState.GREEN else BulbState.YELLOW, "$pending pending")
            )
        )
    }

    private fun renderHealthBulbs(health: Map<String, Any>) {
        val reasons = health["degradedReasons"]?.toString() ?: ""
        fun alive(key: String, label: String, reason: String): BulbEntry {
            val v = health[key] as? Boolean ?: false
            val state = when {
                v -> BulbState.GREEN
                reason.isNotEmpty() && reasons.contains(reason) -> BulbState.RED
                else -> BulbState.YELLOW
            }
            val value = when (state) {
                BulbState.GREEN -> "Alive"
                BulbState.RED -> "Issue"
                BulbState.YELLOW -> "Waiting"
            }
            return BulbEntry(label, state, value)
        }
        val evaluations = (health["evaluations"] as? Number)?.toLong() ?: 0L
        renderBulbSection(
            com.assistant.overlay.R.id.healthBulbGrid,
            listOf(
                alive("accessibilityAlive", "Accessibility", "accessibility-not-ready"),
                alive("overlayAlive", "Overlay", "overlay"),
                alive("frameAlive", "Frame", "frame-stale"),
                alive("decisionAlive", "Decision", "decision-stale"),
                alive("busAlive", "Bus", "bus-idle"),
                alive("dispatchAlive", "Dispatch", "dispatch"),
                alive("gameplayAlive", "Gameplay", "gameplay-idle"),
                alive("boosterAlive", "Booster", "booster-not-ready"),
                BulbEntry("Health checks", if (evaluations > 0) BulbState.GREEN else BulbState.YELLOW, "$evaluations checks")
            )
        )
    }

    private fun renderRegistryBulbs(registry: Map<String, Any>) {
        fun count(key: String, label: String): BulbEntry {
            val v = (registry[key] as? Number)?.toLong() ?: 0L
            return BulbEntry(label, if (v > 0) BulbState.GREEN else BulbState.YELLOW, "$v")
        }
        renderBulbSection(
            com.assistant.overlay.R.id.registryBulbGrid,
            listOf(
                count("engines", "Engines"),
                count("collectCycles", "Collect cycles"),
                count("contributed", "Contributed"),
                count("generation", "Generation"),
                count("collisions", "Collisions")
            )
        )
    }

    override fun onPause() {
        hubRefreshHandler.removeCallbacks(hubRefreshTick)
        super.onPause()
    }

    // F-AUTOSTART-1: save the permission-pipeline state so the flow
    // survives the settings round-trip even if HyperOS kills the
    // process while Ed is in a settings page. onResume re-enters the
    // stage machine from the restored stage, so the flow continues
    // instead of dying silently. When the pipeline completes (or the
    // user taps Stop) permissionPipelineActive is already false, so
    // nothing stale is ever restored.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PIPELINE_ACTIVE, permissionPipelineActive)
        outState.putString(KEY_PERMISSION_STAGE, permissionStage.name)
    }
}
