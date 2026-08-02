package com.assistant.adapter.net

import com.assistant.diagnostic.RuntimeLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Keeps the cellular radio out of deep sleep between plays with a 1-byte UDP
 * packet on the carrier profile cadence - the first pass of a counterattack
 * stops paying radio wake-up tax. Skipped on WIFI.
 */
object RadioKeepAliveEngine {

    @Volatile private var running = false
    @Volatile private var sent = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                val p = CarrierProfileEngine.current
                if (p.name != "WIFI") {
                    try {
                        DatagramSocket().use { s ->
                            s.send(DatagramPacket(ByteArray(1), 1, InetAddress.getByName("8.8.8.8"), 53))
                        }
                        if (++sent % 20L == 0L)
                            RuntimeLogger.log("keepalive sent=" + sent + " cadence=" + p.keepAliveSeconds + "s", "NET")
                    } catch (_: Throwable) { }
                }
                try { Thread.sleep(p.keepAliveSeconds * 1000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-keepalive"; t.start()
    }

    fun stop() { running = false }
}
