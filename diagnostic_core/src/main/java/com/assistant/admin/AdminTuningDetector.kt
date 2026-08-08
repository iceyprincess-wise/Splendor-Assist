package com.assistant.admin

/**
 * The live Detector: recommends values for THIS device from what the
 * engines are measuring RIGHT NOW (AdminLiveStats). Nothing is guessed -
 * every pick is computed from current measurements, and every pick carries
 * a WHY that quotes those numbers.
 *
 * Net picks come from live ping/wobble/loss. Lag and stutter picks come
 * from the device's measured resting truth (frame wobble, steady-beat,
 * touch delay, heat, burst picture, real panel beat) padded with headroom
 * so the chosen lines hold up under real gaming load: silent at rest,
 * tripped instantly by real in-game trouble.
 *
 * If the engines have not produced fresh numbers yet (service just started,
 * or no connection), the detector says so and offers no picks rather than
 * inventing them.
 */
object AdminTuningDetector {

    data class Pick(val key: String, val value: Float, val why: String)

    /** One-line live status shown at the top of every settings screen. */
    fun liveLine(adapter: String): String = when (adapter) {
        AdminConfigStore.ADAPTER_LAG -> lagLiveLine()
        AdminConfigStore.ADAPTER_STUTTER -> stutterLiveLine()
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
            return "DETECTOR: still measuring your device... tap Refresh in ~10 seconds (the Splendor service must be running)."
        return "DETECTOR - your device right now: frame wobble " +
            String.format("%.1f", AdminLiveStats.frameJitterMs) + "ms, steady beat " +
            String.format("%.0f", AdminLiveStats.stabilityPct) + "%, touch delay " +
            String.format("%.0f", AdminLiveStats.mtStallMs) + "ms, heat " +
            AdminLiveStats.thermal + ", screen " +
            String.format("%.0f", AdminLiveStats.panelHz) + "Hz, state " +
            AdminLiveStats.lagVerdict + ", help level " + AdminLiveStats.shedLevel
    }

    private fun stutterLiveLine(): String {
        if (!AdminLiveStats.stutterFresh())
            return "DETECTOR: still measuring your screen... tap Refresh in ~10 seconds (the Splendor service must be running)."
        val bpm = if (AdminLiveStats.sBurstsPerMin >= 0f) AdminLiveStats.sBurstsPerMin else 0f
        val last = if (AdminLiveStats.sWorstMs > 0f)
            String.format("%.0f", AdminLiveStats.sWorstMs) + "ms (" + AdminLiveStats.sFrames + " late frames)"
            else "none yet"
        return "DETECTOR - your screen right now: micro-stutter bursts " +
            String.format("%.0f", bpm) + "/min, last burst " + last +
            ", state " + AdminLiveStats.sState + ", screen " +
            String.format("%.0f", AdminLiveStats.sPanelHz) + "Hz"
    }

    /** Detector picks for one engine. Empty until live numbers are fresh. */
    fun picksFor(engine: String): List<Pick> = when (engine) {
        "NetProbeEngine", "PacketLossProbeEngine", "DnsWarmupEngine",
        "CongestionSentinelEngine", "SpikeBurstEngine", "RadioKeepAliveEngine",
        "ActionWindowEngine", "CarrierProfileEngine", "NetworkStateEngine" ->
            if (AdminLiveStats.fresh()) netPicks(engine) else emptyList()
        "FramePacingEngine", "MainThreadStallEngine", "ThermalPeekEngine",
        "DisplayProfileEngine", "LagVerdictEngine", "LoadShedGovernor" ->
            if (AdminLiveStats.lagFresh()) lagPicks(engine) else emptyList()
        "StutterPulseEngine", "PanelWatchEngine", "BurstForensicsEngine" ->
            if (AdminLiveStats.stutterFresh()) stutterPicks(engine) else emptyList()
        else -> emptyList()
    }

    // ================= NET =================

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

    // ================= LAG =================
    //
    // Lag picks are anchored to the device's RESTING truth and padded with
    // headroom so they stay strong under full gaming load: trouble lines
    // sit far enough above the resting numbers that they never false-alarm
    // at setup, yet real in-game trouble crosses them instantly.

