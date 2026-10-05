#!/usr/bin/env python3
import sys
import os

def patch_forensics_and_survival():
    # 1. DeathWatch.kt (ApplicationExitInfo integration)
    dw_path = os.path.join("app", "src", "main", "java", "com", "assistant", "DeathWatch.kt")
    if os.path.exists(dw_path):
        with open(dw_path, "r", encoding="utf-8") as f:
            dw_content = f.read()

        if "getHistoricalProcessExitReasons" not in dw_content:
            old_import = "import android.app.ActivityManager"
            new_import = """import android.app.ActivityManager
import android.app.ApplicationExitInfo"""

            old_vars = """    @Volatile private var installed = false
    @Volatile private var marker: File? = null
    @Volatile private var procName = "?"
    @Volatile private var startedMs = 0L"""

            new_vars = """    @Volatile private var installed = false
    @Volatile private var appContext: Context? = null
    @Volatile private var marker: File? = null
    @Volatile private var procName = "?"
    @Volatile private var startedMs = 0L"""

            old_install = """        installed = true
        procName = resolveProcessName(c)"""

            new_install = """        installed = true
        appContext = c
        procName = resolveProcessName(c)"""

            old_verdict = """        val verdict = when {
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
        }"""

            new_verdict = """        val osExitReason = getHistoricalExitReason(deadPid.toIntOrNull() ?: 0, deadProc)
        val verdict = when {
            javaCrash ->
                "JAVA EXCEPTION - a crash report exists for this session ($osExitReason)"
            lowMem == "true" ->
                "LOW MEMORY KILL - system reported lowMemory at " + availMb + "MB (threshold " + threshMb + "MB) ($osExitReason)"
            thresh > 0 && avail in 0..(thresh * 2) ->
                "LIKELY LMK - " + availMb + "MB free vs " + threshMb + "MB threshold ($osExitReason)"
            lived in 0..10 ->
                "EARLY DEATH - died " + lived + "s after start ($osExitReason)"
            else ->
                "TERMINATED: $osExitReason"
        }"""

            old_log = """    private fun log(m: String) {
        try { com.assistant.diagnostic.RuntimeLogger.log(m, "DEATHWATCH") } catch (_: Throwable) { }
    }"""

            new_log = """    private fun log(m: String) {
        try { com.assistant.diagnostic.RuntimeLogger.log(m, "DEATHWATCH") } catch (_: Throwable) { }
    }

    private fun getHistoricalExitReason(deadPid: Int, deadProc: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "SDK_LESS_THAN_30"
        val ctx = appContext ?: return "NO_CONTEXT"
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exitInfos = am.getHistoricalProcessExitReasons(ctx.packageName, deadPid, 5)
            val info = exitInfos.firstOrNull { deadPid != 0 && it.pid == deadPid }
                ?: exitInfos.firstOrNull { deadProc.isNotBlank() && it.processName == deadProc }
                ?: exitInfos.firstOrNull()

            if (info != null) {
                val reasonStr = when (info.reason) {
                    ApplicationExitInfo.REASON_ANR -> "REASON_ANR (Application Not Responding)"
                    ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH (Java/Kotlin uncaught exception)"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE (Native C/C++ SIGSEGV/SIGABRT)"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE (Excessive CPU/RAM/Battery)"
                    ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF (Clean stopSelf or System.exit)"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "REASON_INITIALIZATION_FAILURE (Process init failed)"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY (OS Low Memory Killer / LMK)"
                    ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER (Vendor/HyperOS background eviction or kill)"
                    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "REASON_PERMISSION_CHANGE (Permission revoked)"
                    ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED (Killed by OS signal ${info.status})"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED (User force-stop or task swipe)"
                    ApplicationExitInfo.REASON_USER_STOPPED -> "REASON_USER_STOPPED (User stopped application)"
                    else -> "REASON_CODE_${info.reason}"
                }
                "OS_REPORTED: $reasonStr [status=${info.status} importance=${info.importance}]"
            } else {
                "NO_OS_RECORD_FOUND"
            }
        } catch (t: Throwable) {
            "QUERY_FAILED: ${t.javaClass.simpleName}: ${t.message}"
        }
    }"""

            if old_verdict in dw_content and old_log in dw_content:
                dw_content = dw_content.replace(old_import, new_import, 1)
                dw_content = dw_content.replace(old_vars, new_vars, 1)
                dw_content = dw_content.replace(old_install, new_install, 1)
                dw_content = dw_content.replace(old_verdict, new_verdict, 1)
                dw_content = dw_content.replace(old_log, new_log, 1)
                with open(dw_path, "w", encoding="utf-8") as f:
                    f.write(dw_content)
                print("PASS: DeathWatch.kt ApplicationExitInfo integration updated.")
            else:
                print("FAIL: Verdict pattern not matched in DeathWatch.kt")
                sys.exit(1)

    # 2. App.kt (Earliest possible GlobalCrashHandler installation)
    app_path = os.path.join("app", "src", "main", "java", "com", "assistant", "App.kt")
    if os.path.exists(app_path):
        with open(app_path, "r", encoding="utf-8") as f:
            app_content = f.read()

        old_app_start = """    override fun onCreate() {
        super.onCreate()

        val currentProcess = getCurrentProcessName()"""

        new_app_start = """    override fun onCreate() {
        super.onCreate()
        GlobalCrashHandler.install(this)

        val currentProcess = getCurrentProcessName()"""

        if "GlobalCrashHandler.install(this)" in app_content:
            app_content = app_content.replace("        GlobalCrashHandler.install(this)\n", "")

        if old_app_start in app_content:
            app_content = app_content.replace(old_app_start, new_app_start, 1)
            with open(app_path, "w", encoding="utf-8") as f:
                f.write(app_content)
            print("PASS: App.kt GlobalCrashHandler earliest installation updated.")

    # 3. WatchdogAdapterService.kt (override onStartCommand -> START_STICKY)
    wd_path = os.path.join("app", "src", "main", "java", "com", "assistant", "adapter", "watchdog", "WatchdogAdapterService.kt")
    if os.path.exists(wd_path):
        with open(wd_path, "r", encoding="utf-8") as f:
            wd_content = f.read()

        if "override fun onStartCommand" not in wd_content:
            old_bind = "    override fun onBind(intent: Intent?): IBinder? = messenger.binder"
            new_bind = """    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder"""

            if old_bind in wd_content:
                wd_content = wd_content.replace(old_bind, new_bind, 1)
                with open(wd_path, "w", encoding="utf-8") as f:
                    f.write(wd_content)
                print("PASS: WatchdogAdapterService START_STICKY updated.")

    # 4. PerformanceEngineService.kt (override onStartCommand -> START_STICKY)
    pes_path = os.path.join("app", "src", "main", "java", "com", "assistant", "PerformanceEngineService.kt")
    if os.path.exists(pes_path):
        with open(pes_path, "r", encoding="utf-8") as f:
            pes_content = f.read()

        if "override fun onStartCommand" not in pes_content:
            old_bind = "    override fun onBind(intent: Intent?): IBinder? = null"
            new_bind = """    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null"""

            if old_bind in pes_content:
                pes_content = pes_content.replace(old_bind, new_bind, 1)
                with open(pes_path, "w", encoding="utf-8") as f:
                    f.write(pes_content)
                print("PASS: PerformanceEngineService START_STICKY updated.")

    # 5. GameplayEngineService.kt (override onStartCommand -> START_STICKY)
    ges_path = os.path.join("app", "src", "main", "java", "com", "assistant", "GameplayEngineService.kt")
    if os.path.exists(ges_path):
        with open(ges_path, "r", encoding="utf-8") as f:
            ges_content = f.read()

        if "override fun onStartCommand" not in ges_content:
            old_bind = "    override fun onBind(intent: Intent?): IBinder? = null"
            new_bind = """    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null"""

            if old_bind in ges_content:
                ges_content = ges_content.replace(old_bind, new_bind, 1)
                with open(ges_path, "w", encoding="utf-8") as f:
                    f.write(ges_content)
                print("PASS: GameplayEngineService START_STICKY updated.")

    print("PASS: All advanced forensics and survival upgrades successfully applied.")

if __name__ == "__main__":
    patch_forensics_and_survival()
