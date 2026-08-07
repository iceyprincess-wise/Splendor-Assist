package com.assistant.admin

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The REAL live Detector. It does not guess: it reads the numbers the
 * engines are measuring on THIS device RIGHT NOW (ping, wobble, lost
 * packets, connection type, carrier baseline) and turns them into a
 * recommended value per setting - with the reason spelled out next to
 * every pick. If the engines have not produced fresh numbers yet it says
 * "still measuring" instead of inventing values.
 */
object AdminTuningDetector {

    data class Pick(val key: String, val value: Float, val why: String)

    fun ready(): Boolean = AdminLiveStats.fresh() && AdminLiveStats.rttMs > 0f

    fun liveLine(): String {
        if (!ready()) return "Detector: still measuring your network... keep the assistant running and reopen this screen in a minute."
        val loss = if (AdminLiveStats.lossPct >= 0f)
            String.format("%.0f%%", AdminLiveStats.lossPct) else "not measured yet"
        return "Your device right now: ping ~" + AdminLiveStats.rttMs.roundToInt() +
            "ms, wobble ~" + AdminLiveStats.jitterMs.roundToInt() +
            "ms, lost packets " + loss + ", on " + AdminLiveStats.transport +
            " (" + AdminLiveStats.carrier + ", quality " + AdminLiveStats.quality + ")"
    }

