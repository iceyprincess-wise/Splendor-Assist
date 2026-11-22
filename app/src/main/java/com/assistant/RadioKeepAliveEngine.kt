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

    // D18 (Phase R): the keepalive thread is kept so stop() can interrupt
    // it and start() can join the old one instead of running two senders.
    // Reachability: PerformanceEngineService ignites/stops it; the service
    // is relaunched in-process on a stale heartbeat (same as Phase L L-1).
    private var keepAliveThread: Thread? = null

    // Batch booster: match-mode boost, driven by FreeBoosterController.
    // While a match is on the keepalive runs at the 4 s floor regardless
    // of probe quality (today it sleeps 8/10/12 s when GOOD and halves
    // only when dirty). The send path, the fastest-wins resolver target
    // and the WIFI skip are unchanged; only the cadence moves.
    @Volatile var matchBoost = false; private set

    fun setMatchBoost(boost: Boolean) { matchBoost = boost }

    fun start() {
        if (running) return
        running = true
        val old = keepAliveThread
        if (old != null && old.isAlive) {
            try { old.join(500L) } catch (_: Throwable) {}
            if (old.isAlive) return
        }
        val t = Thread {
            while (running) {
                var cadence = CarrierProfileEngine.keepAliveS
                if (CarrierProfileEngine.current.name != "WIFI") {
                    if (matchBoost) cadence = FLOOR_S
                    else if (NetProbeEngine.quality != "GOOD") cadence = maxOf(FLOOR_S, cadence / 2)
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
        keepAliveThread = t
    }

    fun stop() {
        running = false
        keepAliveThread?.interrupt()
    }
}
