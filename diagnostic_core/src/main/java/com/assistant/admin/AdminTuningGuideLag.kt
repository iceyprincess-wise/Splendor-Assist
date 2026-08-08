package com.assistant.admin

/**
 * Field guide for the LAG adapter tunables, written in plain language for a
 * non-technical admin. Every line is read from what the code actually does -
 * no guessing. Reuses AdminTuningGuide.Guide so both guides render the same.
 *
 * "Gaming cheat spot" = the prime pick for smooth pro gaming on a typical
 * phone. The live DETECTOR on the settings screen goes further and computes
 * values from YOUR device's measurements - when the two disagree, trust the
 * Detector, it is looking at your actual device.
 */
object AdminTuningGuideLag {

    fun forKey(key: String): AdminTuningGuide.Guide? = GUIDES[key]

    private val GUIDES: Map<String, AdminTuningGuide.Guide> = mapOf(

        // ---------------- FramePacingEngine ----------------
        "lag.frame.alpha" to AdminTuningGuide.Guide(
            what = "The memory dial (0 to 1) for the smoothness reading. The app watches every single frame your screen draws; this decides how much the newest frame counts versus history when it averages how smooth things are.",
            raise = "The reading chases the newest frames - a developing stutter is flagged very fast, but one single slow frame can also swing the reading and cause a false alarm.",
            lower = "The reading moves calmly and ignores one-off slow frames, but a real slowdown takes a little longer to show up.",
            pro = "Sets the reaction speed of the number every other lag engine trusts.",
            con = "Too high and one heavy frame looks like a trend; too low and real lag hides for seconds.",
            bestMin = "0.1 (very calm)",
            bestMax = "0.35 (very alert)",
            risk = "Above 0.6 - every isolated slow frame yanks the reading around and the judge flip-flops.",
            cheat = "0.25 - sees real slowdowns a beat sooner than stock while staying calm."
        ),
        "lag.frame.report_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the frame watcher hands its smoothness summary (steady-beat %, freezes, worst gap) to the rest of the system.",
            raise = "Longer, smoother-looking summaries - but a fresh lag episode waits longer to appear in a report.",
            lower = "Fresher summaries more often - the judge works with newer facts, tiny extra bookkeeping.",
            pro = "Sets how current the 'how smooth are we' story is.",
            con = "Very short windows make the numbers noisy because too few frames are counted per window.",
            bestMin = "10000",
            bestMax = "30000",
            risk = "Below 2000 - noisy, spammy reports that add overhead and insight nothing.",
            cheat = "15000 - catches a lag episode one report earlier than stock."
        ),
        "lag.frame.stall_ms" to AdminTuningGuide.Guide(
            what = "A frame gap longer than this many milliseconds is counted as a FREEZE - the line between 'one slow frame' and 'the game visibly hung'.",
            raise = "Only big freezes get counted - micro-freezes slip under the radar and no rescue comes for them.",
            lower = "Smaller hiccups start counting as freezes - rescue comes earlier, but ordinary heavy frames may trigger it for nothing.",
            pro = "Defines the exact pain level the rescue system responds to.",
            con = "Set near your screen's normal frame time, normal rendering gets branded as freezing.",
            bestMin = "80",
            bestMax = "150",
            risk = "Below 35 - every tiny skipped beat is a 'freeze'; rescue never stands down and quality suffers non-stop.",
            cheat = "90 - catches a real felt freeze while ignoring single heavy frames. The Detector computes the exact number from your screen."
        ),

