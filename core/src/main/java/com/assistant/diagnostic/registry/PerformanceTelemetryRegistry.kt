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

    @JvmStatic
    @Synchronized
    fun publishNet(rttMs: Float, jitterMs: Float, quality: String, carrier: String, transport: String) {
        val t = NetTelemetry(rttMs, jitterMs, quality, carrier, transport, System.currentTimeMillis())
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
        if (now - lastRead < 2000L) return readCache
        lastRead = now
        try {
            val p = (file?.takeIf { it.exists() }?.readText() ?: return readCache).split("|")
            if (p.size >= 6) readCache = NetTelemetry(
                p[0].toFloatOrNull() ?: 0f, p[1].toFloatOrNull() ?: 0f,
                p[2], p[3], p[4], p[5].toLongOrNull() ?: 0L)
        } catch (_: Throwable) { }
        return readCache
    }

    // ---- ACTION WINDOW (net stack verdict: GO / CAUTION / HOLD) ----
    @Volatile private var window = "UNKNOWN"
    @Volatile private var windowDetail = ""
    @Volatile private var windowMs = 0L
    @Volatile private var wfile: File? = null

    @JvmStatic
    fun publishActionWindow(verdict: String, detail: String) {
        window = verdict; windowDetail = detail; windowMs = System.currentTimeMillis()
        try {
            if (wfile == null) wfile = file?.parentFile?.let { File(it, "perf_window.txt") }
            wfile?.writeText(verdict + "|" + detail + "|" + windowMs)
        } catch (_: Throwable) { }
        // SPLENDOR_P8_HIST_WINDOW
        // Task 3: keep the single-line current value above AND append the
        // same sample to the bounded history file (trend analysis). This
        // also gives perf_window.txt its first registry reader (via the
        // history reader below); previously no reader existed at all.
        appendHistory("perf_window_history.txt", verdict + "|" + detail + "|" + windowMs)
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

    @JvmStatic
    fun publishLoadShed(level: String) {
        shed = level; shedMs = System.currentTimeMillis()
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
        if (now - shedReadAt < 2_000L) return shedReadCache
        shedReadAt = now
        try {
            if (sfile == null) sfile = file?.parentFile?.let { File(it, "perf_loadshed.txt") }
            val f = sfile?.takeIf { it.exists() } ?: return shedReadCache
            val p = f.readText().split("|")
            if (p.isNotEmpty() && p[0].isNotEmpty()) shedReadCache = p[0]
        } catch (_: Throwable) {
        }
        return shedReadCache
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
    private fun readHistoryLines(historyName: String, maxLines: Int): List<String> {
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

    private val DEVICE_EMPTY = DeviceState("UNKNOWN", "UNKNOWN", -1, "UNKNOWN", -1L, -1, true, 0, 0L)

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
}
