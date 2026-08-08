package com.assistant.admin

/**
 * Plain-language field guide for the LAG ADAPTER tunables. Every line is
 * read from what the code actually does - no guessing. Same structure as
 * the net guide: what it is / raise / lower / advantage / disadvantage /
 * best min / best max / risk spot / gaming cheat spot.
 *
 * The live DETECTOR on the settings screen computes values from YOUR
 * device's measurements - when the two disagree, trust the Detector.
 * (The 4 original lag keys - lag.frame.* and lag.shed.min_hold_ms - live
 * in AdminTuningGuide; this file covers the 16 added at finalization.)
 */
object AdminTuningGuideLag {

    fun forKey(key: String): AdminTuningGuide.Guide? = GUIDES[key]

    private val GUIDES: Map<String, AdminTuningGuide.Guide> = mapOf(

        // ---------------- MainThreadStallEngine ----------------
        "lag.stall.cadence_ms" to AdminTuningGuide.Guide(
            what = "The app pokes its own busy thread on this rhythm (ms) and measures how late the answer comes back. A late answer means the phone is choking - this catches lag that frames alone cannot show.",
            raise = "Pokes come less often - lighter on the phone, but a short choke can start and finish between two pokes and never be seen.",
            lower = "Pokes come faster - even the briefest choke gets caught, at the cost of a tiny bit more work per second.",
            pro = "The earliest warning system for silent micro-lag: it feels the choke before your eyes see it.",
            con = "Each poke is tiny, but extremely fast rhythms add up on a weak phone.",
            bestMin = "100 (super alert)",
            bestMax = "400 (relaxed)",
            risk = "Below 50 - the poking itself becomes load on an already bottlenecked phone and worsens what it measures.",
            cheat = "200 - catches every choke a player could ever feel, cost still invisible."
        ),
        "lag.stall.spike_ms" to AdminTuningGuide.Guide(
            what = "How late (ms) one answer must be before it counts as a choke. The line between 'busy for a moment' and 'choking'.",
            raise = "Only serious chokes are counted - fewer alarms, but small repeated micro-chokes pass unnoticed.",
            lower = "Even tiny delays count - nothing slips through, but normal brief busyness starts being flagged.",
            pro = "You define exactly what 'choke' means on your device.",
            con = "The judge and rescue react to these counts - a wrong line here misleads them both.",
            bestMin = "40",
            bestMax = "120",
            risk = "Below 20 - ordinary work gets branded as chokes and the rescue fires constantly for nothing.",
            cheat = "60 - catches micro-chokes before you feel them, ignores harmless busyness."
        ),
        "lag.stall.alpha" to AdminTuningGuide.Guide(
            what = "Memory dial (0-1) for the average delay: how much the newest poke counts versus history.",
            raise = "The average chases the newest reading - fast alarms, jumpy number.",
            lower = "The average moves calmly - stable, but a real slowdown takes longer to show.",
            pro = "Reaction speed vs stability, without touching the poke rhythm.",
            con = "Extremes make the reading twitchy or sleepy - both fool the judge.",
            bestMin = "0.15",
            bestMax = "0.4",
            risk = "Above 0.7 - one late poke can flip the whole verdict by itself.",
            cheat = "0.3 - a real slowdown shows within 2-3 pokes, single blips are ignored."
        ),
        "lag.stall.report_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the choke counts are summarized into the chokes-per-minute number the judge reads.",
            raise = "Steadier long-view numbers, but a fresh choke storm takes longer to show in the count.",
            lower = "Fresher counts, judge reacts sooner - slightly more bookkeeping.",
            pro = "Keeps the judge's choke input honest and current.",
            con = "Very short windows make the per-minute number jumpy.",
            bestMin = "5000",
            bestMax = "20000",
            risk = "Below 2000 - the per-minute maths gets noisy and the judge flip-flops.",
            cheat = "10000 - stock is right: fresh, stable, cheap."
        ),

