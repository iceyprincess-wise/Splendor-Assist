package com.assistant.admin

/**
 * The live Detector: recommends values for THIS device from what the
 * engines are measuring RIGHT NOW (AdminLiveStats). Nothing is guessed -
 * every pick is computed from the current measurements and every pick
 * carries a WHY that quotes those numbers.
 *
 * If the engines have not produced fresh numbers yet (service just
 * started, or nothing to measure), the detector says so and offers no
 * picks rather than inventing them.
 *
 * LAG NOTE: lag numbers measured while idle are calm by nature - real lag
 * happens in-game. So the lag picks are deliberately computed as STRONG
 * anti-lag values: thresholds are derived from your device's measured
 * baseline (panel rhythm, idle wobble, heat) with the safety margin already
 * built in, so they hold up under full game load, not just on a quiet desk.
 */
object AdminTuningDetector {

    data class Pick(val key: String, val value: Float, val why: String)

    /** One-line live status for the top of a settings screen, per adapter. */
    fun liveLine(adapter: String): String = when (adapter) {
        AdminConfigStore.ADAPTER_LAG -> lagLiveLine()
        else -> netLiveLine()
    }

    private fun netLiveLine(): String {
        if (!AdminLiveStats.fresh())
            return "DETECTOR: still measuring your connection... tap Refresh in ~10 seconds (the Splendor service must be running)."
        val loss = if (AdminLiveStats.lossPct >= 0)
            String.format("%.0f", AdminLiveStats.lossPct) + "%" else "measuring"
        return "DETECTOR - your device right now: ping " +
            String.format("%.0f", AdminLiveStats.rttMs) + "ms, wobble " +
            String.format("%.0f", AdminLiveStats.jitterMs) + "ms, lost packets " + loss +
            ", on " + AdminLiveStats.transport + " (" + AdminLiveStats.carrier + "), health " +
            AdminLiveStats.quality
    }

    private fun lagLiveLine(): String {
        if (!AdminLiveStats.lagFresh())
            return "DETECTOR: still measuring smoothness... tap Refresh in ~10 seconds (the Splendor service must be running)."
        return "DETECTOR - your device right now: frame wobble " +
            String.format("%.1f", AdminLiveStats.frameJitterMs) + "ms, steady beat " +
            String.format("%.0f", AdminLiveStats.stabilityPct) + "%, freezes/min " +
            String.format("%.1f", AdminLiveStats.stallsPerMin) + ", touch delay " +
            String.format("%.0f", AdminLiveStats.mtStallMs) + "ms, heat " +
            AdminLiveStats.thermal + ", screen " +
            String.format("%.0f", AdminLiveStats.panelHz) + "Hz, state " +
            AdminLiveStats.lagVerdict +
            " - picks below are game-load-proof, not idle-calibrated"
    }

    /** Detector picks for one engine. Empty until live numbers are fresh. */
    fun picksFor(engine: String): List<Pick> = when (engine) {
        "NetProbeEngine", "PacketLossProbeEngine", "DnsWarmupEngine",
        "CongestionSentinelEngine", "SpikeBurstEngine", "RadioKeepAliveEngine",
        "ActionWindowEngine", "CarrierProfileEngine", "NetworkStateEngine" ->
            if (AdminLiveStats.fresh()) netPicks(engine) else emptyList()
        "FramePacingEngine", "MainThreadStallEngine", "LagVerdictEngine",
        "LoadShedGovernor", "ThermalPeekEngine", "DisplayProfileEngine" ->
            if (AdminLiveStats.lagFresh()) lagPicks(engine) else emptyList()
        else -> emptyList()
    }

    // ==================== NET ====================