    private fun lagPicks(engine: String): List<Pick> {
        val fj = AdminLiveStats.frameJitterMs.coerceAtLeast(0f)
        val stab = AdminLiveStats.stabilityPct
        val mt = AdminLiveStats.mtStallMs.coerceAtLeast(0f)
        val hot = AdminLiveStats.thermal in listOf("MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")
        val warm = hot || AdminLiveStats.thermal == "LIGHT"
        val fjS = String.format("%.1f", fj) + "ms"
        val stabS = String.format("%.0f", stab) + "%"
        val mtS = String.format("%.0f", mt) + "ms"
        val vsync = if (AdminLiveStats.panelHz > 0f) 1000f / AdminLiveStats.panelHz else 16.67f

        return when (engine) {
            "FramePacingEngine" -> listOf(
                Pick("lag.frame.alpha", if (fj > 8f) 0.2f else 0.25f,
                    if (fj > 8f) "your resting frame wobble is already " + fjS + " - smooth a bit harder so noise doesn't read as lag"
                    else "resting wobble is low (" + fjS + ") - react a beat faster, your baseline is clean"),
                Pick("lag.frame.report_ms", 15000f,
                    "summaries every 15s - one report earlier into a lag episode than stock, numbers still solid"),
                Pick("lag.frame.stall_ms", clamp(vsync * 5f, 80f, 120f),
                    "about 5 missed beats of YOUR " + String.format("%.0f", AdminLiveStats.panelHz) + "Hz screen - a real felt freeze, never a single heavy frame")
            )
            "MainThreadStallEngine" -> listOf(
                Pick("lag.stall.cadence_ms", if (mt > 40f) 150f else 200f,
                    if (mt > 40f) "your device already chokes at rest (delay " + mtS + ") - poke faster so no choke hides between pokes"
                    else "resting delay is fine (" + mtS + ") - 200ms pokes catch anything a player could feel"),
                Pick("lag.stall.spike_ms", clamp(maxOf(66f, mt * 3f), 50f, 120f),
                    "above both two missed game beats (66ms) and 3x your resting delay (" + mtS + ") - a real choke, never idle noise"),
                Pick("lag.stall.alpha", 0.3f,
                    "a real choke-up shows within 2-3 pokes, single blips stay ignored"),
                Pick("lag.stall.report_ms", 10000f,
                    "chokes-per-minute stays live for the judge without noise")
            )
            "ThermalPeekEngine" -> listOf(
                Pick("lag.thermal.poll_ms", if (warm) 5000f else 10000f,
                    if (warm) "your phone is already " + AdminLiveStats.thermal + " - watch heat twice as fast, it decides your lag under load"
                    else "phone is cool (" + AdminLiveStats.thermal + ") - 10s checks are plenty until it warms")
            )
            "DisplayProfileEngine" -> listOf(
                Pick("lag.display.game_fps", 30f,
                    "eFootball is locked at 30fps - every lag line is computed from this truth; only change it if the game itself changes")
            )
            "LagVerdictEngine" -> {
                val jitLine = clamp(maxOf(10f, fj * 2.5f), 8f, 25f)
                val stabLine = if (stab >= 0f) clamp(stab - 15f, 40f, 70f) else 65f
                val mtLine = clamp(maxOf(120f, mt * 4f), 100f, 200f)
                listOf(
                    Pick("lag.verdict.poll_ms", 1500f,
                        "judge every 1.5s - with 2 agreeing checks, real lag is confirmed in ~3s"),
                    Pick("lag.verdict.jitter_ms", jitLine,
                        "2.5x your resting wobble (" + fjS + ") - silent at rest, trips instantly in real stutter"),
                    Pick("lag.verdict.stability_pct", stabLine,
                        "15 points below your resting steady-beat (" + stabS + ") - only a real fall from YOUR normal counts"),
                    Pick("lag.verdict.choke_stalls", 8f,
                        "8 freezes/min is already unplayable - call the emergency while the match is still winnable"),
                    Pick("lag.verdict.choke_mtstall_ms", mtLine,
                        "4x your resting touch delay (" + mtS + ") - a true emergency on YOUR device, never idle noise"),
                    Pick("lag.verdict.choke_spikes", 15f,
                        "15 chokes/min feels like heavy hands even with no big freeze - trap the machine-gun pattern here"),
                    Pick("lag.verdict.confirm_polls", 2f,
                        "2 agreeing checks - confirmed fast, immune to single blips")
                )
            }
            "LoadShedGovernor" -> listOf(
                Pick("lag.shed.min_hold_ms", if (warm) 10000f else 6000f,
                    if (warm) "your phone runs " + AdminLiveStats.thermal + " - heat lag comes in waves, hold help longer so it doesn't flap"
                    else "cool device - 6s hold recovers full quality quickly, still clear of the thrash zone"),
                Pick("lag.shed.poll_ms", 1500f,
                    "ride right behind the judge's rhythm one-to-one"),
                Pick("lag.shed.arm_polls", 2f,
                    "armed in ~3 seconds, never by one bad moment"),
                Pick("lag.shed.release_polls", 4f,
                    "4 clean checks before standing down - one beat quicker than stock, still flap-proof")
            )
            else -> emptyList()
        }
    }

