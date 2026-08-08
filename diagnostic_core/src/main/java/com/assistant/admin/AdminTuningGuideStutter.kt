package com.assistant.admin

/**
 * Plain-language field guides for the STUTTER ADAPTER settings, same
 * structure as AdminTuningGuide (what / raise / lower / advantage /
 * disadvantage / best min / best max / risk spot / gaming cheat spot).
 * Every line is read from what the code actually does - no guessing. The
 * live DETECTOR on the settings screen computes values from YOUR device's
 * measurements; when the two disagree, trust the Detector.
 */
object AdminTuningGuideStutter {

    fun forKey(key: String): AdminTuningGuide.Guide? = GUIDES[key]

    private val GUIDES: Map<String, AdminTuningGuide.Guide> = mapOf(

        // ---------------- StutterPulseEngine ----------------
        "stutter.pulse.burst_mult" to AdminTuningGuide.Guide(
            what = "A frame counts as LATE when it takes longer than your screen's beat times this number. The radar reads your screen's real beat live (16.7ms on a 60Hz screen), so 2 means 'twice as slow as it should be'.",
            raise = "Only bigger skips count as late - fewer bursts recorded, calmer system, but the faintest micro-stutter starts passing as normal.",
            lower = "Smaller skips count as late - even the softest hitch is caught, but adaptive screens legally mix rhythms and that starts counting too.",
            pro = "You define exactly where 'smooth' ends and 'stutter' begins on your screen.",
            con = "Too low on an adaptive screen and normal rhythm-mixing reads as stutter.",
            bestMin = "1.8 (sharp ears)",
            bestMax = "3 (only clear stutter)",
            risk = "Below 1.2 - the screen's normal behaviour counts as stutter; permanent false alarms and the rescue never rests.",
            cheat = "2 - twice the beat is the proven felt-stutter line. The Detector raises it only if your screen skips at rest."
        ),
        "stutter.pulse.min_frames" to AdminTuningGuide.Guide(
            what = "How many late frames must land inside one watch slice before it counts as a burst. One late frame happens in normal phone life; a group inside a single second is a real stutter.",
            raise = "Only heavier groups count - fewer alarms, but light 2-frame stutters pass silently.",
            lower = "Lighter groups count - nothing slips through, but single random late frames start raising bursts.",
            pro = "The honesty gate: separates real stutter from one-off blips.",
            con = "At 1, every stray late frame is a 'burst' and the picture gets noisy.",
            bestMin = "2",
            bestMax = "4",
            risk = "1 - single late frames flood the forensics; OSCILLATION gets declared on noise.",
            cheat = "2 - the felt-stutter minimum: two skips in one second is exactly when a player notices."
        ),
        "stutter.pulse.slice_ms" to AdminTuningGuide.Guide(
            what = "The length of each watch slice (ms). The radar counts late frames inside each slice, then closes it and starts the next - this is the resolution of the stutter radar.",
            raise = "Longer slices - late frames from different moments get lumped together; small bursts merge and detail blurs.",
            lower = "Shorter slices - sharper timing on each burst, but a real burst can get split across two slices and read as two small ones.",
            pro = "Sets how finely stutter moments are separated in time.",
            con = "Both extremes distort the burst count in opposite directions.",
            bestMin = "500",
            bestMax = "2000",
            risk = "Below 250 - bursts split into fragments; the oscillation counter over-fires on one real event.",
            cheat = "1000 - one second is the natural heartbeat of felt stutter; the classifier is tuned around it."
        ),
        "stutter.pulse.publish_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the radar publishes its live numbers to the admin screen's Detector readout.",
            raise = "The admin screen's numbers refresh more slowly - the engines themselves are unaffected.",
            lower = "Fresher Detector readout, tiny extra bookkeeping.",
            pro = "Keeps the Detector honest about what is happening right now.",
            con = "Only affects the admin screen, never the protection itself.",
            bestMin = "2000",
            bestMax = "10000",
            risk = "Below 1000 - needless file writing every second for a screen you look at occasionally.",
            cheat = "5000 - fresh whenever you open the panel, invisible cost."
        ),

        // ---------------- PanelWatchEngine ----------------
        "stutter.panel.poll_ms" to AdminTuningGuide.Guide(
            what = "Your screen can change its rhythm mid-game (60/90/120Hz adaptive panels). Android tells the app INSTANTLY when that happens; this is only the backup sweep (ms) that double-checks in case that instant signal is ever missed.",
            raise = "Backup checks less often - basically free, the instant signal does the real work.",
            lower = "Backup checks more often - tiny extra battery for near-zero gain.",
            pro = "Guarantees the radar is never grading frames against a stale screen rhythm.",
            con = "Almost no visible effect either way - the instant signal covers 99% of cases.",
            bestMin = "2000",
            bestMax = "15000",
            risk = "Below 1000 - pointless busy-work every second for nothing.",
            cheat = "5000 - relaxed backup; the instant detection is what matters and it is always on."
        ),

