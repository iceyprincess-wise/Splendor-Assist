package com.assistant




































import com.assistant.diagnostic.RuntimeLogger










import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

















object RadioKeepAliveEngine {

    // Fixed literal floor (not set from outside the code)
    private val FLOOR_S: Int get() = 4

    @Volatile private var running = false
    @Volatile private var sent = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                var cadence = CarrierProfileEngine.keepAliveS
                if (CarrierProfileEngine.current.name != "WIFI") {
                    if (NetProbeEngine.quality != "GOOD") cadence = maxOf(FLOOR_S, cadence / 2)
                    try {
                        // Batch 6b: the keepalive goes to the
                        // fastest-wins resolver NetProbeEngine selected
                        // this session — not the hardcoded 8.8.8.8. The
                        // keepalive is still a 1-byte UDP poke that
                        // keeps the radio awake; the resolver choice is
                        // the only thing that changed, and it is the
                        // first live consumer of the selection.
                        DatagramSocket().use { s ->
                            s.send(DatagramPacket(ByteArray(1), 1, InetAddress.getByName(NetProbeEngine.preferredResolver), 53))
                        }
                        if (++sent % 20L == 0L)
                            RuntimeLogger.log("keepalive sent=" + sent + " cadence=" + cadence + "s resolver=" + NetProbeEngine.preferredResolver, "NET")
                    } catch (_: Throwable) { }
                }
                try { Thread.sleep(if (cadence > 0) cadence * 1000L else 1000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-keepalive"; t.start()
    }

    fun stop() { running = false }
}

/* ========
SpikeBurstEngine
======== */
// V3 INSTANT-REFLEX

/**
 * When the sentinel flags congestion, this fires a rapid probe burst to map
 * the spike (depth + floor) and then watches for recovery at 1s resolution -
 * so the runtime knows the link is clean again SECONDS before the slow
 * cadence would notice. V3: burst size, burst gap and the clean line are
 * fixed literals in code (BURST_SAMPLES, BURST_GAP_MS, CLEAN_MULT) — none
 * is set from outside the code.
 */
