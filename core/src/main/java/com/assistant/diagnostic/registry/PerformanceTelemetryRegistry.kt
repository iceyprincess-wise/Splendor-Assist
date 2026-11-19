package com.assistant.diagnostic.registry

import android.content.Context
import com.assistant.storage.SplendorStorageRoot
import java.io.File

/**
 * PERFORMANCE TELEMETRY - the bodyguard-to-king surface.
 * Adapters (:kernel process) PUBLISH measurements. The main runtime READS,
 * never waits: fresh in-memory value wins, else a throttled file read.
 */
object PerformanceTelemetryRegistry {

    data class NetTelemetry(
        val rttMs: Float, val jitterMs: Float, val quality: String,
        val carrier: String, val transport: String, val updatedMs: Long
    )

    private val EMPTY = NetTelemetry(0f, 0f, "UNKNOWN", "?", "NONE", 0L)

    @Volatile private var net: NetTelemetry = EMPTY
    @Volatile private var file: File? = null
    @Volatile private var lastRead = 0L
    @Volatile private var readCache: NetTelemetry = EMPTY

    /**
     * [context] is accepted so every existing caller keeps one stable
     * contract. The registry only needs the canonical external root, which
     * is fixed, so the argument is intentionally unused.
     */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun initialize(context: Context) {
        // Canonical root only; stays null when storage is not ready and
        // every publisher already null-guards `file`.
        if (file == null) file = try { SplendorStorageRoot.file("perf_telemetry.txt") } catch (_: Throwable) { null }
    }

    // B30-D4: initialize() runs only at process start (App.onCreate,
    // PerformanceEngineService, NetProbeEngine.start). If storage was not
    // ready at those moments, `file` stayed null for the whole process
    // lifetime: no publisher ever re-entered SplendorStorageRoot, so the
    // cross-process file channel was dead until restart even after the
    // All Files grant arrived (S17-D1 lazy retry exists in the root but
    // nothing in this registry called it again). Re-enter it here,
    // throttled: directory() fails fast at the permission gate when
    // storage is not ready, and the retry is skipped entirely once
    // `file` resolves. Needs no Context: the canonical root is fixed.
    @Volatile private var lastFileRetryMs = 0L
    private const val FILE_RETRY_THROTTLE_MS = 5_000L

    private fun ensureFile() {
        if (file != null) return
        val now = System.currentTimeMillis()
        if (now - lastFileRetryMs < FILE_RETRY_THROTTLE_MS) return
        lastFileRetryMs = now
        try { file = SplendorStorageRoot.file("perf_telemetry.txt") } catch (_: Throwable) { }
    }

    // B30-D5: the pipe-delimited single-line format has no escaping, so a
    // "|" inside a string field shifts every later field (the p.size >= 6
    // check still passes, but p[5] becomes the transport string and the
    // timestamp parses to 0L). Quality/transport are internal constants
    // and safe today; carrier is NOT -- CarrierProfileEngine passes the
    // raw TelephonyManager networkOperatorName through (GENERIC.copy(name
    // = op.uppercase())), an arbitrary system string. Strip "|" and
    // newlines at the writer so a hostile/odd operator name can never
    // corrupt the parse.
    private fun pipeSafe(s: String): String =
        s.replace('|', '?').replace('\n', '?').replace('\r', '?')

    @JvmStatic
    @Synchronized
    fun publishNet(rttMs: Float, jitterMs: Float, quality: String, carrier: String, transport: String) {
        ensureFile()
        val q = pipeSafe(quality); val c = pipeSafe(carrier); val tr = pipeSafe(transport)
        val t = NetTelemetry(rttMs, jitterMs, q, c, tr, System.currentTimeMillis())
        net = t
        try {
            file?.writeText(t.rttMs.toString() + "|" + t.jitterMs + "|" + t.quality + "|" +
                t.carrier + "|" + t.transport + "|" + t.updatedMs)
        } catch (_: Throwable) { }
        // SPLENDOR_P8_HIST_NET
        // Task 3: keep the single-line current value above AND append the
        // same sample to the bounded history file (trend analysis).
        appendHistory("perf_telemetry_history.txt", t.rttMs.toString() + "|" + t.jitterMs + "|" + t.quality + "|" +
            t.carrier + "|" + t.transport + "|" + t.updatedMs)
    }

