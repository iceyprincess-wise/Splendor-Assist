package com.assistant.admin

/**
 * Layman field guide for the LAG ADAPTER settings, same structure as the
 * net guides: what it is / raise / lower / advantage / disadvantage /
 * best min / best max / risk spot / gaming cheat spot. Every line is read
 * from what the code actually does - no guessing. The live Detector on the
 * settings screen computes values from YOUR device on top of these.
 *
 * Returned type is AdminTuningGuide.Guide so the panel renders both guides
 * identically.
 */
object AdminTuningGuideLag {

    fun forKey(key: String): AdminTuningGuide.Guide? = GUIDES[key]

    private fun g(what: String, raise: String, lower: String, pro: String, con: String,
                  bestMin: String, bestMax: String, risk: String, cheat: String) =
        AdminTuningGuide.Guide(what, raise, lower, pro, con, bestMin, bestMax, risk, cheat)

    private val GUIDES: Map<String, AdminTuningGuide.Guide> = mapOf(

        // ---------------- FramePacingEngine ----------------
        "lag.frame.alpha" to g(
            what = "The memory dial (0 to 1) for the smoothness watcher. It watches every single frame your screen draws and keeps a running average; this decides how much the newest frame counts versus history.",
            raise = "The average chases the newest frames - a developing stutter is flagged within a frame or two, but one single heavy frame can also swing the picture.",
            lower = "The average moves calmly - very steady picture, but a real slowdown takes longer to show up, so help arrives later.",
            pro = "The reaction-speed dial for everything downstream that reads smoothness.",
            con = "Too high and every isolated slow frame looks like a trend; too low and real trouble hides.",
            bestMin = "0.1 (very calm)",
            bestMax = "0.35 (very alert)",
            risk = "Above 0.6 - every single slow frame yanks the average around and the judge gets whipsawed.",
            cheat = "0.25 - sees a real slowdown 2-3 frames in, ignores one-off hiccups."
        ),
        "lag.frame.report_ms" to g(
            what = "How often (ms) the smoothness watcher closes its notebook and hands a summary (steady-beat %, freezes, worst gap) to the judge.",
            raise = "Longer windows - smoother long-term verdicts, but a fresh lag episode waits longer to appear in a summary.",
            lower = "Fresher summaries more often - the judge reacts to a new episode sooner, tiny extra bookkeeping.",
            pro = "Sets how current the 'how smooth are we' story is.",
            con = "Very short windows make the numbers noisy (too few frames per window to be fair).",
            bestMin = "10000",
            bestMax = "30000",
            risk = "Below 2000 - summary churn with too little data; numbers jump around meaninglessly.",
            cheat = "15000 - catches an in-game lag episode one report earlier than stock."
        ),
        "lag.frame.stall_ms" to g(
            what = "A frame gap longer than this many milliseconds is booked as a FREEZE - the line between 'slow frame' and 'the game visibly hung'.",
            raise = "Only big freezes get booked - micro-freezes slip under the radar and never trigger help.",
            lower = "Smaller hangs count as freezes - help triggers earlier, but set it near your screen's normal beat and ordinary frames get branded freezes.",
            pro = "Defines the exact pain threshold the rescue responds to.",
            con = "Wrong in either direction: too high = silent suffering, too low = constant false alarms.",
            bestMin = "50 (sharp, for fast screens)",
            bestMax = "150 (only heavy freezes)",
            risk = "Below 34 - on a 30fps game EVERY normal frame is branded a freeze and rescue never stands down.",
            cheat = "Use the Detector's pick - it computes 3 missed beats of YOUR screen, the point players actually feel."
        ),

        // ---------------- MainThreadStallEngine ----------------
        "lag.stall.cadence_ms" to g(
            what = "The app pokes its own control thread on this rhythm (ms) and measures how late the answer returns. That lateness is exactly the delay YOUR TOUCHES suffer when the phone is choked - this is the touch-delay radar.",
            raise = "Fewer pokes - lighter on the phone, but short chokes between pokes go unseen.",
            lower = "More pokes - even brief chokes get caught, at slightly more work (the poke itself is nearly free).",
            pro = "Measures the one delay a player feels most: the finger-to-game delay.",
            con = "Extremely fast rhythms measure the measuring more than the phone.",
            bestMin = "100",
            bestMax = "500",
            risk = "Below 50 - the radar starts busy-working the very thread it is trying to keep free.",
            cheat = "200 - catches every choke a player could feel, still feather-light."
        ),
        "lag.stall.spike_ms" to g(
            what = "A poke that comes back later than this many ms is booked as a CHOKE. This is where 'busy for a moment' officially becomes 'the phone froze my touch'.",
            raise = "Only serious chokes get booked - the choke counter stays low, the judge stays relaxed longer.",
            lower = "Smaller delays count - the judge hears about trouble earlier, but normal little waits start being counted too.",
            pro = "Sets the sensitivity of the touch-delay alarm.",
            con = "It feeds the judge's CHOKING line - move both together or they contradict.",
            bestMin = "40",
            bestMax = "120",
            risk = "Below 20 - ordinary scheduling wiggle gets booked as chokes; the count becomes noise.",
            cheat = "60 - exactly 2 game frames at 30fps: the first moment a delayed touch is actually feelable."
        ),
        "lag.stall.alpha" to g(
            what = "Memory dial (0-1) for the average touch delay - how much the newest poke counts versus history.",
            raise = "The average jumps with the newest poke - fast alarms, jumpy number.",
            lower = "The average moves gently - stable, but a real choke-up takes several pokes to register.",
            pro = "Steadies the exact number the judge's CHOKING line reads.",
            con = "Extremes make it twitchy or sleepy - both mislead the judge.",
            bestMin = "0.15",
            bestMax = "0.4",
            risk = "Above 0.7 - one late poke can flip the judge to CHOKING by itself.",
            cheat = "0.3 - a real slowdown shows within 2-3 pokes, single blips ignored."
        ),
        "lag.stall.report_ms" to g(
            what = "How often (ms) the choke counts are summarized and the per-minute rate is refreshed for the judge.",
            raise = "Rates refresh more slowly - calmer numbers, later reaction to a fresh episode.",
            lower = "Rates refresh faster - the judge works with fresher counts.",
            pro = "Keeps the chokes-per-minute number honest and current.",
            con = "Very short windows make the per-minute maths jumpy.",
            bestMin = "5000",
            bestMax = "20000",
            risk = "Below 2000 - the per-minute rate is computed from almost nothing and swings wildly.",
            cheat = "10000 - fresh counts all match long."
        ),

        // ---------------- LagVerdictEngine ----------------
        "lag.verdict.poll_ms" to g(
            what = "How often (ms) the judge reads all the radar numbers and names the device state: SMOOTH, JITTERY or CHOKING. Everything the rescue does starts from this verdict.",
            raise = "Verdicts update more slowly - fewer checks, but help starts later when lag hits mid-match.",
            lower = "Verdicts track the radars near-live - help starts sooner, tiny extra work.",
            pro = "The heartbeat of the whole lag response.",
            con = "Polling much faster than the radars refresh just re-reads the same numbers.",
            bestMin = "1000",
            bestMax = "4000",
            risk = "Above 5000 - lag can rage for seconds before the judge even looks.",
            cheat = "1500 - with 2 agreeing checks, real lag is confirmed within ~3 seconds."
        ),
        "lag.verdict.jitter_ms" to g(
            what = "Frame wobble above this many ms makes the judge say JITTERY (the light-warning state that arms light help).",
            raise = "More wobble tolerated before the warning - fewer warnings, later light help.",
            lower = "Warnings come at smaller wobble - earlier help, but idle wobble may trigger it constantly.",
            pro = "Your main sensitivity dial for 'the game feels rough'.",
            con = "Below your phone's natural idle wobble it never stops warning.",
            bestMin = "6",
            bestMax = "15",
            risk = "Below 4 - permanent JITTERY state; light help never releases and the warning means nothing.",
            cheat = "Use the Detector's pick - 2.5x YOUR measured idle wobble: silent at rest, instant on real stutter."
        ),
        "lag.verdict.stability_pct" to g(
            what = "The steady-beat score is the share of frames marching on the dominant rhythm. Below this percent, the judge says JITTERY.",
            raise = "Demands a steadier beat - warnings come earlier (stricter judge).",
            lower = "Accepts a messier beat - fewer warnings, later help.",
            pro = "Catches the 'nothing froze but it feels off' kind of roughness wobble alone can miss.",
            con = "Adaptive screens naturally mix rhythms; too strict reads normal mixing as trouble.",
            bestMin = "50 (loose)",
            bestMax = "80 (strict)",
            risk = "Above 90 - normal rhythm mixing on adaptive panels is branded trouble non-stop.",
            cheat = "65 - below your idle steadiness, so only real breakdown crosses it."
        ),
        "lag.verdict.choke_stalls" to g(
            what = "Screen freezes per minute above this number = CHOKING, the heavy state that triggers heavy rescue.",
            raise = "More freezes tolerated before heavy help - fewer heavy interventions, more suffered freezes.",
            lower = "Heavy help comes after fewer freezes - stronger protection, heavy mode more often.",
            pro = "Direct control over when the big guns come out.",
            con = "Heavy shed drops real work - arming it too easily costs background comfort.",
            bestMin = "5",
            bestMax = "20",
            risk = "Above 40 - the phone can freeze 39 times a minute and heavy help never arrives.",
            cheat = "8 - on a bottlenecked phone, call the heavy help early; suffering longer helps nobody."
        ),
        "lag.verdict.choke_mtstall_ms" to g(
            what = "Average touch delay above this many ms = CHOKING. The second arm of the heavy alarm, independent of screen freezes.",
            raise = "Bigger average delays tolerated - heavy help later.",
            lower = "Heavy help at smaller delays - snappier protection, more heavy mode.",
            pro = "Protects the input feel directly - the thing that loses matches.",
            con = "Below the phone's natural background delay it triggers without real trouble.",
            bestMin = "80",
            bestMax = "200",
            risk = "Below 40 - ordinary busy moments read as CHOKING and heavy mode flaps.",
            cheat = "100 - a tenth of a second on EVERY touch is exactly where a player starts losing duels."
        ),
        "lag.verdict.choke_spikes" to g(
            what = "Chokes (late pokes) per minute above this number = CHOKING. The third arm of the heavy alarm - catches rapid-fire micro-chokes that keep the average low.",
            raise = "More chokes per minute tolerated - heavy help later.",
            lower = "Heavy help after fewer chokes - catches machine-gun micro-lag earlier.",
            pro = "The net for exactly the silent micro-lag you asked to eliminate: many small chokes, none big alone.",
            con = "Tied to the choke line (spike_ms) - lowering that raises this count automatically.",
            bestMin = "10",
            bestMax = "30",
            risk = "Above 60 - a choke every second counts as fine; micro-lag rules the match unpunished.",
            cheat = "15 - repeated real chokes trip it fast, stray ones don't."
        ),
        "lag.verdict.confirm_polls" to g(
            what = "How many consecutive judge checks must agree before the verdict actually changes. The anti-whiplash gate.",
            raise = "Rock-solid verdicts, but each extra check delays the state change by one poll.",
            lower = "Verdicts flip faster - at 1, a single blip flips the state instantly.",
            pro = "Stops one weird measurement from bouncing help on and off.",
            con = "Every unit here is (poll rhythm) more milliseconds of waiting when real lag starts.",
            bestMin = "1 (instant, twitchy)",
            bestMax = "3 (very solid)",
            risk = "Above 5 - real lag must persist 5+ checks before the judge even admits it exists.",
            cheat = "2 - confirmed fast, immune to single blips."
        ),

        // ---------------- LoadShedGovernor ----------------
        "lag.shed.poll_ms" to g(
            what = "How often (ms) the rescue re-reads the verdict and decides its help level: NONE, LIGHT or HEAVY. (A detected FREEZE burst skips this queue and escalates instantly - that fast path is always on.)",
            raise = "Slower reaction chain from verdict to help.",
            lower = "Help level tracks the verdict near-live.",
            pro = "Keeps rescue reaction time in your hands.",
            con = "Much faster than the judge's rhythm adds nothing (same verdict re-read).",
            bestMin = "1000",
            bestMax = "4000",
            risk = "Above 5000 - by the time help arms, the burst may be over; protection becomes an afterthought.",
            cheat = "1500 - right behind the judge, and the freeze fast-path stays instant anyway."
        ),
        "lag.shed.arm_polls" to g(
            what = "How many consecutive rescue checks must want MORE help before it actually arms.",
            raise = "Help arms more cautiously - fewer ghost interventions, later real ones.",
            lower = "Help arms faster - at 1, a single check's word is enough.",
            pro = "Ghost-proofing for the arming side.",
            con = "Each unit = one poll rhythm of extra suffering before help starts.",
            bestMin = "1",
            bestMax = "3",
            risk = "Above 4 - help regularly arrives after the lag burst already passed.",
            cheat = "2 - arms in ~3s with a 1500ms rhythm, ghost-proof."
        ),
        "lag.shed.release_polls" to g(
            what = "How many consecutive CLEAN checks are needed before help stands down to NONE.",
            raise = "Help lingers longer after trouble - safer against comebacks, full quality returns later.",
            lower = "Full quality returns sooner - with growing risk of on/off flapping.",
            pro = "The anti-flap dial for the release side.",
            con = "Every extra unit keeps reduced-work mode running after the lag is gone.",
            bestMin = "3",
            bestMax = "6",
            risk = "1 - one lucky clean check releases everything, straight into the next burst: flip-flop city.",
            cheat = "4 - one beat sooner back to full quality than stock, still flap-proof."
        ),
        "lag.shed.min_hold_ms" to g(
            what = "Once a help level changes, it cannot change again for at least this many ms - the anti-thrash timer.",
            raise = "Very steady help periods, but stale help lingers after short bursts.",
            lower = "Snappier level changes, with growing thrash risk when load is spiky.",
            pro = "One dial deciding stability vs responsiveness of the whole rescue.",
            con = "The cost of a wrong value only shows when load gets spiky - test in a real match.",
            bestMin = "4000",
            bestMax = "15000",
            risk = "Below 2000 - shed/release thrash: the flip-flopping feels worse than the lag it fights.",
            cheat = "6000 - quick recovery after short bursts, clear of the thrash zone."
        ),

        // ---------------- ThermalPeekEngine ----------------
        "lag.thermal.poll_ms" to g(
            what = "How often (ms) the app asks Android for the phone's official heat status. Heat is the silent lag-maker: a hot phone slows ITSELF down on purpose.",
            raise = "Fewer heat checks - a heat episode is correlated with lag later.",
            lower = "Heat changes are caught sooner - the lag log can say 'this storm was heat' with certainty.",
            pro = "Nearly free reading that answers the biggest question: is my lag heat or load?",
            con = "Checking very fast changes nothing - heat moves in tens of seconds, not milliseconds.",
            bestMin = "5000",
            bestMax = "30000",
            risk = "Below 1000 - a thousand identical readings a minute, pure waste.",
            cheat = "10000 normally, 5000 during long sessions - the Detector picks this from your live heat."
        ),

        // ---------------- DisplayProfileEngine ----------------
        "lag.display.game_fps" to g(
            what = "The frame rate your game is locked to (eFootball: 30). The radars use it to know what 'one game frame' means when grading freezes and chokes.",
            raise = "Tells the radars the game runs faster - freeze/choke maths gets stricter to match a faster game.",
            lower = "Tells the radars the game runs slower - the same maths relaxes.",
            pro = "When the game changes its lock (say 60fps someday), one number here re-tunes the whole adapter - no rebuild.",
            con = "This is a FACT setting, not a tuning dial - it must match the game's real lock.",
            bestMin = "30 (eFootball today)",
            bestMax = "60 (only if the game truly runs 60)",
            risk = "Any value that is NOT the game's real frame rate - every freeze judgement downstream goes quietly wrong.",
            cheat = "30 - the truth. The cheat here is honesty; the panel Hz is detected automatically."
        )
    )
}