        // ---------------- MainThreadStallEngine ----------------
        "lag.stall.cadence_ms" to AdminTuningGuide.Guide(
            what = "The app pokes its own main thread on this rhythm (ms) and measures how late each poke answers. A late answer means the phone was choked at that moment - the invisible lag you feel as heavy, delayed touch.",
            raise = "Fewer pokes - lighter on the phone, but short chokes can hide between two pokes and go uncounted.",
            lower = "More pokes - even the shortest choke gets caught, at a tiny extra cost per second.",
            pro = "This is the net that catches the silent micro-lag no frame counter can see.",
            con = "The poke itself is nearly free, but a silly-fast rhythm just measures itself.",
            bestMin = "100",
            bestMax = "500",
            risk = "Below 50 - the measuring rhythm becomes its own load and pollutes the reading.",
            cheat = "200 - no choke a player could feel fits between two pokes."
        ),
        "lag.stall.spike_ms" to AdminTuningGuide.Guide(
            what = "A poke answered later than this many ms counts as a CHOKE. This is the sensitivity line for the touch-delay net.",
            raise = "Only heavy chokes count - fewer alarms, but light chokes that still feel sticky go unreported.",
            lower = "Lighter chokes get counted - earlier warnings, but normal tiny delays may start counting too.",
            pro = "You choose exactly what 'the phone hesitated' means.",
            con = "Below your device's natural noise level it alarms at rest.",
            bestMin = "50",
            bestMax = "120",
            risk = "Below 20 - ordinary scheduling noise gets branded as chokes; the count becomes meaningless.",
            cheat = "66 - two missed 30fps game beats; anything past that a player can feel. The Detector tunes this above YOUR device's rest noise."
        ),
        "lag.stall.alpha" to AdminTuningGuide.Guide(
            what = "Memory dial (0 to 1) for the average touch delay - how much the newest poke counts versus history.",
            raise = "The delay number reacts fast - real chokes show almost instantly, single blips also swing it more.",
            lower = "The number moves smoothly - stable, but a real choke-up takes several pokes to register.",
            pro = "Balances alarm speed against false alarms for the touch-delay reading.",
            con = "Extremes make the number twitchy or sleepy - both mislead the judge.",
            bestMin = "0.15",
            bestMax = "0.4",
            risk = "Above 0.7 - a single late poke can shove the average over the emergency line by itself.",
            cheat = "0.3 - a real choke-up shows within 2-3 pokes, single blips stay ignored."
        ),
        "lag.stall.report_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the choke counter is summarized into chokes-per-minute for the judge.",
            raise = "Steadier count, older news - the judge reacts to chokes later.",
            lower = "Fresher count more often - slightly noisier number.",
            pro = "Sets how live the chokes-per-minute figure is.",
            con = "Very short windows exaggerate: one choke in 2 seconds reads as 30/min.",
            bestMin = "5000",
            bestMax = "20000",
            risk = "Below 2000 - single chokes explode into huge per-minute numbers and cause panic verdicts.",
            cheat = "10000 - live enough for the judge, zero waste."
        ),

        // ---------------- ThermalPeekEngine ----------------
        "lag.thermal.poll_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the app asks Android for the phone's heat level. When a phone gets hot it silently slows itself down - that slowdown IS lag with a hidden cause.",
            raise = "Heat is checked less often - a rising heat level is caught later, so a heat-lag storm gets blamed on the wrong suspect for longer.",
            lower = "Heat changes are caught sooner - the reading is nearly free, so faster costs almost nothing.",
            pro = "Tells you WHICH enemy you face: heat throttling or scheduling trouble - the fixes are different.",
            con = "The reading only changes every so often anyway; checking insanely fast adds nothing.",
            bestMin = "3000",
            bestMax = "20000",
            risk = "Below 1000 - pure busy-work; the heat level does not change that fast.",
            cheat = "10000 normally, 5000 when your phone already runs warm - exactly what the Detector picks from your live heat."
        ),

        // ---------------- DisplayProfileEngine ----------------
        "lag.display.game_fps" to AdminTuningGuide.Guide(
            what = "The frame rate your game is locked at. eFootball runs locked at 30. Every freeze and choke line in the lag system is computed FROM this number, so it must state the truth.",
            raise = "Tell it a higher number only if the game truly runs faster (say a future 60fps update) - lines get stricter to match the faster game.",
            lower = "Tell it a lower number only if the game truly runs slower - lines relax to match.",
            pro = "When the game changes its lock one day, you fix the whole lag system HERE with one number - no rebuild.",
            con = "Setting a number that does not match the real game skews every freeze line at once.",
            bestMin = "30 (eFootball today)",
            bestMax = "60 (only if the game truly runs 60)",
            risk = "Any number the game does NOT actually run at - the whole lag system grades against a lie. (0 or blank safely falls back to 30.)",
            cheat = "30 - the truth for eFootball. Truth IS the cheat here."
        ),

        // ---------------- LagVerdictEngine ----------------
        "lag.verdict.poll_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the judge reads all the lag measurements and names the device state: SMOOTH, JITTERY or CHOKING. Rescue reacts to this name.",
            raise = "Verdicts lag behind reality - rescue starts later after real trouble begins.",
            lower = "Verdicts track reality near-live - rescue starts sooner, tiny extra cost.",
            pro = "The single dial for how fast the whole protection chain reacts.",
            con = "Much faster than the measurements refresh just re-reads the same numbers.",
            bestMin = "1000",
            bestMax = "4000",
            risk = "Above 8000 - trouble parties for 8 seconds before the judge even looks.",
            cheat = "1500 - with 2 agreeing checks, real lag is confirmed in about 3 seconds."
        ),
        "lag.verdict.jitter_ms" to AdminTuningGuide.Guide(
            what = "Frame wobble above this many ms makes the judge say JITTERY - the light-warning level that arms gentle help.",
            raise = "More wobble is tolerated before the warning - fewer warnings, later gentle help.",
            lower = "Warnings come at milder wobble - earlier help, but normal wobble may trigger it constantly.",
            pro = "Sets your personal line between 'fine' and 'starting to stutter'.",
            con = "Below your device's natural wobble it cries wolf non-stop and help never stands down.",
            bestMin = "8",
            bestMax = "25",
            risk = "Below your device's resting wobble (see the Detector line) - permanent false JITTERY.",
            cheat = "Trust the Detector: it sets this at 2.5x YOUR device's resting wobble - silent at rest, instant in real stutter."
        ),
        "lag.verdict.stability_pct" to AdminTuningGuide.Guide(
            what = "The steady-beat score is the share of frames landing on their expected beat. Below this %, the judge says JITTERY.",
            raise = "Stricter smoothness demand - warnings come earlier, possibly too eagerly.",
            lower = "More irregularity tolerated - fewer warnings, later help.",
            pro = "Catches the 'nothing froze but it feels off' kind of lag that wobble alone can miss.",
            con = "Set above your device's natural score it alarms at rest.",
            bestMin = "40",
            bestMax = "70",
            risk = "Above your device's resting steady-beat (see the Detector line) - permanent false alarm.",
            cheat = "15 points below YOUR resting score - exactly what the Detector computes."
        ),
        "lag.verdict.choke_stalls" to AdminTuningGuide.Guide(
            what = "Visible freezes per minute above this makes the judge declare CHOKING - the emergency state that triggers heavy rescue.",
            raise = "More freezes tolerated before emergency - heavy help comes later.",
            lower = "Emergency declared sooner - heavy help earlier, but occasional harmless freezes may over-trigger it.",
            pro = "Direct control over when the big guns come out.",
            con = "Heavy rescue drops non-essential work - calling it for nothing costs quality.",
            bestMin = "5",
            bestMax = "15",
            risk = "Above 30 - the game is a slideshow before help ever arrives.",
            cheat = "8 - already unplayable territory; call the emergency while the match is still winnable."
        ),
        "lag.verdict.choke_mtstall_ms" to AdminTuningGuide.Guide(
            what = "Average touch delay above this many ms also declares CHOKING - the second emergency trigger, for when the phone is choked even between freezes.",
            raise = "Bigger touch delay tolerated - emergency later.",
            lower = "Emergency at lighter delay - earlier, possibly too early on a naturally slow device.",
            pro = "Catches chokes that freeze counting misses (heavy hands with no visible freeze).",
            con = "Below your device's resting delay it lives in emergency mode.",
            bestMin = "100",
            bestMax = "200",
            risk = "Below your device's resting touch delay (see the Detector line) - permanent emergency, quality destroyed for nothing.",
            cheat = "4x YOUR resting touch delay - the Detector computes it; a true emergency, never idle noise."
        ),
        "lag.verdict.choke_spikes" to AdminTuningGuide.Guide(
            what = "Chokes per minute above this also declares CHOKING - the machine-gun trigger: many small chokes with no single big freeze.",
            raise = "More rapid-fire chokes tolerated before emergency.",
            lower = "Emergency on lighter machine-gun choking - earlier heavy help.",
            pro = "The third net - stutter-storms trip this even when the other two stay quiet.",
            con = "Too low and busy-but-fine moments read as emergencies.",
            bestMin = "10",
            bestMax = "30",
            risk = "Below 5 - normal background housekeeping can read as an emergency.",
            cheat = "15 - past this it feels like heavy hands even with zero big freezes; trap it here."
        ),
        "lag.verdict.confirm_polls" to AdminTuningGuide.Guide(
            what = "How many judge checks in a row must agree before the verdict actually changes. The anti-flip-flop shield.",
            raise = "Rock-solid verdicts that never flap - but every real change waits that many checks to be believed.",
            lower = "Verdicts change faster - at 1, a single blip can flip the state and rescue thrashes.",
            pro = "Kills verdict flip-flopping, which feels worse than the lag itself.",
            con = "Each extra check delays confirmed rescue by one poll.",
            bestMin = "2",
            bestMax = "3",
            risk = "1 - single-blip whipsawing; 5+ - rescue arrives seconds after the pain started.",
            cheat = "2 - confirmed in two checks, immune to single blips."
        ),

        // ---------------- LoadShedGovernor ----------------
        "lag.shed.min_hold_ms" to AdminTuningGuide.Guide(
            what = "Once rescue changes its help level, this is the minimum ms it must hold that level before changing again. The anti-thrash timer.",
            raise = "Very steady help periods - but full quality returns sluggishly after short trouble.",
            lower = "Snappier return to full quality - with growing risk of rapid on/off flapping.",
            pro = "One dial deciding between stability and responsiveness of the whole rescue system.",
            con = "A wrong value only shows its cost when trouble comes in waves.",
            bestMin = "5000",
            bestMax = "15000",
            risk = "Below 2000 - help flaps on/off; the flapping feels worse than the lag it fights.",
            cheat = "6000 - quick quality recovery, clear of the thrash zone. The Detector says 10000 when your phone runs warm (heat lag comes in waves)."
        ),
        "lag.shed.poll_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) rescue re-reads the judge's verdict and decides whether to raise or lower its help level.",
            raise = "Rescue reacts later to a verdict change.",
            lower = "Rescue rides right behind the judge - help arrives sooner.",
            pro = "Keeps the verdict-to-help delay short. Real freezes skip the queue instantly regardless (the emergency fast path always runs).",
            con = "Faster than the judge's own rhythm adds nothing - it re-reads the same verdict.",
            bestMin = "1000",
            bestMax = "4000",
            risk = "Above 8000 - the judge shouts CHOKING and rescue strolls in seconds later.",
            cheat = "1500 - matches the judge's cheat rhythm one-to-one."
        ),
        "lag.shed.arm_polls" to AdminTuningGuide.Guide(
            what = "How many rescue checks in a row must want MORE help before help actually increases.",
            raise = "Help arms more cautiously - fewer false starts, later real help.",
            lower = "Help arms faster - at 1, a single bad check triggers it.",
            pro = "Stops one bad moment from dropping quality for nothing.",
            con = "Every extra check is one more poll of pain before help.",
            bestMin = "1",
            bestMax = "3",
            risk = "Above 5 - by the time help arms, the bad spell may already be over.",
            cheat = "2 - armed in ~3 seconds, never by mistake."
        ),
        "lag.shed.release_polls" to AdminTuningGuide.Guide(
            what = "How many CLEAN checks in a row are needed before help stands down completely.",
            raise = "Help lingers longer after trouble - very safe, quality returns later.",
            lower = "Quality returns sooner - but trouble that comes in waves may catch the system with its guard just dropped.",
            pro = "The guard-down dial: how sure 'it's over' must be.",
            con = "Each extra check keeps reduced quality one poll longer.",
            bestMin = "3",
            bestMax = "6",
            risk = "1 - guard drops on one good moment and the next wave lands unprotected.",
            cheat = "4 - one beat quicker back to full quality than stock, still flap-proof."
        )
    )
}
