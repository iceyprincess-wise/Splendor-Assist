package com.assistant.adapter.net

// V2 PROACTIVE
import com.assistant.admin.AdminConfigStore
import com.assistant.diagnostic.RuntimeLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * V2: cadence adapts - when the link is dirty the radio is pinned at high
 * power MORE often (every profile/2 s, floor 4s), because a dirty link plus
 * a sleepy radio compounds into the worst first-touch latency.
 */
object RadioKeepAliveEngine {

    // ADMIN-TUNABLE (default = original hard-coded value)
    private val FLOOR_S: Int get() = AdminConfigStore.getInt("net.keepalive.floor_s", 4)

    @Volatile private var running = false
    @Volatile private var sent = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                val p = CarrierProfileEngine.current
                var cadence = p.keepAliveSeconds
                if (p.name != "WIFI") {
                    if (NetProbeEngine.quality != "GOOD") cadence = maxOf(FLOOR_S, p.keepAliveSeconds / 2)
                    try {
                        DatagramSocket().use { s ->
                            s.send(DatagramPacket(ByteArray(1), 1, InetAddress.getByName("8.8.8.8"), 53))
                        }
                        if (++sent % 20L == 0L)
                            RuntimeLogger.log("keepalive sent=" + sent + " cadence=" + cadence + "s", "NET")
                    } catch (_: Throwable) { }
                }
                try { Thread.sleep(cadence * 1000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-keepalive"; t.start()
    }

    fun stop() { running = false }
}
