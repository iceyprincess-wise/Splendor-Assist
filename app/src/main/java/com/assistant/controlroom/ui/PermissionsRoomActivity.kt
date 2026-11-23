package com.assistant.controlroom.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.assistant.compliance.ComplianceState

/**
 * Permissions Room (F-PERMS3) — the one place that lists EVERY
 * permission the app needs, with granted / missing status read from
 * the real APIs, and a tap on a missing row opens that permission's
 * own settings page.
 *
 * Why this screen exists: the system's App info → App permissions page
 * only ever lists runtime permissions, and this app declares only two
 * (Notifications, Phone). The other permissions are SPECIAL permissions
 * (All files access, Display over other apps, Battery optimisation,
 * Install unknown apps, Alarms & reminders) plus the Accessibility
 * service — Android puts those under "Special permissions" / the
 * Accessibility settings, never on the App permissions page, and no app
 * can change that page. Ed kept having to hunt through Settings to
 * enable All files access so the app can write logs. This room is the
 * fix: every permission, one list, tap to fix.
 *
 * It does NOT start the engine and does NOT run the permission
 * pipeline (MainActivity's BATTERY → … → MEDIA_PROJECTION chain):
 * the pipeline's terminal step launches media projection, so running
 * it here would auto-start the engine. Each row opens only its own
 * settings page (or the runtime-permission prompt). Returning from a
 * settings page re-reads the live state in onResume.
 *
 * Status vocabulary (same bulb convention as the dashboard):
 *   GRANTED — the real API says the permission is held (green).
 *   MISSING — the real API says it is not held (yellow = waiting).
 * Nothing is hardcoded: every row re-reads its API on open, on
 * resume, and on manual Refresh.
 */
class PermissionsRoomActivity : AppCompatActivity() {

    private enum class PermStatus { GRANTED, MISSING }

    private data class PermEntry(
        val name: String,
        val what: String,
        val status: PermStatus,
        val fix: () -> Unit
    )

    private var listContainer: LinearLayout? = null
    private var summaryView: TextView? = null

    // F-PERMS3: runtime permissions (Notifications, Phone) are asked
    // with this launcher, self-contained in this screen — MainActivity's
    // onRequestPermissionsResult (9001/9002) belongs to the pipeline
    // and is not touched. Any result re-reads the live state.
    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshNow()
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun statusColor(status: PermStatus): Int = when (status) {
        PermStatus.GRANTED -> ThemeTokens.SUCCESS
        // Same yellow as the Diagnosis Room's WIRED: not yet active.
        PermStatus.MISSING -> "#FFC107".toColorInt()
    }

    private fun statusLabel(status: PermStatus): String = when (status) {
        PermStatus.GRANTED -> "GRANTED"
        PermStatus.MISSING -> "MISSING"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        buildUi()
        refreshNow()
    }

