package com.assistant

import com.assistant.diagnostic.RuntimeLogger
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

object PacketLossProbeEngine {

    private val RESOLVERS = listOf("8.8.8.8", "1.1.1.1")

    // Fixed literals (not set from outside the code)
    private val ROUND_MS: Long get() = 4000L
    private val PER_ROUND: Int get() = 4
    private val REPLY_TIMEOUT_MS: Int get() = 700
    private val ALPHA: Float get() = 0.3f
    private val GAP_MS: Long get() = 80L

    @Volatile private var running = false
    @Volatile var lossPct = 0f; private set
    @Volatile private var rounds = 0L
    @Volatile private var seq = 0L
    // Phase P (P-1): D18 double-start fix. The probe thread used to be a
    // local never stored, and stop() only cleared `running` — a stop/start
    // inside the 4 s round sleep left the old thread alive, it woke, saw
    // running==true (set by the new start) and kept probing: TWO live UDP
    // probers writing lossPct. start() now joins the old thread (500 ms)
    // and gives up rather than running two; stop() interrupts the sleep.
    private var probeThread: Thread? = null

    fun start() {
        if (running) return
        val old = probeThread
        if (old != null && old.isAlive) {
            try { old.join(500L) } catch (_: Throwable) { }
            if (old.isAlive) return
        }
        running = true
        val t = Thread {
            while (running) {
                try {
                    val perRound = if (PER_ROUND < 1) 1 else PER_ROUND
                    val gap = GAP_MS
                    var ok = 0
                    repeat(perRound) {
                        if (queryOnce()) ok++
                        if (gap > 0) try { Thread.sleep(gap) } catch (_: Throwable) { }
                    }
                    val roundLoss = (perRound - ok) * 100f / perRound
                    val a = ALPHA
                    lossPct = lossPct * (1 - a) + roundLoss * a
                    rounds++
                    if (roundLoss >= 50f)
                        RuntimeLogger.log("LOSS SPIKE " + String.format("%.0f", roundLoss) +
                            "% this round (avg " + String.format("%.0f", lossPct) + "%)", "NETLOSS")
                    else if (rounds % 15L == 0L)
                        RuntimeLogger.log("loss avg=" + String.format("%.0f", lossPct) + "% rounds=" + rounds, "NETLOSS")
                } catch (_: Throwable) { }
                val nap = ROUND_MS
                try { Thread.sleep(if (nap > 0) nap else 1L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-loss"; t.start()
        probeThread = t
    }

    fun stop() {
        running = false
        try { probeThread?.interrupt() } catch (_: Throwable) { }
    }

    private fun queryOnce(): Boolean = try {
        val host = RESOLVERS[(seq++ % RESOLVERS.size).toInt()]
        val id = Random.nextInt(0x0000FFFF)
        val q = dnsQuery(id)
        DatagramSocket().use { s ->
            s.soTimeout = REPLY_TIMEOUT_MS
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(host), 53))
            val buf = ByteArray(512)
            val resp = DatagramPacket(buf, buf.size)
            s.receive(resp)
            resp.length >= 2 &&
                ((buf[0].toInt() and 0xFF) shl 8 or (buf[1].toInt() and 0xFF)) == id
        }
    } catch (_: Throwable) { false }

    private fun dnsQuery(id: Int): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        b.write(id shr 8); b.write(id and 0xFF)
        b.write(0x01); b.write(0x00)          // recursion desired
        b.write(0x00); b.write(0x01)          // 1 question
        b.write(ByteArray(6))                  // no answer/auth/extra
        for (label in listOf("www", "google", "com")) {
            b.write(label.length); b.write(label.toByteArray())
        }
        b.write(0)
        b.write(0x00); b.write(0x01)           // type A
        b.write(0x00); b.write(0x01)           // class IN
        return b.toByteArray()
    }
}