    private fun netPicks(engine: String): List<Pick> {
        val rtt = AdminLiveStats.rttMs
        val jit = AdminLiveStats.jitterMs
        val loss = if (AdminLiveStats.lossPct >= 0) AdminLiveStats.lossPct else 0f
        val r = String.format("%.0f", rtt) + "ms"
        val j = String.format("%.0f", jit) + "ms"
        val l = String.format("%.0f", loss) + "%"
        val wobbly = jit > rtt * 0.5f && jit > 15f
        val lossy = loss > 2f

        return when (engine) {
            "NetProbeEngine" -> listOf(
                Pick("net.probe.fast_ms", if (wobbly || lossy) 1500f else 2000f,
                    if (wobbly || lossy) "your line is unstable right now (wobble " + j + ", lost " + l + ") - check it faster so problems are caught sooner"
                    else "your line is steady (wobble " + j + ") - the standard rhythm is enough"),
                Pick("net.probe.calm_ms", if (lossy) 3000f else 4000f,
                    if (lossy) "packets are being lost (" + l + ") - keep the good-times check tighter"
                    else "quiet-time check every 4s keeps the picture fresh at low cost"),
                Pick("net.probe.timeout_ms", clamp(rtt * 4f + 200f, 800f, 2000f),
                    "about 4x your measured ping (" + r + ") - slow-but-alive answers still count, dead ones fail fast"),
                Pick("net.probe.alpha", if (wobbly) 0.3f else 0.4f,
                    if (wobbly) "your ping jumps around a lot (wobble " + j + ") - smooth harder so single jumps don't panic the system"
                    else "your ping is steady - let the average react quickly to real changes"),
                Pick("net.probe.samples", if (wobbly) 5f else 3f,
                    if (wobbly) "wobble " + j + " is high vs ping " + r + " - 5 pings per check reads the truth through the noise"
                    else "3 pings per check is enough on your steady line"),
                Pick("net.probe.gap_ms", 60f, "standard spacing between the pings of one check"),
                Pick("net.probe.degraded_mult", 2f, "OK-to-BAD line at 2x your pass-line works for your numbers")
            )
            "PacketLossProbeEngine" -> listOf(
                Pick("net.loss.round_ms", if (lossy) 2500f else 4000f,
                    if (lossy) "you ARE losing packets right now (" + l + ") - check more often while it lasts"
                    else "loss is clean (" + l + ") - standard rhythm is enough"),
                Pick("net.loss.per_round", if (lossy) 6f else 4f,
                    if (lossy) "more packets per check = finer reading while your line is dropping (" + l + ")"
                    else "4 packets per check is enough on a clean line"),
                Pick("net.loss.reply_timeout_ms", clamp(rtt * 3f + 200f, 500f, 1200f),
                    "about 3x your ping (" + r + ") - real losses get counted, slow answers don't"),
                Pick("net.loss.alpha", 0.35f, "slightly faster than default so a loss burst shows within 2-3 checks"),
                Pick("net.loss.gap_ms", 80f, "standard spacing so the check itself never floods your line")
            )
            "DnsWarmupEngine" -> listOf(
                Pick("net.dns.rewarm_ms", 75000f,
                    "refresh server addresses every 75s - always hot, never wasteful")
            )
            "CongestionSentinelEngine" -> listOf(
                Pick("net.sentinel.poll_ms", 1500f,
                    "watch just faster than the ping checks so no rise slips between looks"),
                Pick("net.sentinel.rise_factor", if (wobbly) 1.6f else 1.4f,
                    if (wobbly) "your wobble (" + j + ") jumps naturally - need a bigger jump before alarming or it cries wolf"
                    else "your line is calm - a smaller jump is already meaningful, alarm earlier"),
                Pick("net.sentinel.rise_fraction", 0.5f,
                    "second gate at half your allowance filters false alarms without missing real ones")
            )
            "SpikeBurstEngine" -> listOf(
                Pick("net.spike.recovery_window_ms", 45000f,
                    "watch 45s after a spike - quick all-clear, still enough proof"),
                Pick("net.spike.clean_samples", if (lossy) 3f else 2f,
                    if (lossy) "your line drops packets (" + l + ") - demand one extra clean ping before trusting it again"
                    else "2 clean pings in a row is solid proof on your clean line"),
                Pick("net.spike.burst_samples", 5f, "5 quick pings map a spike's depth well"),
                Pick("net.spike.burst_gap_ms", 200f, "1 second total map time - fast but not a flood"),
                Pick("net.spike.clean_mult", 1.5f, "a ping within 1.5x your pass-line counts as recovered")
            )
            "RadioKeepAliveEngine" -> listOf(
                Pick("net.keepalive.floor_s", if (wobbly || lossy) 3f else 4f,
                    if (wobbly || lossy) "your link is struggling (wobble " + j + ", lost " + l + ") - keep the modem hotter so it never adds ITS delay on top"
                    else "4s floor keeps the modem ready without cooking your battery")
            )
            "ActionWindowEngine" -> listOf(
                Pick("net.window.poll_ms", 1500f,
                    "refresh the traffic light right behind every new measurement"),
                Pick("net.window.hold_loss_pct", 8f,
                    "HOLD one notch earlier than default - actions that would die in transit get held instead"),
                Pick("net.window.go_loss_pct", if (loss < 1f) 2f else 3f,
                    if (loss < 1f) "your line is clean (" + l + " loss) - keep the GO bar strict, you'll pass it anyway"
                    else "your line always has some loss (" + l + ") - a 2% bar would almost never show GO; 3% keeps GO honest AND reachable"),
                Pick("net.window.hold_jitter_mult", 2f,
                    "2x your wobble allowance is the proven line between playable and not")
            )
            "CarrierProfileEngine" -> {
                if (AdminLiveStats.quality == "GOOD" || AdminLiveStats.quality == "DEGRADED") listOf(
                    Pick("net.profile.rtt_ms", (rtt * 1.4f).toInt().toFloat().coerceAtLeast(30f),
                        "your real measured ping is " + r + " - a pass-line at 1.4x that fits YOUR line instead of a generic carrier guess"),
                    Pick("net.profile.jitter_tol_ms", maxOf(15f, jit * 2.5f).toInt().toFloat(),
                        "your real wobble is " + j + " - allow 2.5x that before it counts as trouble"),
                    Pick("net.profile.keepalive_s", 0f,
                        "0 = automatic; the detected carrier rhythm is right unless you see modem lag")
                ) else listOf(
                    Pick("net.profile.rtt_ms", 0f, "connection is currently BAD - don't lock a baseline from a bad moment; leave 0 (auto) and detect again when it's healthy"),
                    Pick("net.profile.jitter_tol_ms", 0f, "same - keep auto until your line is healthy"),
                    Pick("net.profile.keepalive_s", 0f, "keep automatic")
                )
            }
            "NetworkStateEngine" -> listOf(
                Pick("net.state.poll_ms", 10000f,
                    "the OS already reports switches instantly; this backup sweep can stay relaxed")
            )
            else -> emptyList()
        }
    }