    /** Non-blocking. Never touches the network, never waits on an adapter. */
    @JvmStatic
    fun currentNet(): NetTelemetry {
        val now = System.currentTimeMillis()
        if (now - net.updatedMs < 15000L) return net
        if (now - lastRead < 2000L) return if (now - readCache.updatedMs < 15_000L) readCache else EMPTY
        lastRead = now
        ensureFile()
        try {
            val p = (file?.takeIf { it.exists() }?.readText() ?: return if (now - readCache.updatedMs < 15_000L) readCache else EMPTY).split("|")
            if (p.size >= 6) {
                // B30-D2: the file timestamp was never checked, so a sample
                // written hours ago (probe / :survival dead) was served as
                // if live, and FilterEngine prints net.rttMs with no
                // updatedMs check of its own. Adopt the file sample only
                // when its own timestamp is fresh (<15s, mirroring the
                // in-memory rule and the currentFilterAggression pattern);
                // otherwise report EMPTY. A torn read also lands here: a
                // truncated tail makes the timestamp unparseable (0L) and
                // the torn sample is rejected instead of poisoning the
                // cache with 0f/0L fallbacks.
                val ts = p[5].toLongOrNull() ?: 0L
                readCache = if (ts > 0L && now - ts < 15_000L) NetTelemetry(
                    p[0].toFloatOrNull() ?: 0f, p[1].toFloatOrNull() ?: 0f,
                    p[2], p[3], p[4], ts) else EMPTY
            }
        } catch (_: Throwable) { }
        return if (now - readCache.updatedMs < 15_000L) readCache else EMPTY
    }

    // ---- ACTION WINDOW (net stack verdict: GO / CAUTION / HOLD) ----
    @Volatile private var window = "UNKNOWN"
    @Volatile private var windowDetail = ""
    @Volatile private var windowMs = 0L
    @Volatile private var wfile: File? = null

    @JvmStatic
    fun publishActionWindow(verdict: String, detail: String) {
        // B30-D5: detail is a free-form string in the pipe format; keep it
        // pipe-safe the same way publishNet does (today's only caller
        // builds it from numbers, but the format must not depend on that).
        val v = pipeSafe(verdict); val d = pipeSafe(detail)
        window = v; windowDetail = d; windowMs = System.currentTimeMillis()
        ensureFile()
        try {
            if (wfile == null) wfile = file?.parentFile?.let { File(it, "perf_window.txt") }
            wfile?.writeText(v + "|" + d + "|" + windowMs)
        } catch (_: Throwable) { }
        // SPLENDOR_P8_HIST_WINDOW
        // Task 3: keep the single-line current value above AND append the
        // same sample to the bounded history file (trend analysis). This
        // also gives perf_window.txt its first registry reader (via the
        // history reader below); previously no reader existed at all.
        appendHistory("perf_window_history.txt", v + "|" + d + "|" + windowMs)
    }

    // ---- DISPLAY / DEVICE-SIDE (lag stack verdict: SMOOTH / STRAINED / CHOKING) ----
    @Volatile private var dGap = 0f
    @Volatile private var dJank = 0f
    @Volatile private var dStall = 0f
    @Volatile private var dVerdict = "UNKNOWN"
    @Volatile private var dMs = 0L
    @Volatile private var dfile: File? = null

    @JvmStatic
    fun publishDisplay(frameGapMs: Float, jankPerMin: Float, stallMs: Float, verdict: String) {
        dGap = frameGapMs; dJank = jankPerMin; dStall = stallMs; dVerdict = verdict
        dMs = System.currentTimeMillis()
        ensureFile()
        try {
            if (dfile == null) dfile = file?.parentFile?.let { File(it, "perf_display.txt") }
            dfile?.writeText(frameGapMs.toString() + "|" + jankPerMin + "|" + stallMs + "|" + verdict + "|" + dMs)
        } catch (_: Throwable) { }
        // SPLENDOR_P8_HIST_DISPLAY
        // Task 3: keep the single-line current value above AND append the
        // same sample to the bounded history file (trend analysis). This
        // also gives perf_display.txt its first registry reader (via the
        // history reader below); previously no reader existed at all.
        appendHistory("perf_display_history.txt", frameGapMs.toString() + "|" + jankPerMin + "|" + stallMs + "|" + verdict + "|" + dMs)
        // SPLENDOR_P7_DEVICE_LAG_BEGIN
        // LagVerdictEngine already reports this verdict to the registry
        // (publishDisplay, every 500ms); mirror it into the merged
        // cross-process device snapshot so main-process FilterEngine
        // can read it. The separate perf_display.txt write above is kept.
        updateDeviceState(lagVerdict = verdict)
        // SPLENDOR_P7_DEVICE_LAG_END
    }

