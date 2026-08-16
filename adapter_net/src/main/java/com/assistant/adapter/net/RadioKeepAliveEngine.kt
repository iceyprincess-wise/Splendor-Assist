package com.assistant.adapter.net

// V3 INSTANT-REFLEX
import com.assistant.diagnostic.RuntimeLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * V2: cadence adapts - when the link is dirty the radio is pinned at high
 * power MORE often (every rhythm/2 s, floor = admin value), because a dirty
 * link plus a sleepy radio compounds into the worst first-touch latency.
 * V3: the base rhythm itself is override-aware (net.profile.keepalive_s),
 * so the admin controls both the rhythm and the floor.
 */
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
