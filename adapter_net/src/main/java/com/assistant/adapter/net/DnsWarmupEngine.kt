package com.assistant.adapter.net

// V2 PROACTIVE
import com.assistant.diagnostic.RuntimeLogger
import java.net.InetAddress

/** V2: fixed host list (previous list had 2 dead names) and failures are named. */
object DnsWarmupEngine {

    private val HOSTS = listOf(
        "www.konami.com", "www.google.com", "www.cloudflare.com", "one.one.one.one"
    )
    private const val REWARM_MS = 90_000L

    @Volatile private var running = false
    @Volatile private var rounds = 0L
    private val reported = HashSet<String>()

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                var ok = 0
                for (h in HOSTS) {
                    try { InetAddress.getByName(h); ok++ } catch (_: Throwable) {
                        if (reported.add(h)) RuntimeLogger.log("dns FAIL host=" + h, "NET")
                    }
                }
                rounds++
                if (rounds % 5L == 1L) RuntimeLogger.log("dns warm " + ok + "/" + HOSTS.size, "NET")
                try { Thread.sleep(REWARM_MS) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-dnswarm"; t.start()
    }

    fun stop() { running = false }
}
