package com.assistant

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GlobalCrashHandler(
    private val appContext: Context
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    init {
        // auto-install on construction, makes MainActivity GlobalCrashHandler(this) still work
        if (Thread.getDefaultUncaughtExceptionHandler() !is GlobalCrashHandler) {
            Thread.setDefaultUncaughtExceptionHandler(this)
            installed = true
        }
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            writeCrashReport(appContext, t, e)
        } catch (_: Throwable) {}
        defaultHandler?.uncaughtException(t, e) ?: run {
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }

    companion object {
        @Volatile private var installed = false

        fun install(ctx: Context) {
            if (installed) return
            installed = true
            val appCtx = ctx.applicationContext
            Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(appCtx))
        }

        fun logFeatureFault(feature: String, message: String) {
            try {
                val f = getLogFile(null, "splendor_health.log", true)
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                f.appendText("[$ts] $feature: $message\n")
            } catch (_: Throwable) {}
        }

        private fun writeCrashReport(ctx: Context, thread: Thread, e: Throwable) {
            val report = buildReport(ctx, thread, e)
            val file = getLogFile(ctx, "splendor_crash.txt", false)
            file.writeText(report)
        }

        private fun getLogFile(ctx: Context?, baseName: String, append: Boolean): File {
            val candidates = mutableListOf<File>()
            try { candidates.add(File("/sdcard/Download")) } catch (_: Throwable) {}
            try {
                @Suppress("DEPRECATION")
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { candidates.add(it) }
            } catch (_: Throwable) {}
            try { ctx?.getExternalFilesDir(null)?.let { candidates.add(it) } } catch (_: Throwable) {}
            try { ctx?.filesDir?.let { candidates.add(it) } } catch (_: Throwable) {}

            var baseDir = candidates.firstOrNull { it.exists() || it.mkdirs() } ?: File("/data/local/tmp")

            val dot = baseName.lastIndexOf('.')
            val name = if (dot > 0) baseName.substring(0, dot) else baseName
            val ext = if (dot > 0) baseName.substring(dot) else ""

            var target = File(baseDir, baseName)
            if (append) return target

            if (!target.exists()) return target
            for (i in 1..99) {
                target = File(baseDir, "$name($i)$ext")
                if (!target.exists()) return target
            }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            return File(baseDir, "${name}_${ts}$ext")
        }

        private fun buildReport(ctx: Context, thread: Thread, e: Throwable): String {
            val sb = StringBuilder()
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            sb.appendLine("===== SPLENDOR ASSIST CRASH REPORT =====")
            sb.appendLine("Time: $ts")
            sb.appendLine()
            sb.appendLine("--- DEVICE ---")
            sb.appendLine("Manufacturer: ${Build.MANUFACTURER}")
            sb.appendLine("Model: ${Build.MODEL}")
            sb.appendLine("Android: ${Build.VERSION.RELEASE}  SDK=${Build.VERSION.SDK_INT}")
            sb.appendLine("Board: ${Build.BOARD}  Fingerprint: ${Build.FINGERPRINT}")
            sb.appendLine()
            sb.appendLine("--- APP ---")
            try {
                val pm = ctx.packageManager
                val pi = pm.getPackageInfo(ctx.packageName, 0)
                sb.appendLine("Package: ${ctx.packageName}")
                @Suppress("DEPRECATION")
                sb.appendLine("Version: ${pi.versionName} (${pi.versionCode})")
            } catch (_: Throwable) {
                sb.appendLine("Package: ${ctx.packageName}")
            }
            sb.appendLine("Process: ${android.os.Process.myPid()}  Thread: ${thread.name} id=${thread.id}")
            sb.appendLine()
            sb.appendLine("--- EXCEPTION ---")
            sb.appendLine(stackTraceString(e))
            sb.appendLine()
            sb.appendLine("--- FEATURE HEALTH AUDIT ---")
            sb.appendLine(probeFeatures(ctx))
            sb.appendLine()
            sb.appendLine("--- END ---")
            return sb.toString()
        }

        private fun stackTraceString(e: Throwable): String {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            return sw.toString()
        }

        private fun probeFeatures(ctx: Context): String {
            val out = StringBuilder()
            fun check(label: String, className: String, health: (() -> String)? = null) {
                try {
                    Class.forName(className)
                    val extra = try { health?.invoke() ?: "FOUND" } catch (th: Throwable) { "FOUND / health_err=${th.message}" }
                    out.appendLine("  [OK]   $label -> $extra")
                } catch (cnf: ClassNotFoundException) {
                    out.appendLine("  [MISS] $label -> $className")
                } catch (th: Throwable) {
                    out.appendLine("  [FAIL] $label -> ${th.message}")
                }
            }

            out.appendLine("Control Rooms:")
            check("SmartAssistControlRoom", "com.assistant.controlroom.ui.SmartAssistControlRoomActivity")
            check("GoalkeeperControlRoom", "com.assistant.controlroom.ui.GoalkeeperControlRoomActivity")
            check("InterceptionControlRoom", "com.assistant.controlroom.ui.InterceptionControlRoomActivity")
            check("FutureRooms", "com.assistant.controlroom.ui.FutureRoomsActivity")

            out.appendLine()
            out.appendLine("Smart Assist Core:")
            check("SmartAssistRepository", "com.assistant.adapter.smartassist.SmartAssistRepository") {
                try {
                    val m = Class.forName("com.assistant.adapter.smartassist.SmartAssistRepository")
                        .getMethod("optimizationReady")
                    "optimizationReady=${m.invoke(null)}"
                } catch (_: Throwable) { "FOUND" }
            }
            check("SmartAssistMetrics", "com.assistant.adapter.smartassist.SmartAssistMetrics")
            check("SmartAssistPipeline", "com.assistant.adapter.smartassist.SmartAssistPipeline")
            check("SmartAssistControlRoomBridge", "com.assistant.adapter.smartassist.SmartAssistControlRoomBridge")

            out.appendLine()
            out.appendLine("Execution Bus:")
            check("CentralExecutionBus", "com.assistant.execution.CentralExecutionBus")

            out.appendLine()
            out.appendLine("Adapter Services:")
            check("BatteryAdapter", "com.assistant.adapter.battery.BatteryAdapterService")
            check("BootAdapter", "com.assistant.adapter.boot.BootAdapterService")
            check("InputAdapter", "com.assistant.adapter.input.InputAdapterService")
            check("InterruptionAdapter", "com.assistant.adapter.interruption.InterruptionAdapterService")
            check("LagAdapter", "com.assistant.adapter.lag.LagAdapterService")
            check("LmkAdapter", "com.assistant.adapter.lmk.LmkAdapterService")
            check("MemoryAdapter", "com.assistant.adapter.memory.MemoryAdapterService")
            check("NetAdapter", "com.assistant.adapter.net.NetAdapterService")
            check("PingAdapter", "com.assistant.adapter.ping.PingAdapterService")
            check("SchedulerAdapter", "com.assistant.adapter.scheduler.SchedulerAdapterService")
            check("SmartAssistAdapter", "com.assistant.adapter.smartassist.SmartAssistAdapterService")
            check("StutterAdapter", "com.assistant.adapter.stutter.StutterAdapterService")
            check("SyncAdapter", "com.assistant.adapter.sync.SyncAdapterService")
            check("ThermalAdapter", "com.assistant.adapter.thermal.ThermalAdapterService")
            check("WatchdogAdapter", "com.assistant.adapter.watchdog.WatchdogAdapterService")

            out.appendLine()
            out.appendLine("Runtime / Overlay:")
            check("OverlayService", "com.assistant.OverlayService")
            check("SmartAssistAccessibilityEngine", "com.assistant.adapter.smartassist.SmartAssistAccessibilityEngine")
            check("DashboardInjector", "com.assistant.DashboardInjector")
            check("AnalyticsTheaterActivity", "com.assistant.overlay.ui.AnalyticsTheaterActivity")
            check("DvrProjectionService", "com.assistant.overlay.dvr.DvrProjectionService")
            check("DvrSyncEngine", "com.assistant.coach.DvrSyncEngine")

            out.appendLine()
            out.appendLine("Permissions / System Gates:")
            fun permStatus(perm: String): String = try {
                if (ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"
            } catch (_: Throwable) { "?" }
            out.appendLine("  POST_NOTIFICATIONS: ${if (Build.VERSION.SDK_INT >= 33) permStatus("android.permission.POST_NOTIFICATIONS") else "n/a<33"}")
            out.appendLine("  SYSTEM_ALERT_WINDOW: ${if (Settings.canDrawOverlays(ctx)) "GRANTED" else "DENIED"}")
            val acc = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "" } catch (_: Throwable) { "" }
            out.appendLine("  Accessibility Enabled: ${acc.contains(ctx.packageName, true)}")
            out.appendLine("  Accessibility Services: $acc")

            out.appendLine()
            out.appendLine("ControlRoom Registry Snapshot:")
            try {
                val reg = Class.forName("com.assistant.controlroom.AdapterControlRoomRegistry")
                val get = reg.getMethod("get", String::class.java)
                listOf("goalkeeper","interception","smart_assist").forEach { id ->
                    
val instance = reg.getField("INSTANCE").get(null)
val r = get.invoke(instance, id)

                    out.appendLine("  $id -> $r")
                }
            } catch (th: Throwable) {
                out.appendLine("  registry read failed: ${th.message}")
            }
            return out.toString()
        }
    }
}
