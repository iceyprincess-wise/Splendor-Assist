// Guard header removed under Ed's explicit override for this file only
// (2026-11-12, Patch 5): Ed overrode the guard lock; this file's only
// change is removal of 2 unused imports (no logic touched).
package com.assistant.diagnostic

// F41: this file was a dead stub - startMonitoring() did nothing and had
// ZERO callers repo-wide, while its comment pointed at
// PerformanceEngineService as the owner of the loop. That half is true:
// PerformanceEngineService really does own the monitoring loop (15 engines
// + scheduler + 10s health heartbeat). What was missing was this object's
// own purpose. Per the standing rule (wire it if its purpose is real),
// MachineLedger is now the lightweight JVM-level machine sample ledger:
// a bounded ring of Runtime samples (heap + CPUs) that any engine can read
// without touching Android APIs. Pure java.lang.Runtime, so it runs on the
// plain JVM and is unit-testable. No in-repo caller is added here - the
// natural call site is PerformanceEngineService.onCreate (start) /
// onDestroy (stop); that is a later-round wiring step for the app module,
// recorded as an open item, not silently assumed.
object MachineLedger {

    // F41-D2: a ledger with no read API and no stop path is unobservable
    // even when started. snapshot()/latest()/sampleCount()/stopMonitoring()
    // are the read/stop surface; startMonitoring() is idempotent so a
    // second call cannot spawn a second sampler thread (F41-D3).
    data class MachineSample(
        val atMs: Long,
        val freeMemoryBytes: Long,
        val totalMemoryBytes: Long,
        val maxMemoryBytes: Long,
        val availableProcessors: Int
    )

    private const val MAX_SAMPLES = 120
    private const val SAMPLE_INTERVAL_MS = 5_000L

    private val samples: ArrayDeque<MachineSample> = ArrayDeque()

    @Volatile
    private var monitoring = false

    private var samplerThread: Thread? = null

    @Synchronized
    fun startMonitoring() {
        if (monitoring) return
        monitoring = true
        val thread = Thread({ samplerLoop() }, "MachineLedger-Sampler").apply { isDaemon = true }
        samplerThread = thread
        thread.start()
    }

    @Synchronized
    fun stopMonitoring() {
        monitoring = false
        samplerThread?.interrupt()
        samplerThread = null
    }

    fun isMonitoring(): Boolean = monitoring

    @Synchronized
    fun snapshot(): List<MachineSample> = samples.toList()

    @Synchronized
    fun latest(): MachineSample? = samples.lastOrNull()

    @Synchronized
    fun sampleCount(): Int = samples.size

    @Synchronized
    fun recordSample(sample: MachineSample) {
        samples.addLast(sample)
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    private fun samplerLoop() {
        while (monitoring) {
            try {
                recordSample(collect())
            } catch (_: Throwable) {
                // A failed sample must never kill the ledger thread.
            }
            try {
                Thread.sleep(SAMPLE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun collect(): MachineSample {
        val runtime = Runtime.getRuntime()
        return MachineSample(
            atMs = System.currentTimeMillis(),
            freeMemoryBytes = runtime.freeMemory(),
            totalMemoryBytes = runtime.totalMemory(),
            maxMemoryBytes = runtime.maxMemory(),
            availableProcessors = runtime.availableProcessors()
        )
    }
}
