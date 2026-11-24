package com.assistant.controlroom.ui


import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.assistant.UpdateActivity
import com.assistant.UpdateChecker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PHASE4 UPGRADE: FutureRoomsActivity
 *
 * Room directory: real room cards with navigation to active subsystems.
 *
 * Active rooms:
 *   - AI Self-Heal Agent Monitor — navigates to AgentHubActivity, the
 *     single authoritative agent monitor (No66-D44: the inline agent
 *     screen that used to live here duplicated AgentHub, and its
 *     "Force Heal Cycle Now" button was a no-op whenever the daemon was
 *     already running — RuntimeSelfHealEngine.start() returns at once
 *     when running, so the button only showed a toast. AgentHub's
 *     button genuinely forces a cycle via InAppAgentCore.runNow()).
 *
 * Future stubs: 15 planned rooms listed below active rooms.
 *
 * App info: read-only app details (name, version, package, SDKs,
 * install dates, update channel, in-app update history) at the top —
 * UI piece 2. Every value is read live at runtime; no toggles here.
 *
 * Navigation: launched plain from the home card (MainActivity).
 * No intent extra is read: the old "room_label" extra (single value
 * "Future Rooms", activity exported=false) was deleted in the Letter F
 * fix phase (F3-2) — it selected no screen.
 */
class FutureRoomsActivity : AppCompatActivity() {

    // Q61-2/Q66-B4 (batch 5b): the themed palette, resolved once from this
    // activity's theme — the theme is the source of truth, the ThemeTokens
    // hex values are fallbacks for any attribute the theme does not define.
    // Roles with no theme attribute (TEXT_DIM, WARN, BUTTON_NEUTRAL) keep
    // their hex constants. Other ThemeTokens users (Goalkeeper/Interception/
    // AgentHub/GameplayRoom activities, ModMenuController) stay on hex for
    // now — the resolver exists; they adopt one by one.
    private val paletteBg by lazy { ThemeTokens.bg(this) }
    private val paletteText by lazy { ThemeTokens.text(this) }
    private val paletteTextMuted by lazy { ThemeTokens.textMuted(this) }
    private val paletteSuccess by lazy { ThemeTokens.success(this) }
    private val paletteButton by lazy { ThemeTokens.button(this) }
    private val paletteButtonAlt by lazy { ThemeTokens.buttonAlt(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Shared UI helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(24), dp(16), dp(32))
        setBackgroundColor(paletteBg)
    }

    private fun mount(v: LinearLayout) {
        setContentView(ScrollView(this).apply {
            setBackgroundColor(paletteBg)
            addView(v, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        })
    }

    private fun text(list: LinearLayout, t: String, size: Float = 13f,
                     bold: Boolean = false, color: Int = paletteText): TextView {
        val tv = TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
        }
        list.addView(tv)
        return tv
    }

