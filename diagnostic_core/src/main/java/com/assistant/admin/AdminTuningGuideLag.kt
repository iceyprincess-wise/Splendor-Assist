package com.assistant.admin

/**
 * Layman field guide for the Lag Adapter settings. Every line is read from
 * what the code actually does - no guessing. Same structure as the net
 * guide: what it is / raise / lower / advantage / disadvantage /
 * best min / best max / risk spot / gaming cheat spot.
 *
 * The live DETECTOR on the settings screen computes values from YOUR
 * device's measurements - when the two disagree, trust the Detector.
 */
object AdminTuningGuideLag {

    fun forKey(key: String): AdminTuningGuide.Guide? = GUIDES[key]

    private val GUIDES: Map<String, AdminTuningGuide.Guide> = mapOf(

        // ---------------- MainThreadStallEngine ----------------
        "lag.stall.cadence_ms" to AdminTuningGuide.Guide(
            what = "Your phone has one 'busy thread' that draws everything on screen. This engine pokes it on this rhythm (in ms) and times how late the answer comes back - a late answer means that thread was choking, which you feel as lag.",
            raise = "Pokes come less often - lighter on the phone, but a short choke can happen and finish between pokes, unseen.",
            lower = "Pokes come more often - even brief silent chokes get caught, but the pokes themselves add tiny work to the very thread being watched.",
            pro = "The finest micro-lag radar in the whole adapter: it sees chokes frames alone cannot show.",
            con = "Poking too fast adds noise to the thing being measured.",
            bestMin = "150 (very sharp eyes)",
            bestMax = "500 (relaxed)",
            risk = "Below 50 - the watcher itself becomes a tiny extra load on the busy thread, worsening what it measures.",
            cheat = "200 - catches even half-second silent chokes without adding felt weight."
        ),
        "lag.stall.spike_ms" to AdminTuningGuide.Guide(
            what = "How late (in ms) the busy thread's answer must be before it counts as a choke.",
            raise = "Only heavy chokes get counted - fewer alarms, but small ones pass silently.",
            lower = "Even small delays count - micro-lag is fully visible, but ordinary tiny wobbles start filling the count.",
            pro = "You decide exactly what 'choking' means on your device.",
            con = "Set below your phone's natural wobble, the count becomes noise.",
            bestMin = "50",
            bestMax = "120",
            risk = "Below 20 - normal scheduling wobble floods the count and the judge over-reacts.",
            cheat = "60 - catches the chokes you actually feel, one beat earlier than stock."
        ),
        "lag.stall.alpha" to AdminTuningGuide.Guide(
            what = "Memory dial (0-1) for the average choke delay - how much the newest poke counts versus history.",
            raise = "The average follows the newest poke fast - quick alarms, jumpier number.",
            lower = "Calmer, steadier number - but a real slowdown takes longer to show.",
            pro = "Reaction speed vs stability, without touching the poke rhythm.",
            con = "Extremes make the judge either twitchy or sleepy.",
            bestMin = "0.15",
            bestMax = "0.4",
            risk = "Above 0.7 - one late poke can flip the whole verdict alone.",
            cheat = "0.3 - sees a real slowdown within 2-3 pokes, ignores single blips."
        ),
        "lag.stall.report_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the choke counter is summarized into a per-minute rate the judge reads.",
            raise = "Smoother rate, but a fresh choke storm takes longer to show in the numbers.",
            lower = "Rate updates faster - the judge sees a storm sooner, slightly more bookkeeping.",
            pro = "Sets how live the 'chokes per minute' number is.",
            con = "Very short windows make the rate jumpy.",
            bestMin = "5000",
            bestMax = "20000",
            risk = "Below 2000 - the rate jumps around so much the judge flip-flops.",
            cheat = "10000 - stock is right: fresh enough for the judge's confirm step."
        ),

        // ---------------- DisplayProfileEngine ----------------
        "lag.display.game_fps" to AdminTuningGuide.Guide(
            what = "The frame rate your game is locked at. eFootball is locked at 30. Every lag engine grades smoothness against this truth.",
            raise = "Only correct if the game itself ever unlocks a higher rate - then set the new number here, no rebuild needed.",
            lower = "Only correct if the game ever locks lower. Setting it wrong makes smooth play look laggy or laggy play look smooth.",
            pro = "Future-proofing: when the game changes, you change one number here.",
            con = "This is a fact about the game, not a tuning dial - wrong value = wrong verdicts everywhere.",
            bestMin = "30 (eFootball today)",
            bestMax = "30 (eFootball today)",
            risk = "Any value that is not the game's real lock - every judgment downstream goes blind.",
            cheat = "30 - the truth. There is no gain in 'tweaking' a fact."
        ),

