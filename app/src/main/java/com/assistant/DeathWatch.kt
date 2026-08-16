package com.assistant

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DEATH WATCH
 *
 * An UncaughtExceptionHandler only sees thrown Java/Kotlin exceptions. It is
 * structurally blind to the deaths that actually kill this app:
 *
 *   - low-memory kill (SIGKILL from LMK)
 *   - native crash (SIGSEGV)
 *   - ANR kill
 *   - user force-stop
 *
 * In all of those the process vanishes before any Kotlin code can run.
 *
 * DeathWatch works the other way round: while a process is alive it holds a
 * marker file and refreshes it with a heartbeat. A clean exit deletes it.
 * If the marker is still there on the next start, the previous session was
 * KILLED, and the last heartbeat tells us when and under what memory pressure.
 */
object DeathWatch {

    private const val REPORT_NAME = "Splendor_Crash_Reports.txt"
    private const val HEARTBEAT_MS = 5000L

    @Volatile private var installed = false
    @Volatile private var marker: File? = null
    @Volatile private var procName = "?"
    @Volatile private var startedMs = 0L

    @JvmStatic
    fun install(ctx: Context) {
        if (installed) return
        installed = true

        val c = ctx.applicationContext
        procName = resolveProcessName(c)
        startedMs = System.currentTimeMillis()

        val dir = File(c.filesDir, "deathwatch").apply { mkdirs() }
        val m = File(dir, safeName(procName) + ".marker")

        // previous session never removed its marker -> it was killed
        if (m.exists()) {
            try { reportDeath(c, m.readText()) } catch (_: Throwable) { }
        }

        marker = m
        beat(c, "START")

        // orderly VM exit removes the marker; SIGKILL cannot
        try {
            Runtime.getRuntime().addShutdownHook(Thread {
                try { m.delete() } catch (_: Throwable) { }
            })
        } catch (_: Throwable) { }

        val t = Thread {
            while (true) {
                try {
                    Thread.sleep(HEARTBEAT_MS)
                    beat(c, "ALIVE")
                } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true
        t.name = "deathwatch"
        try { t.start() } catch (_: Throwable) { }

        log("DeathWatch armed proc=" + procName + " pid=" + android.os.Process.myPid())
    }

    /** call on an intentional shutdown so it is not reported as a kill */
    @JvmStatic
    fun markCleanExit() {
        try { marker?.delete() } catch (_: Throwable) { }
    }

    // ---------------- heartbeat ----------------

    private fun beat(c: Context, state: String) {
        val m = marker ?: return
        try {
            val mi = memory(c)
            m.writeText(
                state + "|" + procName + "|" + android.os.Process.myPid() + "|" +
                startedMs + "|" + System.currentTimeMillis() + "|" +
                mi[0] + "|" + mi[1] + "|" + mi[2]
            )
        } catch (_: Throwable) { }
    }

    /** availMB, lowMemory, thresholdMB */
    private fun memory(c: Context): Array<String> = try {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        arrayOf(
            (info.availMem / 1048576L).toString(),
            info.lowMemory.toString(),
            (info.threshold / 1048576L).toString()
        )
    } catch (_: Throwable) { arrayOf("?", "?", "?") }

    // ---------------- reporting ----------------

    private fun reportDeath(c: Context, raw: String) {
        val p = raw.split("|")
        fun at(i: Int): String = if (i < p.size) p[i] else "?"

        val deadProc  = at(1)
        val deadPid   = at(2)
        val began     = at(3).toLongOrNull() ?: 0L
        val lastBeat  = at(4).toLongOrNull() ?: 0L
        val availMb   = at(5)
        val lowMem    = at(6)
        val threshMb  = at(7)

        val lived = if (began > 0 && lastBeat > began) (lastBeat - began) / 1000L else -1L
        val gap   = if (lastBeat > 0) (System.currentTimeMillis() - lastBeat) / 1000L else -1L

        val javaCrash = javaCrashPresent(c, began)
        val avail = availMb.toIntOrNull() ?: -1
        val thresh = threshMb.toIntOrNull() ?: 0

        val verdict = when {
            javaCrash ->
                "JAVA EXCEPTION - a crash report exists for this session"
            lowMem == "true" ->
                "LOW MEMORY KILL - system reported lowMemory at " + availMb + "MB (threshold " + threshMb + "MB)"
            thresh > 0 && avail in 0..(thresh * 2) ->
                "LIKELY LMK - " + availMb + "MB free vs " + threshMb + "MB threshold; system reclaiming"
            lived in 0..10 ->
                "EARLY DEATH - died " + lived + "s after start; startup fault or force-stop"
            else ->
                "SILENT KILL - no Java exception. LMK, native crash (SIGSEGV), ANR, or force-stop"
        }

        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("")
        sb.appendLine("===== ABNORMAL PROCESS DEATH =====")
        sb.appendLine("Detected at   : " + ts.format(Date()))
        sb.appendLine("Dead process  : " + deadProc + "  (pid " + deadPid + ")")
        sb.appendLine("Session began : " + (if (began > 0) ts.format(Date(began)) else "?"))
        sb.appendLine("Last heartbeat: " + (if (lastBeat > 0) ts.format(Date(lastBeat)) else "?"))
        sb.appendLine("Survived      : " + lived + "s")
        sb.appendLine("Undetected for: " + gap + "s before this restart")
        sb.appendLine("Memory then   : avail=" + availMb + "MB threshold=" + threshMb + "MB lowMemory=" + lowMem)
        sb.appendLine("Java crash    : " + (if (javaCrash) "YES" else "NO"))
        sb.appendLine("VERDICT       : " + verdict)
        sb.appendLine("Marker state  : " + at(0))
        sb.appendLine("==================================")

        val text = sb.toString()

        // 1) standalone report file, user-readable
        try { reportFile(c)?.appendText(text) } catch (_: Throwable) { }

        // 2) the writer already proven to reach Downloads
        log("ABNORMAL DEATH proc=" + deadProc + " lived=" + lived + "s avail=" +
            availMb + "MB lowMemory=" + lowMem + " verdict=" + verdict)
    }

    private fun javaCrashPresent(c: Context, since: Long): Boolean = try {
        val names = arrayOf("splendor_crash.txt")
        val dirs = listOfNotNull(
            c.filesDir,
            c.getExternalFilesDir(null),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        )
        var hit = false
        for (d in dirs) for (n in names) {
            val f = File(d, n)
            if (f.exists() && f.lastModified() >= since) hit = true
        }
        hit
    } catch (_: Throwable) { false }

    private fun reportFile(c: Context): File? {
        val dirs = listOfNotNull(
            try { java.io.File("/sdcard/Splendor-Assist").apply { mkdirs() } } catch (_: Throwable) { null },
            try { c.getExternalFilesDir(null) } catch (_: Throwable) { null },
            c.filesDir
        )
        for (d in dirs) {
            try { if (d.exists() || d.mkdirs()) return File(d, REPORT_NAME) } catch (_: Throwable) { }
        }
        return null
    }

    // ---------------- helpers ----------------

    private fun resolveProcessName(c: Context): String = try {
        if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            val pid = android.os.Process.myPid()
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: ("pid" + pid)
        }
    } catch (_: Throwable) { "pid" + android.os.Process.myPid() }

    private fun safeName(s: String): String =
        s.replace(':', '_').replace('.', '_').replace('/', '_')

    private fun log(m: String) {
        try { com.assistant.diagnostic.RuntimeLogger.log(m, "DEATHWATCH") } catch (_: Throwable) { }
    }
}