    // ---- LOAD SHED (lag governor -> main-process capture pipeline) ----
    @Volatile private var shed = "NONE"
    @Volatile private var shedMs = 0L
    @Volatile private var sfile: File? = null
    @Volatile private var shedReadAt = 0L
    @Volatile private var shedReadCache = "NONE"
    @Volatile private var shedReadCacheMs = 0L

    // ---- CAPTURE HEARTBEAT (F37-D7) ----
    // LagVerdictEngine runs in :survival but FrameAssembler lives in the
    // main process, so captureAlive() there was always false and SMOOTH was
    // structurally unreachable. The capture loop publishes a heartbeat here
    // (in-memory + perf_capture.txt, mirroring currentLoadShed); the
    // :survival reader adopts it only while fresher than 3000 ms.
    @Volatile private var captureBeatMs = 0L
    @Volatile private var captureBeatFileMs = 0L
    @Volatile private var captureFile: File? = null

    @JvmStatic
    fun publishLoadShed(level: String) {
        shed = level; shedMs = System.currentTimeMillis()
        ensureFile()
        try {
            if (sfile == null) sfile = file?.parentFile?.let { File(it, "perf_loadshed.txt") }
            sfile?.writeText(level + "|" + shedMs)
        } catch (_: Throwable) { }
        // SPLENDOR_P8_HIST_SHED
        // Task 3: keep the single-line current value above AND append the
        // same sample to the bounded history file (trend analysis).
        appendHistory("perf_loadshed_history.txt", level + "|" + shedMs)
    }

    /** Stale-aware: advice older than 15s means the governor is gone - run full. */
    @JvmStatic
    // SPLENDOR_ASYNC_IO_FIX: Non-blocking load shed query on frame path
    // SPLENDOR_LOADSHED_XPROC_V1 - publishLoadShed() runs in the :survival
    // process and wrote perf_loadshed.txt for cross-process readers, but this
    // reader only ever looked at its own in-process volatile, so the main
    // process always saw "NONE" and the capture pipeline never shed load.
    // Mirror the proven currentNet() file fallback with a throttle.
    fun currentLoadShed(): String {
        val now = System.currentTimeMillis()
        if (now - shedMs < 15_000L) return shed
        if (now - shedReadAt < 2_000L) return if (now - shedReadCacheMs < 15_000L) shedReadCache else "NONE"
        shedReadAt = now
        ensureFile()
        try {
            if (sfile == null) sfile = file?.parentFile?.let { File(it, "perf_loadshed.txt") }
            val f = sfile?.takeIf { it.exists() } ?: return if (now - shedReadCacheMs < 15_000L) shedReadCache else "NONE"
            val p = f.readText().split("|")
            // B30-D1: the file timestamp (p[1]) was written but never
            // read, so the "stale-aware: older than 15s means the
            // governor is gone - run full" comment was false: once the
            // :survival governor died while shedding, the main process
            // kept its last HEAVY/LIGHT level FOREVER (OverlayService
            // quarters OCR on HEAVY, RuntimeSelfHeal purges on HEAVY).
            // Adopt the file level only when its own timestamp is fresh;
            // a stale file resets to NONE. A torn read also lands here:
            // a truncated tail makes the timestamp unparseable (0L), so
            // a torn level is never adopted.
            if (p.isNotEmpty() && p[0].isNotEmpty()) {
                val ts = if (p.size >= 2) p[1].toLongOrNull() ?: 0L else 0L
                if (ts > 0L && now - ts < 15_000L) {
                    shedReadCache = p[0]; shedReadCacheMs = ts
                } else {
                    shedReadCache = "NONE"; shedReadCacheMs = 0L
                }
            }
        } catch (_: Throwable) {
        }
        return if (now - shedReadCacheMs < 15_000L) shedReadCache else "NONE"
    }