    private fun btn(list: LinearLayout, label: String,
                    bg: Int = paletteButton, onClick: () -> Unit) {
        list.addView(Button(this).apply {
            text = label; isAllCaps = false; setBackgroundColor(bg)
            // No66-D23: white text on BUTTON_ALT was 2.38:1; alt buttons
            // (now brand cyan #00E5FF) take dark text — 12.79:1.
            setTextColor(if (bg == paletteButtonAlt) paletteBg else Color.WHITE); textSize = 14f
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(5), 0, dp(5))
            layoutParams = lp
            setOnClickListener { onClick() }
        })
    }

    private fun divider(list: LinearLayout) {
        list.addView(android.view.View(this).apply {
            setBackgroundColor(ThemeTokens.BUTTON_NEUTRAL)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            lp.setMargins(0, dp(14), 0, dp(14))
            layoutParams = lp
        })
    }

    // ─────────────────────────────────────────────────────────────────────
    // APP INFO — read-only app details (UI piece 2)
    // ─────────────────────────────────────────────────────────────────────
    // Every value is read LIVE at runtime (PackageManager /
    // UpdateChecker / UpdateActivity history) — nothing is hardcoded, so
    // nothing goes stale when a new release lands. No toggles, no
    // controls: this is an info page, not an admin page. No sensitive
    // info (no keystore, signing, or token details) is shown here.
    private fun infoRow(list: LinearLayout, label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(2), 0, dp(2))
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 12f; setTextColor(ThemeTokens.TEXT_DIM)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = value; textSize = 12f; setTextColor(paletteText)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        })
        list.addView(row)
    }

    private fun packageInfo(): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fmtTime(millis: Long): String =
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date(millis))

    private fun showAppInfo(root: LinearLayout) {
        text(root, "APP INFO", 13f, bold = true, color = paletteSuccess)

        val pi = packageInfo()
        val appName = try {
            applicationInfo.loadLabel(packageManager).toString()
        } catch (e: Exception) {
            packageName
        }
        infoRow(root, "App", appName)
        val versionName = pi?.versionName ?: "Unknown"
        val versionCode = UpdateChecker.installedVersionCode(this)
        infoRow(root, "Version",
            if (versionCode >= 0) "$versionName ($versionCode)" else versionName)
        infoRow(root, "Package", packageName)
        infoRow(root, "Target SDK", applicationInfo.targetSdkVersion.toString())
        infoRow(root, "Minimum SDK", applicationInfo.minSdkVersion.toString())
        if (pi != null) {
            infoRow(root, "Installed", fmtTime(pi.firstInstallTime))
            infoRow(root, "Last updated", fmtTime(pi.lastUpdateTime))
        }
        infoRow(root, "Updates", "GitHub releases")

        // Update history, newest first — recorded by the in-app updater
        // (UpdateActivity.readHistory). Empty until the first update
        // lands through the in-app updater.
        val history = UpdateActivity.readHistory(this)
        if (history.isEmpty()) {
            text(root, "   No updates installed through the in-app updater yet",
                11f, color = paletteTextMuted)
        } else {
            text(root, "   Recent updates", 12f, color = paletteTextMuted)
            for ((version, at) in history.take(3)) {
                text(root, "   v$version — ${fmtTime(at)}",
                    11f, color = paletteTextMuted)
            }
        }

        divider(root)
    }

    // ─────────────────────────────────────────────────────────────────────
    // HOME SCREEN — Room Cards
    // ─────────────────────────────────────────────────────────────────────
    private fun showHome() {
        val root = page()

        text(root, "SPLENDOR ROOMS", 22f, bold = true, color = Color.WHITE)
        text(root, "Control centers for every subsystem", 12f, color = ThemeTokens.TEXT_DIM)

        divider(root)

        // UI piece 2: app info/details first — the details a settings
        // room for this app needs (all read live, no toggles).
        showAppInfo(root)

        text(root, "ACTIVE ROOMS", 13f, bold = true, color = paletteSuccess)

        btn(root, "🤖   AI Self-Heal Agent Monitor", paletteButtonAlt) {
            startActivity(Intent(this, AgentHubActivity::class.java))
        }
        text(root, "   Detects and fixes broken engine states while you play. Logs every fix to SplendorHealLog.txt", 11f, color = paletteTextMuted)

        // F-PERMS3: the Permissions room — every permission the app needs
        // with granted / missing status, tap a missing one to open its
        // settings page. Lives here because the Settings card opens this
        // page (the full Settings page is a later piece).
        btn(root, "🔐   Permissions", paletteButtonAlt) {
            startActivity(Intent(this, PermissionsRoomActivity::class.java))
        }
        text(root, "   Every permission the app needs, with granted / missing status. Tap a missing one to open its settings page — the system's App info page only lists Notifications and Phone.", 11f, color = paletteTextMuted)

        text(root, "  ⚙️  Admin Settings — removed", 11f, color = ThemeTokens.TEXT_DIM)

        divider(root)

        text(root, "COMING SOON (not wired yet)", 13f, bold = true, color = ThemeTokens.WARN)

        val planned = listOf(
            "📊  Live Frame Monitor          — real-time VisionCore frame display",
            "🧠  Tactical Intelligence Room  — formation + behavior recognition",
            "🎯  Shot Accuracy Lab           — shooting lane analysis + keeper bias",
            "🛡️  Defensive Analysis Room     — intercept matrix + zone pressure",
            "📡  Network Quality Room        — packet loss + congestion sentinel",
            "🔥  Thermal Management Room     — device heat + engine duty cycle",
            "🏃  Player Tracking Room        — jersey segmentation + NMS tuning",
            "⚡  Input Response Room         — dispatch latency + touch IRQ stalls",
            "💾  Memory Optimization Room    — RAM tier + LMK lifecycle control",
            "🔄  Sync Monitor Room           — accessibility liveness + heal count",
            "📈  Match Analytics Room        — session stats + contribution rates",
            "🎮  Gameplay Replay Room        — DVR session review",
            "🩺  Health Diagnostics Room     — full engine audit on demand",
            "🌍  Formation Engine Room       — team shape + defensive line",
            "🏆  Keeper Command Room         — goalkeeper beast save configuration"
        )
        for (r in planned) {
            text(root, "  $r", 11f, color = ThemeTokens.TEXT_DIM)
        }

        divider(root)

        btn(root, "←  Back", ThemeTokens.BUTTON_NEUTRAL) { finish() }
        mount(root)
    }
}
