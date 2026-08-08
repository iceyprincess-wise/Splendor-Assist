package com.assistant.admin

/**
 * Layman field guide for every LAG adapter tunable. Same structure and
 * same honesty rule as AdminTuningGuide: every line is read from what the
 * code actually does - no guessing. The live Detector on the settings
 * screen computes device-specific values; when the two disagree, trust
 * the Detector.
 *
 * Returns AdminTuningGuide.Guide so the panel renders both guides the
 * same way.
 */
object AdminTuningGuideLag {

    fun forKey(key: String): AdminTuningGuide.Guide? = GUIDES[key]

    private fun g(what: String, raise: String, lower: String, pro: String, con: String,
                  bestMin: String, bestMax: String, risk: String, cheat: String) =
        AdminTuningGuide.Guide(what, raise, lower, pro, con, bestMin, bestMax, risk, cheat)

    private val GUIDES: Map<String, AdminTuningGuide.Guide> = mapOf(

        // ---------------- MainThreadStallEngine ----------------
        "lag.stall.cadence_ms" to g(
            what = "The app pokes its own brain (the main thread) on this rhythm and times how late the answer comes back. A late answer means the phone is choked - the exact choke that delays your touches.",
            raise = "Pokes come less often - lighter on the phone, but a short choke can happen and finish between two pokes and never get seen.",
            lower = "Pokes come more often - even the shortest choke is caught, at a tiny extra cost of work.",
            pro = "This is the radar for invisible micro-lag: chokes too short to see but long enough to eat a touch.",
            con = "The poke itself is feather-light, but a silly-fast rhythm adds pointless busy-work.",
            bestMin = "150",
            bestMax = "500",
            risk = "Below 50 - the measuring itself becomes load on an already bottlenecked phone.",
            cheat = "200 - no choke a player could feel fits between two pokes."
        ),
        "lag.stall.spike_ms" to g(
            what = "How late the answer must be (in ms) before it is booked as a choke. This defines what officially counts as micro-lag.",
            raise = "Only serious delays get booked - fewer alarms, but small feelable hiccups pass unrecorded.",
            lower = "Even tiny delays get booked - nothing slips by, but normal scheduling noise starts inflating the count.",
            pro = "You decide exactly where 'busy' ends and 'choking' begins.",
            con = "Set far too low, the count screams constantly and the judge over-reacts.",
            bestMin = "50",
            bestMax = "120",
            risk = "Below 20 - ordinary phone housekeeping gets booked as chokes; the rescue never rests and costs you smoothness.",
            cheat = "66 - two game frames: the first moment a delayed touch is actually feelable."
        ),
        "lag.stall.alpha" to g(
            what = "Memory dial (0-1) for the average choke delay - how much the newest poke counts versus history.",
            raise = "The average chases the newest readings - reacts fast, jumps around.",
            lower = "The average moves calmly - steady, but a real choke-up takes longer to show.",
            pro = "Sets how quickly 'device is choking' becomes visible to the judge.",
            con = "Extremes make the reading twitchy or sleepy - both mislead the judge.",
            bestMin = "0.15",
            bestMax = "0.4",
            risk = "Above 0.7 - one long poke swings the whole verdict on its own.",
            cheat = "0.3 - a real choke-up shows within 2-3 pokes, single blips stay ignored."
        ),
        "lag.stall.report_ms" to g(
            what = "How often the choke counter is summarized into chokes-per-minute (the number the judge reads).",
            raise = "Smoother, slower-moving summary - a fresh burst of chokes reaches the judge later.",
            lower = "Fresher summaries - the judge reacts to a burst sooner, slightly more bookkeeping.",
            pro = "Controls how live the chokes-per-minute number is.",
            con = "Very short windows make the number jumpy.",
            bestMin = "5000",
            bestMax = "20000",
            risk = "Below 2000 - the maths gets noisy and the judge flip-flops.",
            cheat = "10000 - fresh numbers every 10 seconds, all match long."
        ),

        // ---------------- ThermalPeekEngine ----------------
        "lag.thermal.poll_ms" to g(
            what = "How often the app asks Android for the official heat status. Heat is the silent lag-maker: a hot phone slows ITSELF down on purpose.",
            raise = "Heat checked less often - fine when cool, but a heat wave is noticed later.",
            lower = "Heat tracked more closely - warming trends are caught as they start.",
            pro = "Free evidence: when lag storms line up with SEVERE heat, you know the true enemy.",
            con = "Heat moves slowly - checking very fast adds nothing.",
            bestMin = "5000",
            bestMax = "30000",
            risk = "Below 1000 - pointless hammering of a value that changes over minutes.",
            cheat = "10000 - stock; drop to 5000 only when the Detector shows your phone running hot."
        ),

        // ---------------- DisplayProfileEngine ----------------
        "lag.display.game_fps" to g(
            what = "The frame rate your game is locked at (eFootball = 30). Every freeze-line and choke-line is calculated FROM this number, so it must state the truth.",
            raise = "Tells the maths the game draws faster - all lines get stricter. Only correct if the game truly runs faster.",
            lower = "Tells the maths the game draws slower - all lines get looser. Only correct if the game truly runs slower.",
            pro = "When the game changes its lock one day, one number here re-tunes the whole adapter - no rebuild.",
            con = "A wrong value here quietly mis-tunes everything built on it.",
            bestMin = "30 (eFootball's real lock)",
            bestMax = "60 (only if the game truly runs 60)",
            risk = "Any value that is not the game's REAL frame rate - the whole adapter grades against a lie.",
            cheat = "30 - the truth. This dial is for facts, not for boosting."
        ),

        // ---------------- LagVerdictEngine ----------------
        "lag.verdict.poll_ms" to g(
            what = "How often the judge reads all the radar numbers and names the device state: SMOOTH, JITTERY or CHOKING.",
            raise = "Verdicts update more slowly - the rescue starts later when lag begins.",
            lower = "Verdicts track the radar near-live - help arrives sooner, tiny extra work.",
            pro = "The reaction-speed dial for the entire rescue chain.",
            con = "Faster than the radar updates adds nothing (it re-reads the same numbers).",
            bestMin = "1000",
            bestMax = "3000",
            risk = "Above 5000 - lag is well underway before the verdict even flips.",
            cheat = "1500 - with 2 agreeing checks, real lag is confirmed in ~3 seconds."
        ),
        "lag.verdict.jitter_ms" to g(
            what = "Frame wobble above this many ms = JITTERY. Wobble is the unevenness between frames - the thing your eye feels as stutter even when the average looks fine.",
            raise = "More wobble tolerated before flagging - fewer alarms, micro-stutter runs longer unflagged.",
            lower = "Flags earlier - protection sooner, but normal wobble may keep it permanently flagged.",
            pro = "Directly sets how much visible unevenness you accept.",
            con = "Must respect your device's natural idle wobble or it cries wolf.",
            bestMin = "6",
            bestMax = "20",
            risk = "Below your idle wobble (see Detector) - permanently JITTERY, rescue never rests, quality drops for nothing.",
            cheat = "2.5x your idle wobble - silent at rest, instant on real stutter. The Detector computes it."
        ),
        "lag.verdict.stability_pct" to g(
            what = "The radar sorts frames into rhythm groups; this is the share (%) that must sit in the main group. Below it = the beat is broken = JITTERY.",
            raise = "Demands a steadier beat - catches subtle rhythm breakdown, may flag phones that are naturally a bit loose.",
            lower = "Tolerates a looser beat - fewer flags, subtle breakdown passes.",
            pro = "Catches the 'not one big freeze, just everything slightly off' lag that wobble alone can miss.",
            con = "Adaptive screens naturally spread frames across groups - too strict misreads them.",
            bestMin = "50",
            bestMax = "75",
            risk = "Above 85 - normal adaptive-screen behaviour reads as breakdown; permanently JITTERY.",
            cheat = "15 points below your idle steady beat (see Detector) - real breakdown crosses it, nothing else does."
        ),
        "lag.verdict.choke_stalls" to g(
            what = "Screen freezes per minute above this = CHOKING (the red alert that triggers heavy help).",
            raise = "More freezes tolerated before the alarm - heavy help comes later.",
            lower = "Alarm comes earlier - a struggling match gets rescued sooner.",
            pro = "Sets exactly how many visible freezes per minute you are willing to live with.",
            con = "Too low and brief rough patches trigger heavy measures that themselves cost quality.",
            bestMin = "5",
            bestMax = "15",
            risk = "0-2 - one bad second per minute keeps heavy mode latched on.",
            cheat = "8 - on a bottlenecked phone, call the big guns early."
        ),
        "lag.verdict.choke_mtstall_ms" to g(
            what = "Average touch delay above this many ms = CHOKING. This is the 'my touches feel late' line.",
            raise = "More touch delay tolerated - alarm later.",
            lower = "Alarm earlier - but every phone has some natural delay; below that it never stops alarming.",
            pro = "Directly guards the thing that matters most in play: how fast your input lands.",
            con = "Needs to sit well above your device's natural idle delay.",
            bestMin = "80",
            bestMax = "200",
            risk = "Below your idle touch delay (see Detector) - permanent CHOKING, heavy mode never releases.",
            cheat = "4x your idle touch delay, never below 100 - the Detector computes it."
        ),
        "lag.verdict.choke_spikes" to g(
            what = "Chokes per minute (from the poke radar) above this = CHOKING. Catches machine-gun micro-lag that never produces one big freeze.",
            raise = "More frequent micro-chokes tolerated before the red alert.",
            lower = "Red alert on lighter machine-gun lag - earlier rescue.",
            pro = "The dedicated trap for 'nothing looks frozen but everything feels late'.",
            con = "Tied to the choke line above - if that line is very low, this fills up with noise.",
            bestMin = "10",
            bestMax = "30",
            risk = "Below 5 - normal busy moments read as choking.",
            cheat = "15 - rapid-fire micro-lag gets caught within one report window."
        ),
        "lag.verdict.confirm_polls" to g(
            what = "How many judge checks in a row must agree before the verdict actually changes.",
            raise = "Rock-steady verdicts, but each extra check delays the rescue by one judge rhythm.",
            lower = "Verdicts flip faster - at 1, a single odd reading whipsaws the whole system.",
            pro = "The anti-flip-flop shield: verdicts mean something.",
            con = "Every extra confirmation is reaction time lost.",
            bestMin = "2",
            bestMax = "3",
            risk = "1 - one blip flips the verdict; rescue arms and disarms in circles, which feels WORSE than the lag.",
            cheat = "2 - confirmed fast, immune to single blips."
        ),

        // ---------------- LoadShedGovernor ----------------
        "lag.shed.poll_ms" to g(
            what = "How often the rescue re-reads the verdict and decides its help level: NONE, LIGHT or HEAVY. A detected freeze burst skips the queue and escalates INSTANTLY regardless.",
            raise = "Level changes happen on a slower beat - calmer, later help.",
            lower = "Help level tracks the verdict tighter.",
            pro = "Second reaction-speed dial, after the judge's own rhythm.",
            con = "The instant freeze fast-path already covers emergencies - this only paces normal changes.",
            bestMin = "1000",
            bestMax = "3000",
            risk = "Above 5000 - the rescue lags behind the judge it is supposed to serve.",
            cheat = "1500 - rescue rides right behind the judge."
        ),
        "lag.shed.arm_polls" to g(
            what = "How many agreeing rescue checks before help actually STARTS (escalation).",
            raise = "Help starts later but never by mistake.",
            lower = "Help starts sooner - at 1, ghosts can trigger it.",
            pro = "Keeps rescue honest without making it slow.",
            con = "Each extra check is seconds of unhelped lag.",
            bestMin = "1",
            bestMax = "3",
            risk = "Above 4 - the lag moment is over before help even arrives.",
            cheat = "2 - armed in ~3 seconds, ghost-proof."
        ),
        "lag.shed.release_polls" to g(
            what = "How many clean checks in a row before help STOPS (stand-down).",
            raise = "Help lingers longer after trouble - safer, slower return to full quality.",
            lower = "Faster return to full quality - too low and it flip-flops.",
            pro = "Stand-down slower than arm-up is exactly what stops the thrash.",
            con = "Every extra check delays full quality after the storm.",
            bestMin = "3",
            bestMax = "6",
            risk = "1 - one calm second ends help and the next wave hits unprotected: shed-release-shed thrash.",
            cheat = "4 - one beat quicker than stock, still flap-proof."
        ),
        "lag.shed.min_hold_ms" to g(
            what = "Once help starts, the minimum time it stays on before ANY level change is allowed - the anti-thrash timer.",
            raise = "Steadier help periods; full quality returns more slowly after short bursts.",
            lower = "Snappier recovery; too low and levels oscillate.",
            pro = "One dial deciding between stability and responsiveness of the whole rescue.",
            con = "A wrong value only shows its cost when load gets spiky.",
            bestMin = "5000",
            bestMax = "15000",
            risk = "Below 2000 - constant level flip-flopping feels worse than the lag it fights.",
            cheat = "6000 - quick quality recovery, clear of the thrash zone. Detector says 10000 when your phone is hot."
        )
    )
}