    // ---- CAPTURE HEARTBEAT (F37-D7) ----
    @JvmStatic
    fun publishCaptureHeartbeat() {
        val now = System.currentTimeMillis()
        captureBeatMs = now
        // File write throttled to 1 per 500 ms; the reader's freshness
        // window is 3000 ms, so a dead capture still goes stale on time.
        if (now - captureBeatFileMs < 500L) return
        captureBeatFileMs = now
        ensureFile()
        try {
            if (captureFile == null) captureFile = file?.parentFile?.let { File(it, "perf_capture.txt") }
            captureFile?.writeText("$now")
        } catch (_: Throwable) { }
    }

    @JvmStatic
    fun currentCaptureAlive(): Boolean {
        val now = System.currentTimeMillis()
        if (now - captureBeatMs < 3000L) return true
        return try {
            ensureFile()
            if (captureFile == null) captureFile = file?.parentFile?.let { File(it, "perf_capture.txt") }
            val ts = captureFile?.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
            ts > 0L && now - ts < 3000L
        } catch (_: Throwable) { false }
    }

    // ---- STUTTER (sub-second burst state: CALM / HICCUP / OSCILLATION / SEIZURE) ----
    @Volatile private var stState = "CALM"
    @Volatile private var stBursts = 0f
    @Volatile private var stWorst = 0f
    @Volatile private var stMs = 0L

    @JvmStatic
    fun publishStutter(state: String, bursts60s: Float, worstMs: Float) {
        stState = state; stBursts = bursts60s; stWorst = worstMs
        stMs = System.currentTimeMillis()
        // SPLENDOR_P7_DEVICE_STUTTER_BEGIN
        // publishStutter previously wrote nowhere cross-process: the
        // stutter signal existed only in :survival memory, so the main
        // process could never see it. Mirror it into the merged device
        // snapshot so main-process FilterEngine reads it.
        updateDeviceState(stutterState = state)
        // SPLENDOR_P7_DEVICE_STUTTER_END
    }

    /** Stale-aware: older than 15s reads CALM. Same-process in-memory, never blocks. */
    @JvmStatic
    fun currentStutterState(): String =
        if (System.currentTimeMillis() - stMs < 15_000L) stState else "CALM"

    // ---- HISTORY (bounded append-only samples; trend analysis) ----
    // SPLENDOR_P8_HIST_BEGIN
    // Task 3: publishNet / publishActionWindow / publishDisplay /
    // publishLoadShed overwrote their single-line files with writeText,
    // so only the latest sample ever existed and trend analysis was
    // impossible (each perf_*.txt exactly one line, no trailing newline,
    // 2026-11-12 logs). The single-line files CANNOT simply be appended
    // to: currentNet() / currentLoadShed() / currentDeviceState() read
    // the WHOLE file with readText().split("|") and parse the LAST field
    // as the timestamp, so a second line would corrupt the timestamp
    // parse (p[5]/p[8] would become "<ts>\n<next value>" ->
    // toLongOrNull() null -> 0L) and the reader would keep returning the
    // OLDEST sample as if current. Fix: keep the single-line
    // current-value file byte-identical, and append the same line to a
    // sibling <name>_history.txt, rotated when it exceeds
    // HISTORY_MAX_BYTES (oldest lines dropped, newest kept). Readers
    // below parse the history files; FilterEngine's report consumes
    // them, so history is read, not merely written.
    private const val HISTORY_MAX_BYTES = 32_768L
    private const val HISTORY_KEEP_LINES = 200

    data class DisplayTelemetry(
        val frameGapMs: Float, val jankPerMin: Float, val stallMs: Float,
        val verdict: String, val updatedMs: Long
    )

    data class ActionWindowSample(val verdict: String, val detail: String, val updatedMs: Long)
    data class LoadShedSample(val level: String, val updatedMs: Long)

