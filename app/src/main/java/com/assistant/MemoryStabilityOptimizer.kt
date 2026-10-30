class MemoryStabilityOptimizer(
    private val context: Context,
    private val memoryStateListener: MemoryStateListener? = null
) : ComponentCallbacks2 {

    interface MemoryStateListener {
        fun onMemoryCritical(availableMegabytes: Long)
        fun onMemoryRestored()
    }

    private val isInitialized = AtomicBoolean(false)
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitorJob: Job? = null

    fun initialize() {
        if (isInitialized.compareAndSet(false, true)) {
            context.registerComponentCallbacks(this)
            startActiveMonitoring()
        }
    }

    fun terminate() {
        if (isInitialized.compareAndSet(true, false)) {
            context.unregisterComponentCallbacks(this)
            monitorJob?.cancel()
            coroutineScope.cancel()
        }
    }

    private fun startActiveMonitoring() {
        monitorJob = coroutineScope.launch {
            while (isActive) {
                checkMemoryState()
                // Adaptive poll rate: High frequency to catch spikes before the OS forces a trim
                delay(3000L) 
            }
        }
    }

    private fun checkMemoryState() {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        // Calculate a critical threshold (15% safety buffer above the OS fatal kill threshold)
        val criticalThreshold = memoryInfo.threshold + (memoryInfo.threshold * 0.15)
        val availableMb = memoryInfo.availMem / (1024 * 1024)

        if (memoryInfo.availMem <= criticalThreshold || memoryInfo.lowMemory) {
            executeAggressiveCleanup()
            memoryStateListener?.onMemoryCritical(availableMb)
        } else {
            memoryStateListener?.onMemoryRestored()
        }
    }

    override fun onTrimMemory(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                executeAggressiveCleanup()
                
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                memoryStateListener?.onMemoryCritical(memoryInfo.availMem / (1024 * 1024))
            }
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // Mild background cleanup without aggressive yields
                coroutineScope.launch(Dispatchers.IO) {
                    System.gc()
                }
            }
        }
    }

    override fun onLowMemory() {
        executeAggressiveCleanup()
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        memoryStateListener?.onMemoryCritical(memoryInfo.availMem / (1024 * 1024))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // No memory action required for configuration changes
    }

    /**
     * Executes Garbage Collection on a dedicated I/O thread.
     * EXTREMELY IMPORTANT: Never call Runtime.getRuntime().gc() on the main thread
     * in a high-frequency injection environment. It blocks the UI thread, causing
     * coordinate translation stutter and missing server-tick boundaries.
     */
    private fun executeAggressiveCleanup() {
        coroutineScope.launch(Dispatchers.IO) {
            System.gc()
            System.runFinalization()
            // Suggest the OS to yield thread execution to allow the GC to finalize
            Thread.yield()
        }
    }
}
/* ======
MemoryStabilityOptimizer Anchor
====== */

/* ========
NativePipelineCache
======== */
