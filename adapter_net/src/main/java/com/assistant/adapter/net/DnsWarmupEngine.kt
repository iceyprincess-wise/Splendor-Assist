package com.assistant.adapter.net

import com.assistant.diagnostic.RuntimeLogger
import java.net.InetAddress

/**
 * Pre-resolves game-relevant hostnames so no lookup happens cold mid-match,
 * and re-warms the OS DNS cache before entries expire. A cold DNS lookup on
 * mobile can cost 100-300ms exactly when a connection is being re-established.
 */
object DnsWarmupEngine {

    private val HOSTS = listOf(
        "konami.net", "cdn.konami.net", "google.com", "cloudflare.com"
    )
    private const val REWARM_MS = 90_000L

    @Volatile private var running = false
    @Volatile private var warmed = 0L

    fun start() {
        if (running) return
        running = true
        val t = Thread {
            while (running) {
                var ok = 0
                for (h in HOSTS) {
                    try { InetAddress.getByName(h); ok++ } catch (_: Throwable) { }
                }
                warmed++
                if (warmed % 5L == 1L)
                    RuntimeLogger.log("dns warm " + ok + "/" + HOSTS.size + " hosts", "NET")
                try { Thread.sleep(REWARM_MS) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "net-dnswarm"; t.start()
    }

    fun stop() { running = false }
}