    /**
     * Append one sample line to a history file, rotating when over the cap.
     * Synchronized: publishers run on different threads (net probe,
     * lag-verdict, net-window, load-shed) and rotation rewrites the file.
     * Never throws: history must never break the live publish path.
     */
    @Synchronized
    private fun appendHistory(historyName: String, line: String) {
        ensureFile()
        try {
            val dir = file?.parentFile ?: return
            val hist = File(dir, historyName)
            hist.appendText(line + "\n")
            if (hist.length() > HISTORY_MAX_BYTES) {
                val lines = hist.readLines().filter { it.isNotBlank() }
                val keep = if (lines.size > HISTORY_KEEP_LINES) lines.takeLast(HISTORY_KEEP_LINES) else lines
                hist.writeText(keep.joinToString("\n", postfix = "\n"))
            }
        } catch (_: Throwable) { }
    }

    /** Last [maxLines] non-blank lines of a history file, oldest first. Empty when storage is not ready. */
    // B30-D3: this was the only history path NOT synchronized, while
    // appendHistory (same object monitor) rewrites the file with
    // writeText during rotation -- a concurrent readLines during the
    // truncate-and-rewrite could see an empty / half-written file and
    // the FilterEngine report would print a truncated trend for that
    // tick. Same monitor as appendHistory now, so a read either sees
    // the pre-rotation or the post-rotation file, never a torn one.
    // (Report path only -- never called from the frame path -- so the
    // lock cannot stall capture. The single-line current* readers stay
    // lock-free by design; their torn reads are neutralised by the
    // timestamp validation in B30-D1/B30-D2 plus the size/toXOrNull
    // guards, instead of taking the same lock on the frame path.)
    @Synchronized
    private fun readHistoryLines(historyName: String, maxLines: Int): List<String> {
        ensureFile()
        val n = if (maxLines < 1) 1 else maxLines
        return try {
            val dir = file?.parentFile ?: return emptyList()
            val hist = File(dir, historyName)
            if (!hist.exists()) return emptyList()
            hist.readLines().filter { it.isNotBlank() }.takeLast(n)
        } catch (_: Throwable) { emptyList() }
    }

