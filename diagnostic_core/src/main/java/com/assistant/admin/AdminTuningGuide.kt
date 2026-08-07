package com.assistant.admin

/**
 * Field guide for every admin tunable: anatomy of the setting, how the
 * code reacts when you raise or lower it, the trade-off, and the tweak
 * spots (best min, best max, risk spot, cheat spot).
 *
 * Keys match AdminConfigStore.TUNABLES. A tunable without an entry simply
 * shows no guide — nothing breaks. When a new engine's constants are
 * migrated, add its guides here.
 *
 * "Cheat spot" = the prime pick for a mid-range phone on mobile data,
 * biased toward reaction speed over battery. Defaults stay the safe
 * factory baseline.
 */
object AdminTuningGuide {

    data class Guide(
        val what: String,
        val raise: String,
        val lower: String,
        val pro: String,
        val con: String,
        val bestMin: String,
        val bestMax: String,
        val risk: String,
        val cheat: String
    )

    fun forKey(key: String): Guide? = GUIDES[key]

    fun render(g: Guide): String = buildString {
        append("WHAT IT IS\n").append(g.what)
        append("\n\nIF YOU RAISE IT\n").append(g.raise)
        append("\n\nIF YOU LOWER IT\n").append(g.lower)
        append("\n\nADVANTAGE\n").append(g.pro)
        append("\n\nDISADVANTAGE\n").append(g.con)
        append("\n\nTWEAK SPOTS\n")
        append("• Best min: ").append(g.bestMin).append('\n')
        append("• Best max: ").append(g.bestMax).append('\n')
        append("• Risk spot: ").append(g.risk).append('\n')
        append("• Cheat spot: ").append(g.cheat)
    }

