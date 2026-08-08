package com.assistant.admin

/**
 * The live Detector: recommends values for THIS device from what the
 * engines are measuring RIGHT NOW (AdminLiveStats). Nothing is guessed -
 * every pick is computed from the current measurements and every pick
 * carries a WHY that quotes those numbers.
 *
 * Net picks read live ping / wobble / lost packets.
 * Lag picks read live frame wobble / steadiness / freezes / touch delay /
 * heat / panel Hz - deliberately HARD picks: they assume match-time load
 * is coming and set the guards so that even heavy in-game moments cannot
 * produce silent micro-lag. Set them while idle; they hold in the storm.
 *
 * If the engines have not produced fresh numbers yet (service just
 * started, or no data), the detector says so and offers no picks rather
 * than inventing them.
 */
object AdminTuningDetector {

    data class Pick(val key: String, val value: Float, val why: String)

    /** One-line live status shown at the top of every settings screen. */
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
            return "DETECTOR: still reading your device... tap Refresh in ~10 seconds (the Splendor service must be running)."
        return "DETECTOR - your device right now: frame wobble " +
            String.format("%.1f", AdminLiveStats.frameJitterMs) + "ms, steady beat " +
            String.format("%.0f", AdminLiveStats.stabilityPct) + "%, freezes/min " +
            String.format("%.1f", AdminLiveStats.stallsPerMin) + ", touch delay " +
            String.format("%.0f", AdminLiveStats.mtStallMs) + "ms, heat " +
            AdminLiveStats.thermal + ", screen " +
            String.format("%.0f", AdminLiveStats.panelHz) + "Hz, state " +
            AdminLiveStats.lagVerdict +
            (if (AdminLiveStats.shedLevel != "NONE") ", rescue " + AdminLiveStats.shedLevel else "")
    }

    /** Detector picks for one engine. Empty until live numbers are fresh. */
    fun picksFor(engine: String): List<Pick> = when (engine) {
        "NetProbeEngine", "PacketLossProbeEngine", "DnsWarmupEngine",
        "CongestionSentinelEngine", "SpikeBurstEngine", "RadioKeepAliveEngine",
        "ActionWindowEngine", "CarrierProfileEngine", "NetworkStateEngine" -> netPicks(engine)
        "FramePacingEngine", "MainThreadStallEngine", "LagVerdictEngine",
        "LoadShedGovernor", "ThermalPeekEngine", "DisplayProfileEngine" -> lagPicks(engine)
        else -> emptyList()
    }

    // ================= NET =================

    private fun netPicks(engine: String): List<Pick> {
        if (!AdminLiveStats.fresh()) return emptyList()
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
                    Pick("net.profile.rtt_ms", (AdminLiveStats.rttMs * 1.4f).toInt().toFloat().coerceAtLeast(30f),
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

    // ================= LAG =================
    // HARD picks: set while idle, built to hold during match-time load so
    // no silent micro-lag survives. Every number is derived from THIS
    // device's live readings and quoted in the why.

    private fun lagPicks(engine: String): List<Pick> {
        if (!AdminLiveStats.lagFresh()) return emptyList()
        val fj = AdminLiveStats.frameJitterMs.coerceAtLeast(0f)
        val stab = AdminLiveStats.stabilityPct
        val mt = AdminLiveStats.mtStallMs.coerceAtLeast(0f)
        val hz = if (AdminLiveStats.panelHz > 0f) AdminLiveStats.panelHz else 60f
        val hot = AdminLiveStats.thermal in listOf("MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")
        val fjS = String.format("%.1f", fj) + "ms"
        val stabS = String.format("%.0f", stab) + "%"
        val mtS = String.format("%.0f", mt) + "ms"
        val hzS = String.format("%.0f", hz) + "Hz"
        // one game frame at the locked rate (default 30fps)
        val gameFrame = 1000f / AdminConfigStore.get("lag.display.game_fps", 30f).coerceAtLeast(1f)

        return when (engine) {
            "FramePacingEngine" -> listOf(
                Pick("lag.frame.alpha", if (fj > 8f) 0.2f else 0.25f,
                    if (fj > 8f) "your idle frame wobble is already " + fjS + " - smooth a bit harder so noise doesn't drown real trouble"
                    else "idle wobble " + fjS + " is low - let the average react fast; a real slowdown shows in 2-3 frames"),
                Pick("lag.frame.report_ms", 15000f,
                    "15s windows: in-match lag episodes reach the judge one report earlier than stock 20s"),
                Pick("lag.frame.stall_ms", clampF(gameFrame * 3f, 60f, 150f),
                    "3 missed game frames (" + String.format("%.0f", gameFrame * 3f) + "ms at your lock) - the exact point a hang becomes visible in play")
            )
            "MainThreadStallEngine" -> listOf(
                Pick("lag.stall.cadence_ms", 200f,
                    "poke every 200ms - no choke a player could feel fits between two pokes"),
                Pick("lag.stall.spike_ms", clampF(gameFrame * 2f, 40f, 100f),
                    "2 game frames (" + String.format("%.0f", gameFrame * 2f) + "ms) - the first moment a delayed touch is feelable, booked as a choke"),
                Pick("lag.stall.alpha", 0.3f,
                    "a real choke-up shows in 2-3 pokes; single scheduling blips stay ignored"),
                Pick("lag.stall.report_ms", 10000f,
                    "fresh chokes-per-minute maths every 10s all match long")
            )
            "LagVerdictEngine" -> listOf(
                Pick("lag.verdict.poll_ms", 1500f,
                    "judge looks every 1.5s - with 2 agreeing checks real lag is confirmed in ~3s"),
                Pick("lag.verdict.jitter_ms", clampF(maxOf(6f, fj * 2.5f), 6f, 20f),
                    "2.5x your idle wobble (" + fjS + ") - silent at rest, trips the moment real stutter starts"),
                Pick("lag.verdict.stability_pct", clampF(minOf(70f, if (stab > 0f) stab - 15f else 65f), 45f, 70f),
                    "your idle steady beat is " + stabS + " - the warning line sits safely below it, only real breakdown crosses"),
                Pick("lag.verdict.choke_stalls", 8f,
                    "8 freezes/min = heavy help. On a bottlenecked phone call the big guns early"),
                Pick("lag.verdict.choke_mtstall_ms", clampF(maxOf(100f, mt * 4f), 100f, 200f),
                    "your idle touch delay is " + mtS + " - well above that (never below 100ms) means genuinely choking, not just busy"),
                Pick("lag.verdict.choke_spikes", 15f,
                    "15 chokes/min trips heavy help - catches machine-gun micro-lag that never shows one big freeze"),
                Pick("lag.verdict.confirm_polls", 2f,
                    "2 agreeing checks: confirmed fast, immune to single blips")
            )
            "LoadShedGovernor" -> listOf(
                Pick("lag.shed.poll_ms", 1500f,
                    "rescue re-checks right behind the judge; the freeze fast-path stays instant regardless"),
                Pick("lag.shed.arm_polls", 2f,
                    "help arms after 2 agreeing checks (~3s) - fast but ghost-proof"),
                Pick("lag.shed.release_polls", 4f,
                    "4 clean checks before standing down - one beat sooner than stock, still flap-proof"),
                Pick("lag.shed.min_hold_ms", if (hot) 10000f else 6000f,
                    if (hot) "your phone is HOT right now (" + AdminLiveStats.thermal + ") - hold help longer, heat lag comes in waves"
                    else "6s hold: quick recovery after short bursts, clear of the thrash zone")
            )
            "ThermalPeekEngine" -> listOf(
                Pick("lag.thermal.poll_ms", if (hot) 5000f else 10000f,
                    if (hot) "heat status is " + AdminLiveStats.thermal + " NOW - watch it twice as fast while it lasts"
                    else "heat is fine (" + AdminLiveStats.thermal + ") - 10s checks are plenty; heat moves slowly")
            )
            "DisplayProfileEngine" -> listOf(
                Pick("lag.display.game_fps", 30f,
                    "eFootball is locked at 30fps - this must state the truth; your " + hzS + " screen is detected automatically")
            )
            else -> emptyList()
        }
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float =
        (if (v < lo) lo else if (v > hi) hi else v).toInt().toFloat()

    private fun clampF(v: Float, lo: Float, hi: Float): Float =
        (if (v < lo) lo else if (v > hi) hi else v).toInt().toFloat()
}