    /** Recent net samples, oldest first. Skips malformed lines. */
    @JvmStatic
    fun recentNetHistory(maxLines: Int = 10): List<NetTelemetry> =
        readHistoryLines("perf_telemetry_history.txt", maxLines).mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 6) null else NetTelemetry(
                p[0].toFloatOrNull() ?: return@mapNotNull null,
                p[1].toFloatOrNull() ?: return@mapNotNull null,
                p[2], p[3], p[4], p[5].toLongOrNull() ?: 0L)
        }

    /** Recent display samples, oldest first. Skips malformed lines. */
    @JvmStatic
    fun recentDisplayHistory(maxLines: Int = 10): List<DisplayTelemetry> =
        readHistoryLines("perf_display_history.txt", maxLines).mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 5) null else DisplayTelemetry(
                p[0].toFloatOrNull() ?: return@mapNotNull null,
                p[1].toFloatOrNull() ?: return@mapNotNull null,
                p[2].toFloatOrNull() ?: return@mapNotNull null,
                p[3], p[4].toLongOrNull() ?: 0L)
        }

    /** Recent action-window samples, oldest first. Skips malformed lines. */
    @JvmStatic
    fun recentActionWindowHistory(maxLines: Int = 10): List<ActionWindowSample> =
        readHistoryLines("perf_window_history.txt", maxLines).mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 3) null else ActionWindowSample(p[0], p[1], p[2].toLongOrNull() ?: 0L)
        }

    /** Recent load-shed samples, oldest first. Skips malformed lines. */
    @JvmStatic
    fun recentLoadShedHistory(maxLines: Int = 10): List<LoadShedSample> =
        readHistoryLines("perf_loadshed_history.txt", maxLines).mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 2) null else LoadShedSample(p[0], p[1].toLongOrNull() ?: 0L)
        }
    // SPLENDOR_P8_HIST_END

    // ---- FLEET (adapter health, cross-process) ----
    // L16 (batch 1): AdapterHealthCounts / AdapterStatusRegistry are
    // plain in-process objects — the :survival watchdog writes them, the
    // main-process dashboard reads its own never-updated copy (NO DATA
    // YET / ADAPTERS: NONE). Same pattern as the f58 bus mirror: the
    // :survival writer publishes the fleet into this file-backed
    // history, and MainProcessBusMirror copies the freshest sample into
    // the main-process registries. Line format:
    // active|degraded|offline|adapter1=STATUS,adapter2=STATUS|updatedMs
    // (adapter names/statuses are pipe-safe internal constants; the whole
    // adapters field is pipe-sanitised anyway).
    data class FleetSample(
        val active: Int,
        val degraded: Int,
        val offline: Int,
        val adapters: String,
        val updatedMs: Long
    )

    /** Pure line parser (harness-pinned): null for any malformed line, never throws. */
    fun parseFleetLine(line: String): FleetSample? {
        val p = line.split("|")
        if (p.size < 5) return null
        val active = p[0].toIntOrNull() ?: return null
        val degraded = p[1].toIntOrNull() ?: return null
        val offline = p[2].toIntOrNull() ?: return null
        if (active < 0 || degraded < 0 || offline < 0) return null
        val updatedMs = p[4].toLongOrNull() ?: return null
        return FleetSample(active, degraded, offline, p[3], updatedMs)
    }

    @JvmStatic
    fun publishFleet(active: Int, degraded: Int, offline: Int, adapters: String) {
        appendHistory(
            "perf_fleet_history.txt",
            "$active|$degraded|$offline|${pipeSafe(adapters)}|${System.currentTimeMillis()}"
        )
    }

    /** Recent fleet samples, oldest first. Skips malformed lines. */
    @JvmStatic
    fun recentFleetHistory(maxLines: Int = 10): List<FleetSample> =
        readHistoryLines("perf_fleet_history.txt", maxLines).mapNotNull { parseFleetLine(it) }

    // ---- DEVICE STATE (lag/stutter/thermal/memory/battery/brake, cross-process) ----
    // SPLENDOR_P7_XPROC_DEVICE_BEGIN
    // The :survival publishers write these signals to AdapterSignalBus, a
    // plain in-process object: a second process has its own copy and never
    // sees them. FilterEngine runs in the main process and read only the
    // bus, so every report block was UNKNOWN / 1.00x while the :survival
    // side was publishing real values (perf_display.txt already showed
    // SMOOTH, perf_telemetry.txt already showed RTT 50ms GOOD on
    // 2026-11-12). The publishers below mirror their latest values into
    // ONE merged file (perf_device.txt); main-process readers use
    // currentDeviceState(), which mirrors the proven currentNet() /
    // currentLoadShed() pattern: fresh in-memory value wins, else a
    // throttled file read. Stale (>15s) snapshots are reported, never
    // silently treated as fresh: callers check updatedMs.
    data class DeviceState(
        val lagVerdict: String, val stutterState: String, val thermalStatus: Int,
        val memoryTier: String, val memoryAvailMb: Long,
        val batteryLevel: Int, val batteryCharging: Boolean,
        val executionBrake: Int, val updatedMs: Long
    )

    // B30-D6: batteryCharging was `true` in the unknown sentinel, so a
    // device that never published battery was reported as charging
    // (FilterEngine prints "Battery: -1% charging=true"). Unknown is
    // not charging: `false` is the conservative side -- nothing in the
    // repo acts on the charging flag (report line only), and the
    // CpuGovernorEngine default is also false. No tri-state Boolean
    // exists here; the -1 level already marks the reading unknown.
    private val DEVICE_EMPTY = DeviceState("UNKNOWN", "UNKNOWN", -1, "UNKNOWN", -1L, -1, false, 0, 0L)

    @Volatile private var device: DeviceState = DEVICE_EMPTY
    @Volatile private var devFile: File? = null
    @Volatile private var devWriteAt = 0L
    @Volatile private var devReadAt = 0L
    @Volatile private var devReadCache: DeviceState = DEVICE_EMPTY
    private const val DEVICE_STALE_MS = 15_000L
    private const val DEVICE_WRITE_THROTTLE_MS = 500L
    private const val DEVICE_READ_THROTTLE_MS = 2_000L

    @Synchronized
    private fun updateDeviceState(
        lagVerdict: String? = null, stutterState: String? = null, thermalStatus: Int? = null,
        memoryTier: String? = null, memoryAvailMb: Long? = null,
        batteryLevel: Int? = null, batteryCharging: Boolean? = null,
        executionBrake: Int? = null
    ) {
        ensureFile()
        val now = System.currentTimeMillis()
        // Stutter is event-driven (BurstForensicsEngine.record fires only
        // on a real burst), so on a calm device no stutter publish ever
        // arrives. Fall back to the registry's own stutter semantic --
        // currentStutterState() reads CALM when no burst is recent -- so a
        // calm device is reported CALM instead of UNKNOWN forever.
        val stutter = stutterState ?: if (device.stutterState == "UNKNOWN") currentStutterState() else device.stutterState
        device = device.copy(
            lagVerdict = lagVerdict ?: device.lagVerdict,
            stutterState = stutter,
            thermalStatus = thermalStatus ?: device.thermalStatus,
            memoryTier = memoryTier ?: device.memoryTier,
            memoryAvailMb = memoryAvailMb ?: device.memoryAvailMb,
            batteryLevel = batteryLevel ?: device.batteryLevel,
            batteryCharging = batteryCharging ?: device.batteryCharging,
            executionBrake = executionBrake ?: device.executionBrake,
            updatedMs = now
        )
        if (now - devWriteAt < DEVICE_WRITE_THROTTLE_MS) return
        devWriteAt = now
        try {
            if (devFile == null) devFile = file?.parentFile?.let { File(it, "perf_device.txt") }
            val d = device
            devFile?.writeText(d.lagVerdict + "|" + d.stutterState + "|" + d.thermalStatus + "|" +
                d.memoryTier + "|" + d.memoryAvailMb + "|" + d.batteryLevel + "|" +
                d.batteryCharging + "|" + d.executionBrake + "|" + d.updatedMs)
        } catch (_: Throwable) { }
    }

    /** Non-blocking. Fresh in-memory value wins, else a throttled file read. */
    @JvmStatic
    @Synchronized
    fun currentDeviceState(): DeviceState {
        val now = System.currentTimeMillis()
        if (now - device.updatedMs < DEVICE_STALE_MS) return device
        if (now - devReadAt < DEVICE_READ_THROTTLE_MS) return devReadCache
        devReadAt = now
        ensureFile()
        try {
            if (devFile == null) devFile = file?.parentFile?.let { File(it, "perf_device.txt") }
            val p = (devFile?.takeIf { it.exists() }?.readText() ?: return devReadCache).split("|")
            if (p.size >= 9) devReadCache = DeviceState(
                p[0], p[1], p[2].toIntOrNull() ?: -1, p[3], p[4].toLongOrNull() ?: -1L,
                p[5].toIntOrNull() ?: -1, p[6] == "true",
                p[7].toIntOrNull() ?: 0, p[8].toLongOrNull() ?: 0L)
        } catch (_: Throwable) { }
        return devReadCache
    }

    // ---- DEVICE-STATE PUBLISHERS (called next to the bus publish) ----
    @JvmStatic fun publishDeviceThermal(status: Int) { updateDeviceState(thermalStatus = status) }
    @JvmStatic fun publishDeviceMemory(tier: String, availMb: Long) { updateDeviceState(memoryTier = tier, memoryAvailMb = availMb) }
    @JvmStatic fun publishDeviceBattery(level: Int, charging: Boolean) { updateDeviceState(batteryLevel = level, batteryCharging = charging) }
    @JvmStatic fun publishDeviceBrake(level: Int) { updateDeviceState(executionBrake = level) }

    // ---- FILTER AGGRESSION (FilterEngine in main process -> :survival consumers) ----
    // SPLENDOR_P7_XPROC_AGGRO_REG_BEGIN
    // FilterEngine publishes its multiplier here as well as to the bus, so
    // the :survival readers (LagVerdictEngine, BurstForensicsEngine,
    // SpeedCompensationContributor) see it. Stale (>15s) reads fall back
    // to 1.0f: a dead FilterEngine must never leave a stale extremist push.
    @Volatile private var aggro = 1.0f
    @Volatile private var aggroMs = 0L
    @Volatile private var aggroFile: File? = null
    @Volatile private var aggroReadAt = 0L
    @Volatile private var aggroReadCache = 1.0f
    @Volatile private var aggroReadCacheMs = 0L

    @JvmStatic
    @Synchronized
    fun publishFilterAggression(multiplier: Float) {
        ensureFile()
        val v = multiplier.coerceIn(1.0f, 3.0f)
        aggro = v
        aggroMs = System.currentTimeMillis()
        try {
            if (aggroFile == null) aggroFile = file?.parentFile?.let { File(it, "perf_aggression.txt") }
            aggroFile?.writeText(v.toString() + "|" + aggroMs)
        } catch (_: Throwable) { }
    }

    /** Non-blocking. Fresh in-memory value wins, else a throttled file read. */
    @JvmStatic
    @Synchronized
    fun currentFilterAggression(): Float {
        val now = System.currentTimeMillis()
        if (now - aggroMs < 15_000L) return aggro
        if (now - aggroReadAt < 2_000L) return if (now - aggroReadCacheMs < 15_000L) aggroReadCache else 1.0f
        aggroReadAt = now
        ensureFile()
        try {
            if (aggroFile == null) aggroFile = file?.parentFile?.let { File(it, "perf_aggression.txt") }
            val p = (aggroFile?.takeIf { it.exists() }?.readText() ?: return 1.0f).split("|")
            if (p.size >= 2) {
                aggroReadCache = p[0].toFloatOrNull()?.coerceIn(1.0f, 3.0f) ?: 1.0f
                aggroReadCacheMs = p[1].toLongOrNull() ?: 0L
            }
        } catch (_: Throwable) { }
        return if (now - aggroReadCacheMs < 15_000L) aggroReadCache else 1.0f
    }
    // SPLENDOR_P7_XPROC_DEVICE_END

    // ---- CROWDING ZONE (No68-C-D6: main-process detector -> :survival lag verdict) ----
    // CrowdingZoneDetector publishes to the in-process AdapterSignalBus, but
    // LagVerdictEngine runs in :survival (PerformanceEngineService), where
    // that bus copy is never published — so the "+2 CHOKING confirmation
    // polls when crowded" protection could never fire. Mirror the zone here
    // on the same file-backed pattern as filter aggression. The publisher
    // writes change-only (CrowdingZoneDetector tracks the last value), so
    // this is at most one small write per crowded-phase transition, never
    // per-frame I/O. Stale (>5 s) reads as not-crowded: a crowded phase is
    // transient, and a dead main process must never leave :survival
    // demanding extra confirmation forever.
    @Volatile private var crowdZone = false
    @Volatile private var crowdMs = 0L
    @Volatile private var crowdFile: File? = null
    @Volatile private var crowdReadAt = 0L
    @Volatile private var crowdReadCache = false
    @Volatile private var crowdReadCacheMs = 0L
    private const val CROWD_STALE_MS = 5_000L

    @JvmStatic
    @Synchronized
    fun publishCrowdingZone(zone: Boolean) {
        ensureFile()
        crowdZone = zone
        crowdMs = System.currentTimeMillis()
        try {
            if (crowdFile == null) crowdFile = file?.parentFile?.let { File(it, "perf_crowding.txt") }
            crowdFile?.writeText(zone.toString() + "|" + crowdMs)
        } catch (_: Throwable) { }
    }

    /** Non-blocking. Fresh in-memory value wins, else a throttled file read. Stale reads false. */
    @JvmStatic
    @Synchronized
    fun currentCrowdingZone(): Boolean {
        val now = System.currentTimeMillis()
        if (now - crowdMs < CROWD_STALE_MS) return crowdZone
        if (now - crowdReadAt < 2_000L) return if (now - crowdReadCacheMs < CROWD_STALE_MS) crowdReadCache else false
        crowdReadAt = now
        ensureFile()
        try {
            if (crowdFile == null) crowdFile = file?.parentFile?.let { File(it, "perf_crowding.txt") }
            val p = (crowdFile?.takeIf { it.exists() }?.readText() ?: return false).split("|")
            if (p.size >= 2) {
                crowdReadCache = p[0].toBooleanStrictOrNull() ?: false
                crowdReadCacheMs = p[1].toLongOrNull() ?: 0L
            }
        } catch (_: Throwable) { }
        return if (now - crowdReadCacheMs < CROWD_STALE_MS) crowdReadCache else false
    }
}