    override fun onResume() {
        super.onResume()
        // F-PERMS3: the settings round-trip is the whole point of this
        // screen — Ed taps a row, toggles in Settings, comes back, and
        // must see GRANTED. Re-read on resume (no timer, no loop).
        refreshNow()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(20), dp(14), dp(40))
            setBackgroundColor(ThemeTokens.BG)
        }

        val titleTv = TextView(this).apply {
            text = "🔐  PERMISSIONS"; textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(titleTv)

        val subTv = TextView(this).apply {
            text = "Every permission the app needs · tap a missing one to open its settings page · the system's App info page only lists Notifications and Phone, the rest are here"
            textSize = 10f; setTextColor(ThemeTokens.TEXT_MUTED)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(subTv)

        summaryView = TextView(this).apply {
            text = "Loading…"; textSize = 12f
            setTextColor(ThemeTokens.TEXT_MUTED)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(summaryView)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(listContainer)

        val refreshBtn = Button(this).apply {
            text = "↺  Refresh Now"; isAllCaps = false
            setBackgroundColor(ThemeTokens.BUTTON)
            setTextColor(Color.WHITE); textSize = 13f
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(12), 0, dp(6)); layoutParams = lp
            setOnClickListener { refreshNow() }
        }
        root.addView(refreshBtn)

        val backBtn = Button(this).apply {
            text = "←  Back"; isAllCaps = false
            setBackgroundColor(ThemeTokens.SURFACE)
            setTextColor(Color.WHITE); textSize = 13f
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(6), 0, dp(6)); layoutParams = lp
            setOnClickListener { finish() }
        }
        root.addView(backBtn)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(ThemeTokens.BG)
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    private fun section(title: String) {
        val container = listContainer ?: return
        val tv = TextView(this).apply {
            text = title; textSize = 13f
            setTextColor(ThemeTokens.ACCENT_TEXT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }
        container.addView(tv)
        container.addView(android.view.View(this).apply {
            setBackgroundColor(ThemeTokens.SURFACE)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            lp.setMargins(0, 0, 0, dp(6)); layoutParams = lp
        })
    }

    private fun box(entry: PermEntry) {
        val container = listContainer ?: return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ThemeTokens.SURFACE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(6)); layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (entry.status == PermStatus.MISSING) {
                    entry.fix()
                } else {
                    Toast.makeText(
                        this@PermissionsRoomActivity,
                        "${entry.name}: already granted",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        val dot = TextView(this).apply {
            text = "●"; textSize = 14f
            setTextColor(statusColor(entry.status))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(8); layoutParams = lp
        }
        row.addView(dot)
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        mid.addView(TextView(this).apply {
            text = entry.name; textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ThemeTokens.TEXT)
        })
        mid.addView(TextView(this).apply {
            text = entry.what; textSize = 11f
            setTextColor(ThemeTokens.TEXT_MUTED)
        })
        row.addView(mid)
        row.addView(TextView(this).apply {
            text = statusLabel(entry.status); textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(statusColor(entry.status))
        })
        container.addView(row)
    }

    // ── Real-state checks (one per row; a failing check reads MISSING,
    // never crashes the screen) ──────────────────────────────────────

    private fun allFilesGranted(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // Pre-R storage is granted by the legacy permission.
        }
    } catch (_: Throwable) { false }

    private fun overlayGranted(): Boolean = try {
        Settings.canDrawOverlays(this)
    } catch (_: Throwable) { false }

    private fun batteryGranted(): Boolean = try {
        ComplianceState.battery(this)
    } catch (_: Throwable) { false }

    private fun installGranted(): Boolean = try {
        packageManager.canRequestPackageInstalls()
    } catch (_: Throwable) { false }

    private fun alarmsGranted(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else {
            true // No exact-alarm gate below S.
        }
    } catch (_: Throwable) { false }

    private fun accessibilityGranted(): Boolean = try {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        // Same check as MainActivity.checkAccessibilityAndProceed().
        enabled.contains("com.assistant.SmartAssistAccessibilityEngine", true) ||
            enabled.contains(packageName, true)
    } catch (_: Throwable) { false }

    private fun notificationsGranted(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // No runtime notification permission below TIRAMISU.
        }
    } catch (_: Throwable) { false }

    private fun phoneGranted(): Boolean = try {
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    // ── Tap-to-fix: the same intents the pipeline already uses ──────

    private fun openSettings(intent: Intent, fallback: Intent? = null) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                if (fallback != null) startActivity(fallback)
                else throw IllegalStateException("no fallback")
            } catch (_: Exception) {
                Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fixAllFiles() = openSettings(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri()),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    )

    private fun fixOverlay() = openSettings(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
    )

    private fun fixBattery() = openSettings(
        // F-PERMS-3 first try: the direct allow dialog.
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    )

    private fun fixInstall() = openSettings(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri())
    )

    private fun fixAlarms() = openSettings(
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:$packageName".toUri())
    )

    private fun fixAccessibility() = openSettings(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    )

    private fun fixNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runtimePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun fixPhone() {
        runtimePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
    }

    private fun refreshNow() {
        val container = listContainer ?: return
        container.removeAllViews()
        try {
            fun entry(
                name: String,
                what: String,
                granted: Boolean,
                fix: () -> Unit
            ): PermEntry {
                val status = if (granted) PermStatus.GRANTED else PermStatus.MISSING
                return PermEntry(name, what, status, fix)
            }

            // STORAGE first, front and centre: Ed's explicit requirement.
            // Without it the app cannot write logs to storage.
            section("STORAGE — NEEDED FOR LOGS")
            var grantedCount = 0
            var missingCount = 0
            fun tally(granted: Boolean) {
                if (granted) grantedCount++ else missingCount++
            }

            val allFiles = allFilesGranted()
            tally(allFiles)
            box(entry(
                "All files access",
                "Lets the app read and write logs and files on storage",
                allFiles, ::fixAllFiles
            ))

            // ALL PERMISSIONS — the full list Ed asked for.
            section("ALL PERMISSIONS")
            val rows = listOf(
                entry("Display over other apps", "Draws the overlay on top of the game", overlayGranted(), ::fixOverlay),
                entry("Battery optimisation off", "Keeps the app alive in the background", batteryGranted(), ::fixBattery),
                entry("Install unknown apps", "Lets the app install its own updates", installGranted(), ::fixInstall),
                entry("Alarms & reminders", "Exact alarms for update reminders", alarmsGranted(), ::fixAlarms),
                entry("Accessibility service", "Reads the screen so the engines can see the game", accessibilityGranted(), ::fixAccessibility),
                entry("Notifications", "Shows engine and update notices", notificationsGranted(), ::fixNotifications),
                entry("Phone", "Reads signal level for the booster", phoneGranted(), ::fixPhone)
            )
            rows.forEach { tally(it.status == PermStatus.GRANTED); box(it) }

            summaryView?.text = "Granted $grantedCount · Missing $missingCount"
            summaryView?.setTextColor(
                if (missingCount > 0) "#FFC107".toColorInt() else ThemeTokens.SUCCESS
            )
        } catch (t: Throwable) {
            try { android.util.Log.w("PermissionsRoom", "refreshNow failed", t) } catch (_: Throwable) {}
            try { summaryView?.text = "Refresh failed: ${t.message ?: t.javaClass.simpleName}" } catch (_: Throwable) {}
        }
    }
}
