class FrameDropStabilizer : Choreographer.FrameCallback {

    private var lastFrameNanos: Long = 0L
    private var onDropCallback: (() -> Unit)? = null

    // Dynamic threshold synced to physical refresh boundaries
    private var dropThresholdNanos: Long = 0L
    
    // Memory-efficient state lock
    private var isRunning: Boolean = false
    
    private val choreographer: Choreographer = Choreographer.getInstance()

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        // 20% variance allowance to tolerate standard OS micro-stutters without false triggers
        const val VARIANCE_TOLERANCE_MULTIPLIER = 1.2f 
    }

    private fun calculateThreshold(refreshRate: Float): Long {
        val frameTimeNanos = (NANOS_PER_SECOND / refreshRate).toLong()
        return (frameTimeNanos * VARIANCE_TOLERANCE_MULTIPLIER).toLong()
    }

    /**
     * Bootstraps the stabilizer loop.
     * @param targetRefreshRate The expected physical display refresh rate (e.g., 60f, 90f, 120f)
     * @param onDrop High-priority callback executed on frame desync
     */
    fun start(targetRefreshRate: Float = 60f, onDrop: () -> Unit) {
        if (isRunning) return
        
        this.dropThresholdNanos = calculateThreshold(targetRefreshRate)
        this.onDropCallback = onDrop
        this.isRunning = true
        this.lastFrameNanos = 0L
        
        // Immediately hook into the rendering pipeline
        choreographer.postFrameCallback(this)
    }

    fun stop() {
        if (!isRunning) return
        this.isRunning = false
        choreographer.removeFrameCallback(this)
        this.onDropCallback = null
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return

        if (lastFrameNanos != 0L) {
            val deltaNanos = frameTimeNanos - lastFrameNanos

            // Frame drop detection using pre-calculated physics boundaries
            if (deltaNanos > dropThresholdNanos) {
                onDropCallback?.invoke()
            }
        }

        lastFrameNanos = frameTimeNanos
        
        // Self-perpetuating loop utilizing 'this' prevents object reallocation overhead
        choreographer.postFrameCallback(this)
    }
}
/* ======
FrameDropStabilizer Anchor
====== */

/* ========
LatencyDefeatingInputEngine
======== */
/**
 * High-performance, low-overhead input injector optimized for continuous execution.
 * LETHAL FIX: Eliminates aggressive micro-jitter duplication and allows uninhibited 
 * coordinate mapping directly to the CentralExecutionBus layer.
 */