    private val GUIDES: Map<String, Guide> = mapOf(

        // ---------------- NetProbeEngine ----------------
        "net.probe.fast_ms" to Guide(
            what = "Gap between latency probes while the link is flagged dirty. Each cycle fires one lightweight TCP connect and feeds the result into the latency/jitter picture.",
            raise = "Probes fire less often — the engine notices a recovering or worsening link later, but burns less battery and data.",
            lower = "Probes fire more often — dirty links are confirmed or cleared sooner, but radio time goes up and the probes themselves add traffic.",
            pro = "Direct control over how fast the whole net picture refreshes when things are bad.",
            con = "Too aggressive and the measuring starts disturbing the thing being measured.",
            bestMin = "1000 (very responsive, noticeable battery cost)",
            bestMax = "3000 (easy on battery, still usable)",
            risk = "Below 500 — probe traffic competes with game traffic and can worsen the very jitter it measures.",
            cheat = "1500 — fast dirty-link tracking without self-inflicted noise."
        ),
        "net.probe.calm_ms" to Guide(
            what = "Gap between latency probes while the link is clean. The quiet-time heartbeat that keeps the baseline fresh.",
            raise = "Fewer background probes — cheaper, but the first sign of trouble is caught later.",
            lower = "Baseline stays fresher and trouble is spotted earlier, at the cost of steady background chatter.",
            pro = "Cheap place to save battery since most of the time the link is clean.",
            con = "Set too high, the engine is half-blind between probes.",
            bestMin = "3000",
            bestMax = "10000",
            risk = "Above 15000 — a link can go bad and stay undetected for a whole window.",
            cheat = "4000 — near-live awareness for a small cost."
        ),
        "net.probe.timeout_ms" to Guide(
            what = "How long one probe waits for the TCP connect to succeed before counting that probe as failed.",
            raise = "Slow-but-alive servers still count as reachable — fewer false alarms, but a truly dead link takes longer to confirm.",
            lower = "Dead links are confirmed faster, but congested-yet-alive links start counting as failures.",
            pro = "Tunes how forgiving the probe is on high-latency mobile networks.",
            con = "Wrong in either direction it distorts the dirty/clean verdict itself.",
            bestMin = "800",
            bestMax = "2000",
            risk = "Below 400 — mass false failures flip the link dirty on normal mobile latency.",
            cheat = "1000 — tight verdicts without punishing mobile-data latency."
        ),
        "net.probe.alpha" to Guide(
            what = "EWMA smoothing weight (0-1) for latency samples. It is the memory dial: how much the newest sample outweighs history.",
            raise = "Average reacts faster to the latest sample — snappy but jumpy; one spike moves the whole picture.",
            lower = "Average is smoother and calmer, but genuine changes take several samples to show.",
            pro = "Lets you pick reaction speed vs stability without touching cadences.",
            con = "Extremes make the metric either twitchy or sluggish — both mislead downstream engines.",
            bestMin = "0.2 (smooth)",
            bestMax = "0.5 (reactive)",
            risk = "Above 0.8 — a single outlier spike can flip link state on its own.",
            cheat = "0.4 — quick to see real shifts, still ignores one-off blips."
        ),

        // ---------------- PacketLossProbeEngine ----------------
        "net.loss.round_ms" to Guide(
            what = "Interval between loss-measuring rounds. Each round sends a small burst of queries and counts how many come back.",
            raise = "Loss % updates more slowly — less data use, but HOLD/GO reacts late to sudden loss.",
            lower = "Loss picture refreshes faster — earlier HOLD on a dropping link, more background traffic.",
            pro = "The main lever on how live the loss number is.",
            con = "Rounds too close together add their own load on a weak link.",
            bestMin = "2500",
            bestMax = "8000",
            risk = "Below 1500 — probe bursts overlap with play traffic and inflate measured loss.",
            cheat = "3000 — near-live loss tracking at modest cost."
        ),
        "net.loss.per_round" to Guide(
            what = "Queries sent per round. Resolution dial: 4 per round means each lost reply counts as 25% loss for that round.",
            raise = "Finer per-round resolution (8 queries = 12.5% steps) and steadier numbers, but more data per round.",
            lower = "Cheaper rounds but chunky readings — with 2 queries a single lost reply reads as 50% loss.",
            pro = "Better resolution makes HOLD/GO thresholds mean what they say.",
            con = "Big bursts can themselves induce queueing/loss on a saturated link.",
            bestMin = "3",
            bestMax = "8",
            risk = "Above 16 — the burst itself stresses the link and skews the measurement.",
            cheat = "5 — 20% resolution steps, still a light burst."
        ),
        "net.loss.reply_timeout_ms" to Guide(
            what = "How long each query waits for its reply before being counted as lost.",
            raise = "Late replies still count as delivered — loss reads lower and cleaner, but real problems surface later.",
            lower = "Slow replies count as lost — loss reads higher and HOLD triggers earlier, but pure latency starts masquerading as loss.",
            pro = "Sets where 'slow' becomes 'gone' for the loss metric.",
            con = "Too tight and high-ping-but-stable networks look lossy when they are not.",
            bestMin = "500",
            bestMax = "1200",
            risk = "Below 300 — normal mobile latency gets booked as packet loss.",
            cheat = "700 — the default is already the sweet spot here."
        ),
        "net.loss.alpha" to Guide(
            what = "EWMA smoothing weight for the loss average — same memory dial as the probe alpha, applied to loss %.",
            raise = "Loss % jumps quickly to match the latest round — fast alarms, noisy number.",
            lower = "Loss % moves gently — stable, but a sudden loss burst takes rounds to register.",
            pro = "Stabilises the exact number HOLD/GO decisions read.",
            con = "Too smooth and the action window reacts to history instead of now.",
            bestMin = "0.2",
            bestMax = "0.5",
            risk = "Above 0.7 — one bad round can slam the window to HOLD by itself.",
            cheat = "0.35 — slightly faster than default without flapping."
        ),

        // ---------------- DnsWarmupEngine ----------------
        "net.dns.rewarm_ms" to Guide(
            what = "How often the engine re-resolves the important hostnames so their DNS entries stay hot in cache.",
            raise = "Fewer wake-ups and less chatter, but entries can expire and the next real lookup goes out cold (slow first connect).",
            lower = "Lookups are always hot — first-connect is instant, at the cost of periodic background resolutions.",
            pro = "Removes the classic cold-DNS stall at the worst moment.",
            con = "Pointless traffic if set far below typical DNS TTLs.",
            bestMin = "60000",
            bestMax = "180000",
            risk = "Below 15000 — flooding resolvers for zero extra benefit.",
            cheat = "75000 — hot cache with headroom under common 120s TTLs."
        ),

        // ---------------- CongestionSentinelEngine ----------------
        "net.sentinel.poll_ms" to Guide(
            what = "How often the sentinel re-reads the jitter picture to check for a congestion rise.",
            raise = "Congestion onset is caught later, cheaper on CPU.",
            lower = "Earlier congestion warnings, slightly more work per second.",
            pro = "Cheap poll — this engine only reads numbers others already produced.",
            con = "Polling faster than the probes refresh adds nothing (it re-reads the same values).",
            bestMin = "1000",
            bestMax = "4000",
            risk = "Below 500 — wasted cycles re-reading unchanged data.",
            cheat = "1500 — aligned just under the probe cadence so no rise is missed."
        ),
        "net.sentinel.rise_factor" to Guide(
            what = "Multiplier over the jitter baseline that counts as a congestion rise (1.5 = jitter must climb 50% above its norm).",
            raise = "Fewer alarms; only serious congestion trips the sentinel — real onsets get caught later.",
            lower = "Earlier, more sensitive warnings — but ordinary jitter wobble starts tripping false alarms.",
            pro = "The main sensitivity dial for congestion detection.",
            con = "Works together with rise fraction — moving only one can make the pair inconsistent.",
            bestMin = "1.3",
            bestMax = "2.0",
            risk = "Below 1.1 — nearly every natural fluctuation reads as congestion.",
            cheat = "1.4 — trips one beat earlier than stock without false-alarm spam."
        ),
        "net.sentinel.rise_fraction" to Guide(
            what = "Second gate: the jitter rise must also exceed this fraction of the tolerance budget (0.6 = 60% of tolerance) before congestion is declared.",
            raise = "Stricter gate — small rises are ignored even if the factor trips; fewer combined alarms.",
            lower = "Looser gate — smaller absolute rises pass; the factor dial dominates the decision.",
            pro = "Filters out 'big relative rise on a tiny baseline' false positives.",
            con = "Too strict and slow-creep congestion never crosses the line.",
            bestMin = "0.4",
            bestMax = "0.8",
            risk = "Below 0.2 — the second gate stops filtering anything.",
            cheat = "0.5 — balanced with a 1.4 rise factor."
        ),

        // ---------------- ActionWindowEngine ----------------
        "net.window.poll_ms" to Guide(
            what = "How often the GO/HOLD verdict is recomputed from the current loss and jitter numbers.",
            raise = "Verdict updates lag behind the metrics — stale GO on a link that just went bad.",
            lower = "Verdict tracks the metrics near-live, tiny CPU cost.",
            pro = "Keeps the traffic light honest between metric updates.",
            con = "Below the probe cadences it just recomputes identical inputs.",
            bestMin = "1000",
            bestMax = "3000",
            risk = "Above 5000 — actions fire on a verdict from several seconds ago.",
            cheat = "1500 — verdict refreshes right behind every metric update."
        ),
        "net.window.hold_loss_pct" to Guide(
            what = "Loss percentage above which the window slams to HOLD — the hard ceiling for acting on a lossy link.",
            raise = "More tolerant: actions keep flowing on worse links — more play windows, more actions lost in transit.",
            lower = "More protective: HOLD comes sooner — safer actions, fewer usable windows.",
            pro = "Direct control of the safety ceiling.",
            con = "Must stay well above the GO threshold or the state machine gets squeezed.",
            bestMin = "5 (cautious)",
            bestMax = "15 (permissive)",
            risk = "Above 25 — HOLD practically never triggers; actions fire into a dying link.",
            cheat = "8 — holds one notch earlier than stock, saves actions that would have died."
        ),
        "net.window.go_loss_pct" to Guide(
            what = "Loss must be BELOW this for the window to show GO — the cleanliness bar for green-lighting actions.",
            raise = "GO appears on dirtier links — more windows, lower confidence each one.",
            lower = "GO only on truly clean links — high-confidence windows, fewer of them.",
            pro = "Sets exactly how clean 'clean' means.",
            con = "Near zero and mobile links may never qualify at all.",
            bestMin = "1",
            bestMax = "4",
            risk = "Above 10 — GO overlaps with genuinely lossy conditions, defeating the point.",
            cheat = "2 — the default is already the prime pick."
        ),
        "net.window.hold_jitter_mult" to Guide(
            what = "HOLD triggers when jitter exceeds tolerance times this multiplier — the jitter arm of the safety gate.",
            raise = "Bigger jitter storms are tolerated before holding — fewer interruptions, riskier timing.",
            lower = "Holds at milder jitter — well-timed actions only, more frequent pauses.",
            pro = "Protects timing-sensitive actions independently of loss.",
            con = "Too low and normal wireless wobble keeps freezing the window.",
            bestMin = "1.5",
            bestMax = "3",
            risk = "Above 5 — jitter effectively stops protecting anything.",
            cheat = "2 — stock value sits at the natural knee already."
        ),

        // ---------------- SpikeBurstEngine ----------------
        "net.spike.recovery_window_ms" to Guide(
            what = "After a spike burst is detected, how long the engine watches the link before it may declare recovery.",
            raise = "Longer observation — very sure recoveries, but the cautious state lingers after the link is actually fine.",
            lower = "Faster return to normal operations, on thinner evidence.",
            pro = "Controls how paranoid post-spike behaviour is.",
            con = "Too long and you sit in degraded mode well after the storm passed.",
            bestMin = "30000",
            bestMax = "120000",
            risk = "Below 10000 — recovery declared off a lucky moment, straight into the next spike.",
            cheat = "45000 — quicker bounce-back with evidence still meaningful."
        ),
        "net.spike.clean_samples" to Guide(
            what = "Consecutive clean samples required inside the watch window before the spike state clears.",
            raise = "Stronger proof required — near-zero false recoveries, slower all-clear.",
            lower = "Faster all-clear, higher chance of flapping back into spike state.",
            pro = "Simple, powerful debounce on the recovery decision.",
            con = "Each extra sample delays recovery by one probe interval.",
            bestMin = "2",
            bestMax = "4",
            risk = "0-1 — recovery flaps on single good samples.",
            cheat = "3 — one extra proof beat kills flapping for ~2s of patience."
        ),

        // ---------------- RadioKeepAliveEngine ----------------
        "net.keepalive.floor_s" to Guide(
            what = "Minimum keep-alive interval while the link is dirty — the floor on how often tiny packets hold the radio in its high-power state.",
            raise = "Radio is allowed to step down more — better battery/heat, but the next real packet may pay the radio ramp-up delay.",
            lower = "Radio pinned hot — first-packet latency stays minimal, battery and warmth pay for it.",
            pro = "Kills the radio wake-up latency spike exactly when the link is already struggling.",
            con = "The most battery-expensive dial in the net adapter.",
            bestMin = "3",
            bestMax = "8",
            risk = "Below 2 — constant top-power radio: heavy drain and a warm phone throttling itself.",
            cheat = "4 — stock floor is the measured knee between latency and drain."
        ),

        // ---------------- FramePacingEngine ----------------
        "lag.frame.alpha" to Guide(
            what = "EWMA weight for the running frame-time average — how fast the smoothness picture follows the newest frames.",
            raise = "Average chases recent frames — quick to flag a developing stutter, jumpy on single slow frames.",
            lower = "Very steady trend line, but a real slowdown takes longer to show.",
            pro = "Reaction-speed dial for everything downstream that reads frame health.",
            con = "Too high and one heavy frame reads like a trend.",
            bestMin = "0.1",
            bestMax = "0.35",
            risk = "Above 0.6 — every isolated slow frame yanks the average around.",
            cheat = "0.25 — sees real slowdowns a beat sooner than stock, still calm."
        ),
        "lag.frame.report_ms" to Guide(
            what = "Window between frame-pacing summaries handed to the rest of the runtime.",
            raise = "Smoother, longer-term verdicts — reactions to a fresh lag episode come later.",
            lower = "Fresher verdicts more often, slightly more bookkeeping.",
            pro = "Sets the granularity of the 'how smooth are we' story.",
            con = "Very short windows make verdicts noisy and spammy.",
            bestMin = "10000",
            bestMax = "30000",
            risk = "Below 2000 — report churn adds overhead and noise with no insight.",
            cheat = "15000 — catches a lag episode one report earlier than stock."
        ),
        "lag.frame.stall_ms" to Guide(
            what = "Frame time above this counts as a hard stall — the line between 'slow frame' and 'freeze'.",
            raise = "Only big freezes register — micro-stutter slips under the radar.",
            lower = "More frames get booked as stalls — earlier countermeasures, possibly over-triggering on ordinary heavy frames.",
            pro = "Defines the exact pain threshold the governor responds to.",
            con = "Set near the display's natural frame time it flags normal rendering.",
            bestMin = "80",
            bestMax = "150",
            risk = "Below 33 — every dropped vsync is a 'stall'; shedding never stops.",
            cheat = "90 — catches 5-6 dropped frames in a row while ignoring singles."
        ),

        // ---------------- LoadShedGovernor ----------------
        "lag.shed.min_hold_ms" to Guide(
            what = "Once load-shedding engages, the minimum time it stays engaged before it may release — the anti-thrash timer.",
            raise = "Steadier shed periods, but full quality returns sluggishly after brief load spikes.",
            lower = "Snappier recovery to full quality, with growing risk of shed-release oscillation.",
            pro = "One dial that decides between stability and responsiveness of the whole shedding system.",
            con = "The cost of a wrong value is invisible until load gets spiky.",
            bestMin = "5000",
            bestMax = "15000",
            risk = "Below 2000 — shed/release thrash: the flip-flopping feels worse than the lag it fights.",
            cheat = "6000 — quicker quality recovery while staying clear of the thrash zone."
        )
    )
}
