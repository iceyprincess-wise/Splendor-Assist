package com.assistant




































import com.assistant.diagnostic.RuntimeLogger










import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

















object RadioKeepAliveEngine {

    // ADMIN-TUNABLE (default = original hard-coded value)
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
                        DatagramSocket().use { s ->
                            s.send(DatagramPacket(ByteArray(1), 1, InetAddress.getByName("8.8.8.8"), 53))
                        }
                        if (++sent % 20L == 0L)
                            RuntimeLogger.log("keepalive sent=" + sent + " cadence=" + cadence + "s", "NET")
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
 * cadence would notice. V3: burst size, burst gap and the clean line are all
 * admin-tunable, and the clean line follows the override-aware baseline.
 */
