package com.assistant.admin

/**
 * Plain-language field guides for the LAG ADAPTER settings, same structure
 * as AdminTuningGuide (what / raise / lower / advantage / disadvantage /
 * best min / best max / risk spot / gaming cheat spot). Every line is read
 * from what the code actually does - no guessing. The live DETECTOR on the
 * settings screen computes values from YOUR device's measurements; when the
 * two disagree, trust the Detector.
 */
object AdminTuningGuideLag {

    fun forKey(key: String): AdminTuningGuide.Guide? = GUIDES[key]

    private val GUIDES: Map<String, AdminTuningGuide.Guide> = mapOf(

        // ---------------- MainThreadStallEngine ----------------
        "lag.stall.cadence_ms" to AdminTuningGuide.Guide(
            what = "The app pokes your phone's main working thread on this rhythm (in ms) and measures how late each poke answers. When the phone chokes, the answer comes late - that lateness IS the lag your finger feels as a slow touch.",
            raise = "Pokes come less often - lighter on the phone, but a short choke can hide entirely between two pokes and never get counted.",
            lower = "Pokes come more often - even the shortest choke gets caught, but the measuring itself adds a tiny bit of work.",
            pro = "This is the radar for the exact lag a player feels in their finger.",
            con = "Poking extremely fast makes the radar itself part of the load it measures.",
            bestMin = "100 (catches everything)",
            bestMax = "500 (relaxed)",
            risk = "Below 50 - the poking itself becomes busy-work on the very thread you are protecting.",
            cheat = "200 - nothing a player can feel slips through, cost stays invisible."
        ),
        "lag.stall.spike_ms" to AdminTuningGuide.Guide(
            what = "How late (in ms) one poke's answer must be before it counts as a choke. Below this line it is normal busyness; above it, it goes on the choke counter the judge reads.",
            raise = "Only serious chokes get counted - fewer alarms, but mild sluggishness passes as normal.",
            lower = "Even mild lateness counts - the judge hears about trouble earlier, but ordinary heavy moments start filling the counter.",
            pro = "You define exactly what 'the phone choked' means on your device.",
            con = "Set close to your phone's normal busyness and the counter fills with noise.",
            bestMin = "50",
            bestMax = "120",
            risk = "Below 30 - normal work on a budget phone counts as choking; the rescue never rests.",
            cheat = "66 - exactly two missed game frames; the first moment a human can feel. Trust the Detector to place it for YOUR phone."
        ),
        "lag.stall.alpha" to AdminTuningGuide.Guide(
            what = "Memory dial (0-1) for the average touch delay. Decides how much the newest poke counts versus history.",
            raise = "The average chases the newest poke - reacts fast, but one slow poke can swing the whole picture.",
            lower = "The average moves calmly - stable, but a real slow-down takes several pokes to show.",
            pro = "Reaction speed vs stability, without touching the poke rhythm.",
            con = "Extremes make the number either twitchy or sleepy - both mislead the judge.",
            bestMin = "0.15",
            bestMax = "0.4",
            risk = "Above 0.7 - a single busy moment reads like a full choke-up.",
            cheat = "0.3 - a real choke shows within 2-3 pokes, single blips stay ignored."
        ),
        "lag.stall.report_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the choke counter is summarized into the chokes-per-minute number the judge reads.",
            raise = "Steadier number, but a fresh choke-storm takes longer to show in it.",
            lower = "Fresher number, slightly more bookkeeping.",
            pro = "Keeps the judge's input honest and current.",
            con = "Very short windows make the per-minute math jumpy.",
            bestMin = "5000",
            bestMax = "20000",
            risk = "Below 2000 - the per-minute number swings wildly from tiny samples.",
            cheat = "10000 - stock is right: live enough, steady enough."
        ),