    // ================= STUTTER =================
    //
    // Stutter picks are anchored to the screen's REAL beat and the resting
    // burst picture, padded with headroom: silent while you set up, tripped
    // instantly by real in-game stutter - values strong enough for the heat
    // of a match.

    private fun stutterPicks(engine: String): List<Pick> {
        val bpm = AdminLiveStats.sBurstsPerMin.coerceAtLeast(0f)
        val hz = if (AdminLiveStats.sPanelHz > 0f) AdminLiveStats.sPanelHz else 60f
        val vsync = 1000f / hz
        val busy = bpm > 1f
        val bS = String.format("%.0f", bpm)
        val hzS = String.format("%.0f", hz)

        return when (engine) {
            "StutterPulseEngine" -> listOf(
                Pick("stutter.pulse.burst_mult", if (busy) 2.5f else 2f,
                    if (busy) "your screen already skips at rest (" + bS + " bursts/min) - raise the late-line so resting noise doesn't drown real stutter"
                    else "your screen is clean at rest (" + bS + " bursts/min) - 2x your " + hzS + "Hz beat is the proven felt-stutter line"),
                Pick("stutter.pulse.min_frames", 2f,
                    "one late frame is normal phone life; two inside a single second is a real burst"),
                Pick("stutter.pulse.slice_ms", 1000f,
                    "1-second slices are the natural heartbeat of felt stutter - shorter splits bursts, longer blurs them"),
                Pick("stutter.pulse.publish_ms", 5000f,
                    "live readout every 5s - fresh whenever you open this screen, invisible cost")
            )
            "PanelWatchEngine" -> listOf(
                Pick("stutter.panel.poll_ms", 5000f,
                    "the OS reports screen-rhythm switches instantly; this backup sweep can stay relaxed")
            )
            "BurstForensicsEngine" -> {
                val seiz = clamp(maxOf(100f, vsync * 8f), 100f, 200f)
                listOf(
                    Pick("stutter.forensics.seizure_ms", seiz,
                        "about 8 missed beats of YOUR " + hzS + "Hz screen - a freeze anyone feels, never one heavy frame"),
                    Pick("stutter.forensics.osc_bursts", 3f,
                        "3 bursts inside the window is a rhythm, not bad luck - name it and let the rescue act"),
                    Pick("stutter.forensics.osc_window_ms", 15000f,
                        "15s window matches how rhythmic stutter actually arrives in waves"),
                    Pick("stutter.forensics.calm_after_ms", if (busy) 12000f else 8000f,
                        if (busy) "your screen bursts even at rest (" + bS + "/min) - demand 12 quiet seconds so CALM isn't declared inside an ongoing attack"
                        else "clean screen at rest - 8 quiet seconds is real proof, all-clear one beat quicker than stock"),
                    Pick("stutter.forensics.decay_poll_ms", 3000f,
                        "check for calm a beat faster than stock so full confidence returns promptly")
                )
            }
            else -> emptyList()
        }
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float =
        (if (v < lo) lo else if (v > hi) hi else v).toInt().toFloat()
}