        // ---------------- BurstForensicsEngine ----------------
        "stutter.forensics.seizure_ms" to AdminTuningGuide.Guide(
            what = "A burst containing a frame slower than this (ms) is classified SEIZURE - a felt freeze. SEIZURE is the loudest alarm in the whole app: the lag rescue jumps to HEAVY immediately, no waiting for confirmations.",
            raise = "Only longer freezes trigger the emergency - fewer full alerts, but shorter felt freezes get the softer treatment.",
            lower = "Shorter freezes count as emergencies - fastest protection, but heavy-but-normal frames start pulling the loudest alarm.",
            pro = "Direct control over the app's single most aggressive reaction.",
            con = "Because SEIZURE skips all confirmation gates, a too-low line here means constant emergency mode.",
            bestMin = "100",
            bestMax = "250",
            risk = "Below 60 - ordinary heavy frames fire the emergency path non-stop; the rescue thrashes.",
            cheat = "About 8 missed beats of your screen (the Detector computes it exactly) - a freeze anyone feels, never one heavy frame."
        ),
        "stutter.forensics.osc_bursts" to AdminTuningGuide.Guide(
            what = "How many bursts inside the watch window make it OSCILLATION - the rhythmic micro-stutter pattern (stutter... stutter... stutter...) that averages never catch.",
            raise = "The rhythm must repeat more before being named - fewer alarms, longer exposure to the pattern.",
            lower = "The rhythm is named sooner - but two coincidental bursts can get called a pattern.",
            pro = "Catches the exact stutter type players complain about most and metrics miss.",
            con = "Works together with the window length - move them sensibly together.",
            bestMin = "2",
            bestMax = "5",
            risk = "1 - every single burst is a 'pattern'; the word OSCILLATION loses all meaning.",
            cheat = "3 - three strikes inside the window is a rhythm, not bad luck."
        ),
        "stutter.forensics.osc_window_ms" to AdminTuningGuide.Guide(
            what = "The look-back window (ms) the oscillation counter checks. Bursts inside this window count toward the OSCILLATION verdict; older ones age out.",
            raise = "Bursts spread further apart still count as one pattern - catches slow rhythms, but unrelated bursts get linked.",
            lower = "Only tightly-packed bursts count - crisp patterns only, slow rhythms escape.",
            pro = "Tunes what 'rhythmic' means in real seconds.",
            con = "Too wide and every busy afternoon looks like one long oscillation.",
            bestMin = "10000",
            bestMax = "30000",
            risk = "Above 60000 - the whole minute is one window; separate events chain into a permanent pattern verdict.",
            cheat = "15000 - matches how rhythmic stutter actually arrives in waves."
        ),
        "stutter.forensics.calm_after_ms" to AdminTuningGuide.Guide(
            what = "After the last burst, how long (ms) the screen must stay clean before the verdict returns to CALM.",
            raise = "The caution state lingers longer after trouble - very safe, slightly conservative.",
            lower = "CALM returns faster - full confidence back sooner, but a pause between two bursts can be mistaken for recovery.",
            pro = "The anti-flap dial for the stutter verdict's recovery side.",
            con = "Too short and the verdict ping-pongs mid-storm, which downstream engines feel.",
            bestMin = "5000",
            bestMax = "20000",
            risk = "Below 3000 - CALM declared inside the gap of an ongoing oscillation; the system relaxes mid-attack.",
            cheat = "8000 - quicker all-clear than stock, still 8 quiet seconds of real proof."
        ),
        "stutter.forensics.decay_poll_ms" to AdminTuningGuide.Guide(
            what = "How often (ms) the calm-checker looks at the clock to decide whether the quiet time has passed and CALM can be restored.",
            raise = "CALM restoration can arrive a little late after the quiet time is already served.",
            lower = "CALM lands right on time - negligible extra work.",
            pro = "Keeps the recovery timing crisp.",
            con = "Little to win or lose outside the extremes.",
            bestMin = "2000",
            bestMax = "10000",
            risk = "Below 1000 - checking a clock every second that only matters every few seconds.",
            cheat = "3000 - CALM lands within 3s of being earned; the rescue stands down promptly."
        )
    )
}