        // ---------------- ThermalPeekEngine ----------------
        "lag.thermal.poll_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the app asks Android how hot the phone is. Heat makes phones deliberately slow themselves down - this tells the lag system whether heat is the true enemy.",
            raise = "Fewer checks - practically free anyway, but a heat rise is noticed later.",
            lower = "Heat changes are caught sooner - useful in long game sessions where heat builds.",
            pro = "Separates 'phone is hot' lag from 'phone is overloaded' lag - different problems, different fixes.",
            con = "The reading itself is nearly free, so there is little to gain at extremes.",
            bestMin = "5000",
            bestMax = "30000",
            risk = "Below 1000 - pointless hammering of a value that changes over minutes, not milliseconds.",
            cheat = "10000 - stock; drop to 5000 only if your phone already runs warm (the Detector checks this for you)."
        ),

        // ---------------- DisplayProfileEngine ----------------
        "lag.display.game_fps" to AdminTuningGuide.Guide(
            what = "The frame rate your game runs at. eFootball is locked at 30 frames per second - every lag measurement is graded against this truth.",
            raise = "Only correct if the game itself changes its lock (e.g. a future 60fps mode) - then set it to match.",
            lower = "Never useful for eFootball - grading against a slower game than reality makes real stutter look acceptable.",
            pro = "When the game updates its frame rate someday, you fix it here with zero code editing.",
            con = "A wrong value makes every smoothness verdict wrong - this is a fact-setting, not a tuning dial.",
            bestMin = "30 (eFootball today)",
            bestMax = "30 (until the game itself changes)",
            risk = "Anything that does not match the real game - the whole lag radar grades against a lie.",
            cheat = "30 - the truth. Facts beat tweaks here."
        ),

        // ---------------- LagVerdictEngine ----------------
        "lag.verdict.poll_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the judge reads all the measurements and names the device state: SMOOTH, JITTERY or CHOKING.",
            raise = "Verdicts lag behind reality - rescue starts later after real lag begins.",
            lower = "Verdicts track reality near-live - tiny extra work.",
            pro = "Directly sets how fast the whole rescue chain can react.",
            con = "Checking much faster than measurements refresh just re-reads the same numbers.",
            bestMin = "1000",
            bestMax = "3000",
            risk = "Above 5000 - lag can rage for seconds before the judge even looks.",
            cheat = "1500 - with 2 agreeing checks, real lag is confirmed within ~3 seconds."
        ),
        "lag.verdict.jitter_ms" to AdminTuningGuide.Guide(
            what = "Frame wobble (ms) above this = the judge calls JITTERY. Wobble is the beat-to-beat unevenness you feel as stutter even when the frame rate looks fine.",
            raise = "More wobble tolerated before the alarm - fewer alarms, micro-stutter can pass silently.",
            lower = "Even faint stutter trips the alarm - nothing silent slips by, but natural screen variation may false-alarm.",
            pro = "The main sensitivity dial for felt smoothness.",
            con = "Set below your screen's natural wobble, it cries wolf non-stop.",
            bestMin = "6",
            bestMax = "15",
            risk = "Below 4 - normal adaptive-screen variation reads as stutter; rescue never rests.",
            cheat = "8 - catches the faintest real stutter without fighting your screen. The Detector tunes this to your measured wobble."
        ),
        "lag.verdict.stability_pct" to AdminTuningGuide.Guide(
            what = "The share (%) of frames that must land on the dominant rhythm for the beat to count as steady. Below this = JITTERY.",
            raise = "Demands a steadier beat - catches subtler unevenness, but adaptive screens may never satisfy very high bars.",
            lower = "Accepts a looser beat - fewer alarms, more 'feels off' moments pass unflagged.",
            pro = "Catches the stutter kind that wobble alone can miss (frames drifting between rhythms).",
            con = "Too strict on an adaptive-refresh screen = permanent false JITTERY.",
            bestMin = "55",
            bestMax = "80",
            risk = "Above 90 - an adaptive panel legally mixes rhythms; the judge would call healthy play jittery forever.",
            cheat = "70 - catches 'feels off' while respecting adaptive screens."
        ),
        "lag.verdict.choke_stalls" to AdminTuningGuide.Guide(
            what = "Frozen frames per minute above this = CHOKING, the heavy alarm that triggers full rescue.",
            raise = "More freezes tolerated before heavy rescue - smoother-looking settings, later help.",
            lower = "Heavy help arrives earlier - on a bottlenecked phone, early help is the difference.",
            pro = "Directly decides when the big rescue guns come out.",
            con = "Too low and heavy rescue engages during brief harmless rough patches.",
            bestMin = "5",
            bestMax = "20",
            risk = "Above 40 - the phone is visibly dying before heavy help is even considered.",
            cheat = "8 - on a bottlenecked device, call the heavy help early."
        ),
        "lag.verdict.choke_mtstall_ms" to AdminTuningGuide.Guide(
            what = "Average busy-thread delay (ms) above this = CHOKING. This is lag you feel on every touch, even between frames.",
            raise = "More touch-delay tolerated before heavy rescue.",
            lower = "Heavy rescue on smaller touch-delays - protects feel, may over-trigger on brief loads.",
            pro = "Guards the touch-to-response feel directly - the thing players notice most.",
            con = "Works off an average, so it pairs with the chokes-per-minute line - move them together.",
            bestMin = "80",
            bestMax = "150",
            risk = "Below 50 - ordinary loading moments read as emergencies.",
            cheat = "100 - by the time average delay hits 100ms you feel every touch; rescue exactly there."
        ),
        "lag.verdict.choke_spikes" to AdminTuningGuide.Guide(
            what = "Chokes per minute above this = CHOKING. The backstop for repeated short chokes whose average still looks acceptable.",
            raise = "More frequent chokes tolerated - the average-delay line does the work alone.",
            lower = "Frequent micro-chokes alone can trigger heavy rescue even when the average looks fine.",
            pro = "Catches the death-by-a-thousand-cuts lag pattern.",
            con = "Its meaning depends on the choke line (spike_ms) - lower that, and this count naturally rises.",
            bestMin = "10",
            bestMax = "30",
            risk = "Below 5 - a couple of harmless chokes in a minute already trips the heavy alarm.",
            cheat = "15 - matched to the 60ms choke line: repeated real chokes trip it, stray ones don't."
        ),
        "lag.verdict.confirm_polls" to AdminTuningGuide.Guide(
            what = "How many checks in a row must agree before the judge changes its verdict. The anti-panic debounce.",
            raise = "Rock-solid verdicts, but each extra check delays the rescue by one judge-rhythm.",
            lower = "Verdicts flip faster - at 1, a single blip can whipsaw the whole rescue chain.",
            pro = "One dial that kills verdict flip-flopping.",
            con = "Delay = confirm count x judge rhythm; keep the product small.",
            bestMin = "2",
            bestMax = "3",
            risk = "1 - single-blip whipsawing returns; the rescue chain flaps with it.",
            cheat = "2 - confirmed fast, immune to blips."
        ),

        // ---------------- LoadShedGovernor ----------------
        "lag.shed.poll_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the rescue re-checks the verdict and decides whether to raise or lower its help level (NONE / LIGHT / HEAVY). A SEIZURE freeze burst skips the queue and escalates instantly regardless.",
            raise = "Rescue reacts more slowly to the judge's calls.",
            lower = "Help lands sooner after lag is confirmed - tiny extra work.",
            pro = "Sets rescue reaction time; the seizure fast-path stays instant either way.",
            con = "Below the judge rhythm it just re-reads the same verdict.",
            bestMin = "1000",
            bestMax = "3000",
            risk = "Above 5000 - confirmed lag waits seconds for help that was already approved.",
            cheat = "1500 - rescue moves the moment the judge confirms."
        ),
        "lag.shed.arm_polls" to AdminTuningGuide.Guide(
            what = "How many agreeing checks before help STARTS or gets stronger.",
            raise = "Help arms slower but never on ghosts.",
            lower = "Help arms faster - at 1, a single reading can start shedding work.",
            pro = "Balances instant help against phantom triggers.",
            con = "Real emergencies are already covered by the instant seizure path, so extreme lowering buys little.",
            bestMin = "1",
            bestMax = "3",
            risk = "Far above 3 - by the time help arms, the lag episode may already be over.",
            cheat = "2 - fast and ghost-proof."
        ),
        "lag.shed.release_polls" to AdminTuningGuide.Guide(
            what = "How many CLEAN checks in a row before help stands down back to normal.",
            raise = "Help lingers longer after lag clears - safe, but you play under reduced extras longer.",
            lower = "Full quality returns sooner - too soon and help flaps on/off at the edge of a lag episode.",
            pro = "The stand-down debounce: prevents the on/off flapping that feels worse than lag itself.",
            con = "Each extra check keeps help engaged one judge-rhythm longer.",
            bestMin = "3",
            bestMax = "6",
            risk = "1 - shed/release thrash returns immediately.",
            cheat = "4 - quality back one beat sooner than stock, still flap-proof."
        )
    )
}