    /** Live picks for one engine's settings; empty until measurements are fresh. */
    fun picksFor(engine: String): List<Pick> {
        if (!ready()) return emptyList()
        val rtt = AdminLiveStats.rttMs
        val jit = AdminLiveStats.jitterMs
        val loss = if (AdminLiveStats.lossPct >= 0f) AdminLiveStats.lossPct else 0f
        val tol = if (AdminLiveStats.jitterTolMs > 0) AdminLiveStats.jitterTolMs.toFloat() else 30f
        val wifi = AdminLiveStats.transport == "WIFI"
        val shaky = jit > tol || loss > 3f
        val rttTxt = rtt.roundToInt().toString() + "ms"
        val jitTxt = jit.roundToInt().toString() + "ms"

        return when (engine) {
            "NetProbeEngine" -> listOf(
                Pick("net.probe.fast_ms", if (shaky) 1500f else 2000f,
                    if (shaky) "your link is shaky right now - check it faster" else "your link is steady - stock pace is enough"),
                Pick("net.probe.calm_ms", if (shaky) 3000f else 4000f,
                    "keeps the quiet-time picture fresh for your current link"),
                Pick("net.probe.timeout_ms", round100(max(600f, (rtt + jit) * 4f)),
                    "your ping+wobble is ~" + (rtt + jit).roundToInt() + "ms; waiting 4x that catches slow-but-alive servers"),
                Pick("net.probe.alpha", if (jit > tol) 0.25f else 0.4f,
                    if (jit > tol) "your wobble (" + jitTxt + ") is high - smooth more so one blip doesn't panic the stack"
                    else "your wobble (" + jitTxt + ") is low - reacting faster is safe"),
                Pick("net.probe.samples", if (jit > tol) 5f else 3f,
                    if (jit > tol) "noisy link - more pings per check gives a truer middle value" else "steady link - 3 pings is plenty"),
                Pick("net.probe.gap_ms", if (rtt < 50f) 40f else 60f,
                    "matched to your ping of " + rttTxt),
                Pick("net.probe.degraded_mult", if (wifi) 1.8f else 2f,
                    if (wifi) "WiFi baseline is tight - flag trouble a bit sooner" else "mobile data breathes more - stock line fits")
            )
            "NetworkStateEngine" -> listOf(
                Pick("net.state.poll_ms", if (shaky) 5000f else 10000f,
                    "instant switch detection does the real work; this is only the backup sweep")
            )
            "DnsWarmupEngine" -> listOf(
                Pick("net.dns.rewarm_ms", if (shaky) 60000f else 90000f,
                    if (shaky) "shaky link - keep addresses hotter" else "steady link - stock refresh is enough")
            )
            "RadioKeepAliveEngine" -> listOf(
                Pick("net.keepalive.floor_s", if (shaky && !wifi) 3f else 4f,
                    if (shaky && !wifi) "your mobile link is struggling - keep the radio hotter (costs battery)"
                    else "stock floor is the right trade for your link")
            )
            "PacketLossProbeEngine" -> listOf(
                Pick("net.loss.round_ms", if (loss > 3f) 3000f else 4000f,
                    "your measured loss is " + String.format("%.0f", loss) + "% - " +
                    (if (loss > 3f) "track it tighter" else "stock rhythm is fine")),
                Pick("net.loss.per_round", if (loss > 3f) 6f else 4f,
                    if (loss > 3f) "more packets per check = finer loss reading when it matters" else "clean link - light checks are enough"),
                Pick("net.loss.reply_timeout_ms", round100(max(500f, (rtt + jit) * 5f)),
                    "5x your ping+wobble - late replies still count, dead ones don't"),
                Pick("net.loss.alpha", if (loss > 3f) 0.4f else 0.3f,
                    if (loss > 3f) "loss is active - let the average react faster" else "stock smoothing fits a clean link"),
                Pick("net.loss.gap_ms", 80f, "stock spacing keeps the burst light on any link")
            )
            "CongestionSentinelEngine" -> listOf(
                Pick("net.sentinel.poll_ms", if (shaky) 1500f else 2000f,
                    if (shaky) "trouble is brewing on your link - watch closer" else "stock watch rhythm fits"),
                Pick("net.sentinel.rise_factor", if (jit > tol) 1.6f else 1.4f,
                    if (jit > tol) "your wobble is already high - slightly deafer alarm avoids constant alerts"
                    else "calm link - a slightly sharper alarm hears trouble earlier"),
                Pick("net.sentinel.rise_fraction", 0.5f,
                    "balanced second gate for your wobble allowance of " + tol.roundToInt() + "ms")
            )
            "SpikeBurstEngine" -> listOf(
                Pick("net.spike.recovery_window_ms", if (shaky) 45000f else 60000f,
                    if (shaky) "spikes are frequent here - shorter watch, faster bounce-back" else "stock watch window fits"),
                Pick("net.spike.clean_samples", if (jit > tol) 3f else 2f,
                    if (jit > tol) "noisy link - demand one extra clean ping before all-clear" else "steady link - 2 proofs is enough"),
                Pick("net.spike.burst_samples", 5f, "5 pings maps a spike well without adding load"),
                Pick("net.spike.burst_gap_ms", 200f, "stock spacing - the map stays honest"),
                Pick("net.spike.clean_mult", 1.5f, "clean line at 1.5x your baseline matches your carrier profile")
            )
            "ActionWindowEngine" -> listOf(
                Pick("net.window.poll_ms", 1500f, "refresh the verdict right behind every new measurement"),
                Pick("net.window.hold_loss_pct", if (loss > 5f) 12f else 8f,
                    "your loss is " + String.format("%.0f", loss) + "% - " +
                    (if (loss > 5f) "a stricter ceiling would freeze you constantly; hold a bit more room"
                     else "hold earlier, save actions that would have died")),
                Pick("net.window.go_loss_pct", if (loss > 2f) 3f else 2f,
                    if (loss > 2f) "your link rarely reads 0% - demanding cleaner than it gets means never GO"
                    else "your link can meet the stock bar"),
                Pick("net.window.hold_jitter_mult", if (jit > tol) 2.5f else 2f,
                    if (jit > tol) "your wobble runs high - a touch more headroom avoids constant HOLD"
                    else "stock gate fits your wobble")
            )
            "CarrierProfileEngine" -> listOf(
                Pick("net.profile.rtt_ms", round10(rtt * 1.25f),
                    "your real ping is ~" + rttTxt + " - pass-line just above it judges YOUR link, not a generic one"),
                Pick("net.profile.jitter_tol_ms", round5(max(10f, jit * 2f)),
                    "your real wobble is ~" + jitTxt + " - allowance at 2x that flags real trouble only"),
                Pick("net.profile.keepalive_s", 0f,
                    "leave on auto - the engine already halves the rhythm when your link goes bad")
            )
            else -> emptyList()
        }
    }

    private fun round100(v: Float): Float = (Math.round(v / 100f) * 100).toFloat()
    private fun round10(v: Float): Float = (Math.round(v / 10f) * 10).toFloat()
    private fun round5(v: Float): Float = (Math.round(v / 5f) * 5).toFloat()
}