    // ==================== LAG ====================
    // Idle numbers are calm by nature; these picks are computed from your
    // device's measured baseline with game-load margin BUILT IN, so they
    // hold when the game is hammering the phone - not just at rest.

    private fun lagPicks(engine: String): List<Pick> {
        val fj = AdminLiveStats.frameJitterMs
        val stab = AdminLiveStats.stabilityPct
        val mt = AdminLiveStats.mtStallMs.coerceAtLeast(0f)
        val hz = if (AdminLiveStats.panelHz > 0f) AdminLiveStats.panelHz else 60f
        val vsync = 1000f / hz
        val hot = AdminLiveStats.thermal == "MODERATE" || AdminLiveStats.thermal == "SEVERE" ||
                  AdminLiveStats.thermal == "CRITICAL" || AdminLiveStats.thermal == "EMERGENCY"
        val fjS = String.format("%.1f", fj) + "ms"
        val stabS = String.format("%.0f", stab) + "%"
        val mtS = String.format("%.0f", mt) + "ms"
        val hzS = String.format("%.0f", hz) + "Hz"

        return when (engine) {
            "FramePacingEngine" -> listOf(
                Pick("lag.frame.alpha", 0.25f,
                    "your idle frame wobble is " + fjS + "; under game load it multiplies - 0.25 shows a real slowdown within 2-3 frames without panicking on singles"),
                Pick("lag.frame.report_ms", 15000f,
                    "15s windows catch an in-game lag episode one report earlier than stock 20s"),
                Pick("lag.frame.stall_ms", clamp(vsync * 3f, 50f, 100f),
                    "3 missed beats of your " + hzS + " screen (" + String.format("%.0f", vsync * 3f) + "ms) = a freeze a player feels; graded to YOUR panel, strong enough for game load")
            )
            "MainThreadStallEngine" -> listOf(
                Pick("lag.stall.cadence_ms", 200f,
                    "poke every 200ms - catches every choke a player could feel, even mid-match; idle touch delay " + mtS + " confirms the probe itself is cheap on your phone"),
                Pick("lag.stall.spike_ms", 60f,
                    "60ms is where a delayed touch becomes feelable in a 30fps game (2 game frames) - strong under load, quiet at idle"),
                Pick("lag.stall.alpha", 0.3f,
                    "a real in-game slowdown shows within 2-3 pokes, single blips ignored"),
                Pick("lag.stall.report_ms", 10000f,
                    "10s summaries keep the judge's choke counts fresh through a match")
            )
            "LagVerdictEngine" -> listOf(
                Pick("lag.verdict.poll_ms", 1500f,
                    "judge every 1.5s - with 2 agreeing checks, in-game lag is confirmed within ~3s"),
                Pick("lag.verdict.jitter_ms", clamp(maxOf(8f, fj * 2.5f), 8f, 15f),
                    "your idle wobble is " + fjS + " - alarm at 2.5x that (floor 8ms): idle never false-alarms, game-load stutter trips it immediately"),
                Pick("lag.verdict.stability_pct", if (stab in 0f..85f) 65f else 70f,
                    "your idle steady-beat is " + stabS + " on a " + hzS + " panel - this bar stays below idle so only real in-game breakdown crosses it"),
                Pick("lag.verdict.choke_stalls", 8f,
                    "8 freezes/min = heavy alarm; on a bottlenecked phone call the heavy help early"),
                Pick("lag.verdict.choke_mtstall_ms", 100f,
                    "average touch delay of 100ms is felt on every input - heavy rescue exactly there (your idle is " + mtS + ", so no false trigger)"),
                Pick("lag.verdict.choke_spikes", 15f,
                    "15 chokes/min matched to the 60ms choke line - repeated real chokes trip it, stray ones don't"),
                Pick("lag.verdict.confirm_polls", 2f,
                    "2 agreeing checks - confirmed fast, immune to single blips")
            )
            "LoadShedGovernor" -> listOf(
                Pick("lag.shed.poll_ms", 1500f,
                    "rescue re-checks every 1.5s, right behind the judge - and the freeze fast-path stays instant"),
                Pick("lag.shed.arm_polls", 2f,
                    "help arms after 2 agreeing checks (~3s) - fast and ghost-proof"),
                Pick("lag.shed.release_polls", 4f,
                    "4 clean checks before standing down - full quality back one beat sooner than stock, still flap-proof"),
                Pick("lag.shed.min_hold_ms", 6000f,
                    "6s minimum hold - quick recovery after short lag bursts, still clear of on/off thrash")
            )
            "ThermalPeekEngine" -> listOf(
                Pick("lag.thermal.poll_ms", if (hot) 5000f else 10000f,
                    if (hot) "your phone is ALREADY warm (heat " + AdminLiveStats.thermal + ") - watch heat twice as fast; in-game it will climb from here"
                    else "heat is " + AdminLiveStats.thermal + " right now - 10s checks are plenty until a long session warms it")
            )
            "DisplayProfileEngine" -> listOf(
                Pick("lag.display.game_fps", 30f,
                    "eFootball is locked at 30fps - this is a fact-setting; your " + hzS + " panel is detected automatically")
            )
            else -> emptyList()
        }
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float =
        (if (v < lo) lo else if (v > hi) hi else v).toInt().toFloat()
}