        // ---------------- ThermalPeekEngine ----------------
        "lag.thermal.poll_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the app asks Android how hot the phone is running. Heat makes phones slow themselves down - that IS lag with a different name.",
            raise = "Fewer heat checks - near-zero cost anyway, but a heat spike is noticed later.",
            lower = "Heat changes are seen sooner - so lag storms can be blamed (or cleared) faster.",
            pro = "Tells you WHICH enemy you face: heat throttling or scheduling. The cure differs.",
            con = "The reading itself is nearly free, so this dial changes little.",
            bestMin = "5000",
            bestMax = "30000",
            risk = "Below 1000 - pointless busy-work every second for a value that changes over minutes.",
            cheat = "5000 - during long gaming sessions heat is the silent killer; watch it twice as fast."
        ),

        // ---------------- LagVerdictEngine ----------------
        "lag.verdict.poll_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the judge reads all the measurements and names the device state: SMOOTH, JITTERY or CHOKING.",
            raise = "Verdicts lag behind reality - the rescue starts later.",
            lower = "Verdicts track reality near-live - the rescue starts sooner, tiny extra work.",
            pro = "Together with the confirm count, this IS your reaction time to lag.",
            con = "Below the radars' own rhythms it just re-reads unchanged numbers.",
            bestMin = "1000",
            bestMax = "3000",
            risk = "Above 5000 - a lag storm can rage for 10+ seconds before help even starts.",
            cheat = "1500 - with 2 confirms, lag is confirmed within ~3 seconds."
        ),
        "lag.verdict.jitter_ms" to AdminTuningGuide.Guide(
            what = "Frame wobble line (ms). When frames arrive unevenly by more than this, the judge calls the device JITTERY - the light-lag state.",
            raise = "More wobble tolerated before acting - fewer interventions, more felt micro-stutter.",
            lower = "Even faint unevenness triggers the light rescue - silkier feel, rescue is active more often.",
            pro = "The exact sensitivity of your micro-lag alarm.",
            con = "Below your device's natural wobble, the rescue never rests.",
            bestMin = "6",
            bestMax = "15",
            risk = "Below 3 - even a perfectly healthy phone wobbles this much; permanent false alarm.",
            cheat = "8 - catches the stutter you can feel but not name. Trust the Detector's number over this."
        ),
        "lag.verdict.stability_pct" to AdminTuningGuide.Guide(
            what = "Steady-beat line (%). Frames should land on a steady rhythm; this is the share that must be on-beat, or the judge calls JITTERY.",
            raise = "Stricter smoothness demand - micro-lag caught earlier, rescue active more.",
            lower = "Looser demand - rescue rests more, light stutter passes unhandled.",
            pro = "Catches the 'something feels off' stutter that averages hide.",
            con = "Adaptive screens naturally mix rhythms - too strict misreads that as lag.",
            bestMin = "55",
            bestMax = "80",
            risk = "Above 90 - normal adaptive-screen behaviour reads as lag; the rescue never stands down.",
            cheat = "70 - demands the steadiness good play feels like, without fighting the screen."
        ),
        "lag.verdict.choke_stalls" to AdminTuningGuide.Guide(
            what = "Freeze-rate line: this many screen freezes per minute makes the judge declare CHOKING - the heavy-lag state that triggers the full rescue.",
            raise = "More freezes tolerated before full rescue - fewer heavy interventions, more felt freezing.",
            lower = "Full rescue arrives at the first signs - strongest protection, heavy mode used more.",
            pro = "Directly sets how much freezing you are willing to feel before maximum help.",
            con = "Too low and heavy rescue runs on borderline moments.",
            bestMin = "4",
            bestMax = "20",
            risk = "Above 40 - the phone can freeze nearly every second and the judge still says 'fine'.",
            cheat = "8 - on a bottlenecked device, call in the full rescue early."
        ),
        "lag.verdict.choke_mtstall_ms" to AdminTuningGuide.Guide(
            what = "Busy-thread delay line (ms). When the drawing thread's average answer delay passes this, the judge declares CHOKING.",
            raise = "Deeper choking tolerated before heavy rescue.",
            lower = "Heavy rescue starts at shallower choking - earlier save, heavy mode more often.",
            pro = "Catches death-by-a-thousand-cuts lag where no single frame freezes but everything is late.",
            con = "Too low and moderate load reads as an emergency.",
            bestMin = "80",
            bestMax = "150",
            risk = "Below 40 - ordinary busy moments count as emergencies; heavy rescue never rests.",
            cheat = "100 - one beat earlier than stock, right where lag becomes unmissable."
        ),
        "lag.verdict.choke_spikes" to AdminTuningGuide.Guide(
            what = "Choke-rate line: this many busy-thread chokes per minute also declares CHOKING (the third, independent trigger).",
            raise = "More chokes per minute tolerated.",
            lower = "Fewer chokes needed to trigger the heavy rescue.",
            pro = "Backstop trigger: even if frames look passable, a choking thread is caught.",
            con = "Works together with the poke settings - if you sharpened those, this counts more events.",
            bestMin = "10",
            bestMax = "30",
            risk = "Below 5 - a handful of harmless chokes triggers emergency mode.",
            cheat = "15 - matched to the sharper 60ms choke line above."
        ),
        "lag.verdict.confirm_polls" to AdminTuningGuide.Guide(
            what = "How many judge checks in a row must agree before the verdict actually changes. The anti-panic debounce.",
            raise = "Near-zero false flips, but real lag waits longer to be confirmed.",
            lower = "Verdicts flip faster - at 1, a single blip can swing the whole system.",
            pro = "Stops one weird moment from flip-flopping the rescue on and off.",
            con = "Each extra confirm adds one poll of waiting before help.",
            bestMin = "2",
            bestMax = "3",
            risk = "1 - whipsawing: rescue arms and disarms on single blips, which itself feels like stutter.",
            cheat = "2 - confirmed fast, never panicky. Pair with a 1500ms judge rhythm for ~3s reaction."
        ),

        // ---------------- LoadShedGovernor ----------------
        "lag.shed.poll_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the rescue re-checks the verdict to raise or lower its help level (NONE / LIGHT / HEAVY). A SEIZURE freeze burst skips the queue and escalates instantly regardless.",
            raise = "Slower reaction chain from 'lag named' to 'help started'.",
            lower = "Help starts sooner after the judge speaks - tiny extra work.",
            pro = "Second half of your total reaction time (judge + rescue).",
            con = "Much faster than the judge's rhythm adds nothing - it re-reads the same verdict.",
            bestMin = "1000",
            bestMax = "3000",
            risk = "Above 5000 - confirmed lag sits unhandled for extra seconds.",
            cheat = "1500 - matched to the judge's 1500 so help lands the moment lag is confirmed."
        ),
        "lag.shed.arm_polls" to AdminTuningGuide.Guide(
            what = "Rescue checks in a row that must want MORE help before the level actually rises.",
            raise = "Rescue arms more cautiously - fewer unnecessary helps, later real ones.",
            lower = "Rescue arms faster - at 1 it arms on the judge's first word.",
            pro = "Keeps the help itself from becoming twitchy.",
            con = "Every extra check delays real help by one poll.",
            bestMin = "1",
            bestMax = "3",
            risk = "Above 5 - by the time help arrives the lag moment already hurt you.",
            cheat = "2 - quick to arm without arming on ghosts."
        ),
        "lag.shed.release_polls" to AdminTuningGuide.Guide(
            what = "Rescue checks in a row that must agree things are fine before help is withdrawn.",
            raise = "Help lingers longer after lag ends - very steady, slightly conservative.",
            lower = "Help withdraws quickly - snappier return to full everything, risk of lag bouncing straight back.",
            pro = "The asymmetry (fast to help, slow to leave) is what kills lag flapping.",
            con = "Too high and you sit in reduced mode after the storm has clearly passed.",
            bestMin = "3",
            bestMax = "8",
            risk = "1 - help leaves at the first good moment and the lag snaps back: flap-lag, worse than the original.",
            cheat = "4 - stands down one beat sooner than stock, still flap-proof."
        ),
        "lag.shed.min_hold_ms" to AdminTuningGuide.Guide(
            what = "Once the help level changes, the minimum time (ms) it must stay before it may change again. The anti-thrash timer.",
            raise = "Rock-steady help periods, but full quality returns sluggishly after short lag bursts.",
            lower = "Snappier level changes - with growing risk of rapid on-off cycling that itself feels like stutter.",
            pro = "One dial deciding between stability and responsiveness of the whole rescue.",
            con = "A wrong value is invisible until load gets spiky - then it is very visible.",
            bestMin = "5000",
            bestMax = "15000",
            risk = "Below 2000 - help flips on/off in waves; the flipping is worse than the lag it fights.",
            cheat = "6000 - quicker quality recovery while staying clear of the thrash zone."
        )
    )
}