        // ---------------- ThermalPeekEngine ----------------
        "lag.thermal.poll_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the app reads Android's official heat status. Heat is the silent lag-maker: a hot phone secretly slows itself down.",
            raise = "Heat checked less often - practically free either way, but a heat rise is noticed later.",
            lower = "Heat rises are caught sooner, letting you connect lag to heat in real time.",
            pro = "Names the invisible enemy: when lag and heat rise together, you KNOW it is throttling.",
            con = "The reading is nearly free, so there is little to win or lose here.",
            bestMin = "3000",
            bestMax = "30000",
            risk = "Below 1000 - pointless hammering of a value that changes over seconds, not milliseconds.",
            cheat = "5000 while gaming in heat, 10000 otherwise - exactly how the Detector picks it."
        ),

        // ---------------- DisplayProfileEngine ----------------
        "lag.display.game_fps" to AdminTuningGuide.Guide(
            what = "The frame rate your game runs at. eFootball is locked at 30. Every freeze-line and budget in the lag engines is computed from this truth.",
            raise = "Tells the engines the game draws faster - budgets tighten; only correct if the game truly changed.",
            lower = "Tells the engines the game draws slower - budgets loosen; only correct if the game truly changed.",
            pro = "When the game some day changes its lock, one number here re-tunes the whole adapter - no rebuild.",
            con = "A wrong value makes every line slightly wrong, silently.",
            bestMin = "30 (eFootball today)",
            bestMax = "60 (only if the game truly runs 60)",
            risk = "Any value that is not the game's REAL frame rate - the whole adapter grades against a lie.",
            cheat = "30 - the truth. This dial is not a booster; it is a fact the engines rely on."
        ),

        // ---------------- LagVerdictEngine ----------------
        "lag.verdict.poll_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the judge reads all lag measurements and names the device state: SMOOTH, JITTERY or CHOKING.",
            raise = "Verdicts lag behind reality - the rescue starts late.",
            lower = "Verdicts track reality near-live - tiny extra work.",
            pro = "Sets how quickly the whole defence chain reacts, judge first.",
            con = "Much faster than the radars refresh adds nothing - same numbers re-read.",
            bestMin = "1000",
            bestMax = "3000",
            risk = "Above 5000 - by the time CHOKING is declared you have felt it for seconds.",
            cheat = "1500 - with 2 agreeing checks, real lag is confirmed in ~3 seconds."
        ),
        "lag.verdict.jitter_ms" to AdminTuningGuide.Guide(
            what = "Frame wobble (ms) above which the judge says JITTERY. Wobble is the unevenness between frames - the thing your eye reads as stutter even when the average looks fine.",
            raise = "More wobble tolerated before JITTERY - fewer alarms, silent micro-stutter passes unpunished.",
            lower = "Even faint stutter triggers JITTERY - earliest protection, but a naturally uneven phone lives in alarm.",
            pro = "The micro-stutter tripwire - catches what no average can see.",
            con = "Must respect YOUR phone's natural resting wobble or it cries wolf.",
            bestMin = "8",
            bestMax = "25",
            risk = "Below your phone's resting wobble - permanent JITTERY, permanent light rescue, wasted performance.",
            cheat = "2.5x your resting wobble - silent at rest, instant in real stutter. The Detector computes this exactly."
        ),
        "lag.verdict.stability_pct" to AdminTuningGuide.Guide(
            what = "The steady-beat score (%) below which the judge says JITTERY. It measures how many frames land on the panel's main rhythm - high means clean, low means messy pacing.",
            raise = "Stricter demand for steadiness - earlier alarms on messy pacing, but adaptive screens naturally dip and may false-alarm.",
            lower = "More mess tolerated before alarming - fewer false alarms, later real ones.",
            pro = "Catches 'everything is technically fast but feels wrong' - the pacing sickness.",
            con = "Adaptive-refresh panels legally mix rhythms; too strict reads that as sickness.",
            bestMin = "40",
            bestMax = "75",
            risk = "Above 85 - normal adaptive-panel behaviour counts as lag; permanent alarm.",
            cheat = "15 points below your resting score - only a real fall from YOUR normal trips it. Detector places it."
        ),
        "lag.verdict.choke_stalls" to AdminTuningGuide.Guide(
            what = "Freezes-per-minute above which the judge declares CHOKING - the emergency state that sends the rescue to HEAVY.",
            raise = "More freezes tolerated before emergency - rescue arrives later into a bad episode.",
            lower = "Emergency declared sooner - strongest protection, but occasional single freezes can over-trigger it.",
            pro = "Direct control over when lag officially becomes an emergency.",
            con = "Too low and brief rough patches get treated like disasters.",
            bestMin = "5",
            bestMax = "15",
            risk = "0-2 - one bad second flips the whole system into emergency mode.",
            cheat = "8 - already unplayable territory; call the emergency while the match is still winnable."
        ),
        "lag.verdict.choke_mtstall_ms" to AdminTuningGuide.Guide(
            what = "Average touch delay (ms) above which the judge declares CHOKING. When the main thread is this late on average, every tap is arriving late to the game.",
            raise = "More touch delay tolerated - emergency later; sluggish-hands moments pass as normal.",
            lower = "Emergency at milder delay - snappier protection, but heavy-but-fine moments can trigger it.",
            pro = "Ties the emergency to the exact thing that loses matches: late touches.",
            con = "Must sit far above your resting delay or it is permanently on.",
            bestMin = "100",
            bestMax = "200",
            risk = "Below 60 - overlaps normal busy moments; the rescue never stands down.",
            cheat = "4x your resting touch delay - a true emergency on YOUR phone, never noise. Detector computes it."
        ),
        "lag.verdict.choke_spikes" to AdminTuningGuide.Guide(
            what = "Chokes-per-minute above which the judge declares CHOKING - the machine-gun pattern: many small chokes, no single big freeze.",
            raise = "More frequent small chokes tolerated - the pattern must get worse before the emergency call.",
            lower = "The pattern is trapped earlier - but busy normal phones brush this line.",
            pro = "Catches death-by-a-thousand-cuts lag that the freeze counter misses.",
            con = "Overlaps the freeze line's job; move them together sensibly.",
            bestMin = "10",
            bestMax = "30",
            risk = "Below 5 - ordinary background housekeeping counts as an emergency.",
            cheat = "15 - one choke every 4 seconds already feels like heavy hands; trap it there."
        ),
        "lag.verdict.confirm_polls" to AdminTuningGuide.Guide(
            what = "How many judge checks in a row must agree before the verdict actually flips. The anti-panic gate.",
            raise = "Rock-steady verdicts, but real trouble waits longer to be confirmed (each step = one poll rhythm).",
            lower = "Instant verdicts, but a single weird moment can flip the whole system.",
            pro = "One dial kills verdict flip-flopping completely.",
            con = "Every extra step delays the rescue by one check.",
            bestMin = "2",
            bestMax = "3",
            risk = "1 - single-blip flips return; the system becomes jumpy exactly when things are rough.",
            cheat = "2 - confirmed fast, immune to blips. With a 1.5s rhythm that is ~3s to certainty."
        ),

        // ---------------- LoadShedGovernor (new dials) ----------------
        "lag.shed.poll_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the rescue re-reads the judge's verdict and decides whether to raise or lower its help level (NONE / LIGHT / HEAVY).",
            raise = "Rescue reacts more slowly to both trouble and recovery.",
            lower = "Rescue rides right behind the judge - help arrives and leaves promptly.",
            pro = "Keeps rescue timing glued to verdict timing.",
            con = "Faster than the judge's rhythm adds nothing - same verdict re-read.",
            bestMin = "1000",
            bestMax = "3000",
            risk = "Above 5000 - the judge shouts CHOKING and the rescue strolls in seconds later.",
            cheat = "1500 - matches the judge's cheat rhythm one-to-one."
        ),
        "lag.shed.arm_polls" to AdminTuningGuide.Guide(
            what = "How many rescue checks in a row must want MORE help before the level actually rises.",
            raise = "Help starts later but never by mistake.",
            lower = "Help starts at the first sign - fastest aid, occasionally unnecessary.",
            pro = "Filters one-off blips out of the decision to intervene.",
            con = "Each extra check delays real help by one rhythm.",
            bestMin = "1",
            bestMax = "3",
            risk = "Above 5 - by the time help arrives the bad episode may already be over.",
            cheat = "2 - armed in ~3 seconds, never armed by one bad moment."
        ),
        "lag.shed.release_polls" to AdminTuningGuide.Guide(
            what = "How many CLEAN checks in a row are needed before the rescue stands down to NONE.",
            raise = "Help lingers longer after trouble - very stable, slightly conservative.",
            lower = "Full quality returns sooner - but too eager and it flaps: help off, lag back, help on...",
            pro = "The anti-flap dial for the recovery side.",
            con = "Every extra check keeps light rescue running a little longer than needed.",
            bestMin = "3",
            bestMax = "6",
            risk = "1 - one lucky clean check ends the help mid-storm and the flip-flopping feels worse than the lag.",
            cheat = "4 - one beat quicker than stock, still flap-proof."
        )
    )
}
