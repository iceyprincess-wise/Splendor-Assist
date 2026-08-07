package com.assistant.adapter.net

// V2 PROACTIVE
import com.assistant.diagnostic.RuntimeLogger
import com.assistant.diagnostic.admin.AdminConfigStore
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

/**
 * Measures PACKET LOSS - the metric TCP probes cannot see. Sends real UDP DNS
 * queries and counts replies. Loss is what actually eats passes in eFootball:
 * a lost packet is a lost input, not a late one.
 */
object PacketLossProbeEngine {

    private val ROUND_MS get() = AdminConfigStore.getMs("loss_round_ms")
    private val PER_ROUND get() = AdminConfigStore.getInt("loss_per_round")
    private val REPLY_TIMEOUT_MS get() = AdminConfigStore.getInt("loss_reply_timeout_ms")
    private val ALPHA get() = AdminConfigStore.get("loss_alpha")

    @Volatile private var running = false
    @Volatile var lossPct = 0f; private set
    @Volatile private var rounds = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                try {
                    val perRound = PER_ROUND
                    var ok = 0
                    repeat(perRound) {
                        if (queryOnce()) ok++
                        try { Thread.sleep(80) } catch (_: Throwable) { }
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
                try { Thread.sleep(ROUND_MS) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-loss"; t.start()
    }

    fun stop() { running = false }

    private fun queryOnce(): Boolean = try {
        val id = Random.nextInt(0x0000FFFF)
        val q = dnsQuery(id)
        DatagramSocket().use { s ->
            s.soTimeout = REPLY_TIMEOUT_MS
            s.send(DatagramPacket(q, q.size, InetAddress.getByName("8.8.8.8"), 53))
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
