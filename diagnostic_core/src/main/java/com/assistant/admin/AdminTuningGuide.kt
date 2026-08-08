package com.assistant.admin

/**
 * Field guide for every admin tunable, written in plain language for a
 * non-technical admin. Every line is read from what the code actually
 * does - no guessing. Structure per setting:
 *   what it is / raise / lower / advantage / disadvantage /
 *   best min / best max / risk spot / gaming cheat spot.
 *
 * "Gaming cheat spot" = the prime pick for smooth pro gaming on a typical
 * phone. The live DETECTOR on the settings screen goes further and computes
 * a value from YOUR device's measurements - when the two disagree, trust
 * the Detector, it is looking at your actual network.
 *
 * Keys match AdminConfigStore.TUNABLES. A tunable without an entry simply
 * shows no guide - nothing breaks.
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
        append("- Best min: ").append(g.bestMin).append('\n')
        append("- Best max: ").append(g.bestMax).append('\n')
        append("- Risk spot (never go here): ").append(g.risk).append('\n')
        append("- Gaming cheat spot: ").append(g.cheat)
    }

    private val GUIDES: Map<String, Guide> = mapOf(

        // ---------------- NetProbeEngine ----------------
        "net.probe.fast_ms" to Guide(
            what = "When your connection is flagged bad, this is how many milliseconds the app waits between health checks. Each check pings the internet and updates the picture every other engine relies on.",
            raise = "The app checks less often. Your phone rests more and saves battery, but when the network recovers (or gets worse) the app finds out later - so it reacts later.",
            lower = "The app checks more often. Trouble and recovery are spotted almost immediately, but the phone works more and the checks themselves add a little traffic.",
            pro = "The single biggest dial for how fast the whole system notices network change when things are rough.",
            con = "Checking too fast can add noise to the very connection it is trying to measure.",
            bestMin = "1000 (very alert, costs some battery)",
            bestMax = "3000 (relaxed, still fine)",
            risk = "Below 500 - the checks themselves crowd your game's traffic and can CAUSE the lag you are fighting.",
            cheat = "1500 - the app feels trouble almost live without disturbing the game."
        ),
        "net.probe.calm_ms" to Guide(
            what = "Same as above but for when your connection is healthy: the quiet-time heartbeat that keeps the picture fresh.",
            raise = "Fewer background checks - saves battery, but the first sign of new trouble is caught later.",
            lower = "The picture stays fresher and trouble is caught earlier, for a bit more background activity.",
            pro = "Cheap place to save battery, since most of the time your connection is fine.",
            con = "Too high and the app is half-asleep between checks - trouble can build unseen.",
            bestMin = "3000",
            bestMax = "10000",
            risk = "Above 15000 - the connection can go bad and stay unnoticed for the whole gap.",
            cheat = "4000 - near-live awareness for a small battery cost."
        ),
        "net.probe.timeout_ms" to Guide(
            what = "How many milliseconds one ping waits for an answer before being counted as failed.",
            raise = "Slow-but-alive servers still count as reachable - fewer false alarms, but a truly dead connection takes longer to confirm.",
            lower = "Dead connections are confirmed faster, but a slow (yet working) connection starts counting as broken.",
            pro = "Sets how forgiving the app is on slow mobile networks.",
            con = "Wrong in either direction and the good/bad verdict itself becomes wrong.",
            bestMin = "800",
            bestMax = "2000",
            risk = "Below 400 - normal mobile-data slowness gets branded as failure and the app panics for nothing.",
            cheat = "1000 - sharp verdicts without punishing mobile data. (Trust the Detector here - it computes this from your real ping.)"
        ),
        "net.probe.alpha" to Guide(
            what = "The memory dial (0 to 1). Decides how much the newest ping counts versus history when the app averages your connection speed.",
            raise = "The average follows the newest ping quickly - snappy, but jumpy: one bad ping can swing the whole picture.",
            lower = "The average moves calmly - stable, but a real change takes several pings to show up.",
            pro = "Pick reaction speed vs stability without touching how often checks run.",
            con = "At the extremes the picture becomes either twitchy or sleepy - both mislead the other engines.",
            bestMin = "0.2 (smooth)",
            bestMax = "0.5 (reactive)",
            risk = "Above 0.8 - a single hiccup can flip the app's whole verdict on its own.",
            cheat = "0.4 - sees real changes fast, still ignores one-off blips."
        ),
        "net.probe.samples" to Guide(
            what = "How many pings are fired per health check. The app sorts them and uses the middle one, so one weird ping cannot lie to it.",
            raise = "Truer readings on a noisy connection, but each check takes longer and works the phone more.",
            lower = "Faster, lighter checks, but single weird pings have more influence on the verdict.",
            pro = "The honesty dial - more pings, harder to fool.",
            con = "Each extra ping stretches the check and adds traffic.",
            bestMin = "3",
            bestMax = "5",
            risk = "1 - every check trusts a single ping; one fluke flips the verdict. Above 9 - checks take so long they overlap and bloat.",
            cheat = "3 on a steady link, 5 on a noisy one - exactly what the Detector picks from your live wobble."
        ),
        "net.probe.gap_ms" to Guide(
            what = "The pause in milliseconds between the pings inside one health check.",
            raise = "Pings spread out - each samples a slightly different moment (truer picture), but the whole check takes longer.",
            lower = "The check finishes faster, but pings bunch together and can all hit the same brief hiccup.",
            pro = "Fine-tunes check quality vs check speed.",
            con = "Barely noticeable either way unless pushed to extremes.",
            bestMin = "40",
            bestMax = "100",
            risk = "0 - all pings fire at once and one micro-hiccup poisons the entire check.",
            cheat = "60 - the stock spacing is already the sweet spot."
        ),
        "net.probe.degraded_mult" to Guide(
            what = "Where the line sits between 'connection OK-ish' and 'connection bad'. Your ping above baseline times this number = DEGRADED; beyond it = BAD.",
            raise = "The app tolerates slower pings before declaring things bad - fewer alarms, later protection.",
            lower = "The app declares trouble sooner - earlier protection, but normal slow moments start triggering it.",
            pro = "You decide how picky the quality verdict is.",
            con = "Affects everything downstream, since many engines change behaviour when quality drops.",
            bestMin = "1.5 (picky)",
            bestMax = "2.5 (tolerant)",
            risk = "Below 1.2 - the app spends its life in 'bad' mode and burns battery on fast checks.",
            cheat = "1.8 - flags real trouble one beat earlier than stock."
        ),

        // ---------------- NetworkStateEngine ----------------
        "net.state.poll_ms" to Guide(
            what = "The app already gets told INSTANTLY by Android when you switch between WiFi and mobile data. This is only the backup sweep that double-checks, in case that instant signal is ever missed.",
            raise = "Backup checks less often - basically free, since the instant signal does the real work.",
            lower = "Backup checks more often - tiny extra battery for near-zero gain.",
            pro = "Safety net for a rare missed switch signal.",
            con = "Almost no visible effect either way - the instant signal covers 99% of cases.",
            bestMin = "5000",
            bestMax = "20000",
            risk = "Below 1000 - pointless busy-work every second for nothing.",
            cheat = "10000 - stock. Leave it; the instant detection is what matters and it is always on."
        ),

        // ---------------- DnsWarmupEngine ----------------
        "net.dns.rewarm_ms" to Guide(
            what = "How often the app re-looks-up the game servers' internet addresses so they are always ready. A cold address lookup at the wrong moment = a visible freeze at connect time.",
            raise = "Fewer background lookups - saves a little data, but an address can expire and your next connection starts cold and slow.",
            lower = "Addresses always hot - connections always start instantly, at the cost of small periodic lookups.",
            pro = "Removes the classic 'why did it pause right there' stall.",
            con = "Refreshing far more often than addresses actually expire is wasted traffic.",
            bestMin = "60000 (1 minute)",
            bestMax = "180000 (3 minutes)",
            risk = "Below 15000 - hammering the address system for zero extra benefit.",
            cheat = "75000 - always hot, never wasteful."
        ),

        // ---------------- RadioKeepAliveEngine ----------------
        "net.keepalive.floor_s" to Guide(
            what = "Your phone's modem dozes off to save power, and waking it costs precious milliseconds at the worst moment. When your connection is bad, the app sends tiny packets to keep the modem awake - this is the fastest rhythm (in seconds) it is allowed to use.",
            raise = "The modem is allowed to rest more - cooler phone, better battery, but your next action may pay the wake-up delay.",
            lower = "The modem is kept hot - your actions never wait for it, but battery drains faster and the phone runs warmer.",
            pro = "Kills the wake-up delay exactly when your connection is already struggling.",
            con = "The most battery-hungry dial in the whole Net Adapter.",
            bestMin = "3",
            bestMax = "8",
            risk = "Below 2 - modem pinned at full power non-stop: heavy drain, warm phone, and heat makes phones slow themselves down.",
            cheat = "4 - stock is the measured sweet spot between speed and drain. Only the Detector should talk you down to 3."
        ),

        // ---------------- PacketLossProbeEngine ----------------
        "net.loss.round_ms" to Guide(
            what = "How often the app runs a lost-packet check. Each check sends a small burst of packets and counts how many answers come back - lost packets are lost actions in game.",
            raise = "The loss number updates more slowly - less data used, but the app reacts late when packets suddenly start dying.",
            lower = "The loss picture refreshes faster - protection kicks in earlier, for a bit more background traffic.",
            pro = "The main dial for how live your loss reading is.",
            con = "Checks too close together add their own load to an already-struggling connection.",
            bestMin = "2500",
            bestMax = "8000",
            risk = "Below 1500 - the check bursts crowd your game's packets and INFLATE the loss they measure.",
            cheat = "3000 - near-live loss tracking at a modest cost."
        ),
        "net.loss.per_round" to Guide(
            what = "How many packets each check sends. This sets the reading's precision: 4 packets means each lost one counts as 25%.",
            raise = "Finer readings (8 packets = 12.5% steps) and steadier numbers, but more data per check.",
            lower = "Cheaper checks but chunky readings - with 2 packets, one lost packet reads as a scary 50%.",
            pro = "Better precision makes the GO/HOLD thresholds mean what they say.",
            con = "Big bursts can themselves clog a connection that is already full.",
            bestMin = "3",
            bestMax = "8",
            risk = "Above 16 - the burst itself stresses the connection and skews the reading.",
            cheat = "5 - clean 20% steps, still a feather-light burst."
        ),
        "net.loss.reply_timeout_ms" to Guide(
            what = "How long each test packet waits for its answer before being declared lost.",
            raise = "Late answers still count as arrived - the loss number reads lower, but real problems show up later.",
            lower = "Slow answers get counted as lost - protection triggers earlier, but plain slowness starts masquerading as loss.",
            pro = "Sets where 'slow' officially becomes 'gone'.",
            con = "Too tight and a slow-but-stable connection looks broken when it is not.",
            bestMin = "500",
            bestMax = "1200",
            risk = "Below 300 - ordinary mobile-data slowness gets booked as packet loss and the app over-protects.",
            cheat = "700 - stock is the sweet spot. The Detector refines it from your live ping."
        ),
        "net.loss.alpha" to Guide(
            what = "Memory dial for the loss average - how much the newest check counts versus history.",
            raise = "The loss number jumps quickly to match the latest check - fast alarms, jumpy number.",
            lower = "The loss number moves gently - stable, but a sudden burst of loss takes several checks to register.",
            pro = "Steadies the exact number the GO/HOLD verdict reads.",
            con = "Too smooth and the verdict reacts to history instead of now.",
            bestMin = "0.2",
            bestMax = "0.5",
            risk = "Above 0.7 - one bad check can slam the verdict to HOLD all by itself.",
            cheat = "0.35 - slightly faster than stock without flip-flopping."
        ),
        "net.loss.gap_ms" to Guide(
            what = "The pause between the packets inside one loss check.",
            raise = "Packets spread out and sample different moments - truer reading, slightly longer check.",
            lower = "The check finishes faster but packets bunch up and one micro-hiccup can eat several at once.",
            pro = "Keeps each check honest and light.",
            con = "Small effect either way unless pushed to extremes.",
            bestMin = "50",
            bestMax = "150",
            risk = "0 - the whole burst fires at once; one blink of congestion reads as massive loss.",
            cheat = "80 - stock spacing is already right."
        ),

        // ---------------- CongestionSentinelEngine ----------------
        "net.sentinel.poll_ms" to Guide(
            what = "How often the watchman re-reads the wobble numbers to check whether congestion is building.",
            raise = "Congestion onset is caught later - slightly cheaper.",
            lower = "Earlier warnings - slightly more work per second.",
            pro = "Very cheap check - it only reads numbers other engines already produced.",
            con = "Checking faster than new numbers arrive adds nothing (it re-reads the same values).",
            bestMin = "1000",
            bestMax = "4000",
            risk = "Below 500 - wasted effort re-reading unchanged numbers.",
            cheat = "1500 - sits just under the ping-check rhythm so no rise is ever missed."
        ),
        "net.sentinel.rise_factor" to Guide(
            what = "How big a jump in wobble sounds the alarm. 1.5 means wobble must jump 50% above its last reading.",
            raise = "Fewer alarms - only serious congestion trips it, and real trouble gets caught later.",
            lower = "Earlier, more sensitive alarms - but ordinary wobble starts triggering false ones.",
            pro = "The main sensitivity dial for early-warning.",
            con = "Works as a pair with the setting below - move both thoughtfully.",
            bestMin = "1.3",
            bestMax = "2.0",
            risk = "Below 1.1 - nearly every natural flutter reads as congestion; the app lives in alarm mode.",
            cheat = "1.4 - hears trouble one beat earlier than stock without crying wolf."
        ),
        "net.sentinel.rise_fraction" to Guide(
            what = "The second gate: the wobble jump must ALSO be above this share of your allowance (0.6 = 60%) before the alarm sounds. Stops big-percentage-but-tiny jumps from causing panic.",
            raise = "Stricter gate - small jumps are ignored even when the first gate trips; fewer alarms overall.",
            lower = "Looser gate - smaller jumps pass through; the first dial dominates.",
            pro = "Filters the classic false alarm: a 'huge rise' that is actually 2ms to 4ms.",
            con = "Too strict and slowly-creeping congestion never crosses the line.",
            bestMin = "0.4",
            bestMax = "0.8",
            risk = "Below 0.2 - the second gate stops filtering anything.",
            cheat = "0.5 - balanced partner to a 1.4 rise factor."
        ),

        // ---------------- SpikeBurstEngine ----------------
        "net.spike.recovery_window_ms" to Guide(
            what = "After a lag spike is detected, how long (ms) the app watches the connection for signs of recovery, checking every second.",
            raise = "Longer watch - recoveries are announced with more certainty, but the app stays in cautious mode after the link is actually fine.",
            lower = "Faster return to full-speed play, on thinner evidence.",
            pro = "Controls how paranoid the app is after a spike.",
            con = "Too long and you sit in careful-mode well after the storm passed.",
            bestMin = "30000",
            bestMax = "120000",
            risk = "Below 10000 - all-clear declared off one lucky second, straight into the next spike.",
            cheat = "45000 - quicker bounce-back while the evidence still means something."
        ),
        "net.spike.clean_samples" to Guide(
            what = "How many clean pings IN A ROW the app needs before declaring the spike over.",
            raise = "Stronger proof - near-zero false all-clears, but the all-clear comes later.",
            lower = "Faster all-clear - with a higher chance of bouncing right back into spike mode.",
            pro = "Simple, powerful protection against flip-flopping.",
            con = "Each extra proof costs about one second of patience.",
            bestMin = "2",
            bestMax = "4",
            risk = "0 or 1 - the all-clear flaps on single lucky pings.",
            cheat = "3 - one extra proof kills the flip-flop for ~1s of patience."
        ),
        "net.spike.burst_samples" to Guide(
            what = "When a spike hits, how many rapid pings are fired to measure how deep it is.",
            raise = "A more detailed map of the spike, at the cost of more pings during an already-bad moment.",
            lower = "Lighter touch during trouble, but a rougher picture of what happened.",
            pro = "Better maps mean smarter recovery decisions.",
            con = "Firing many pings INTO a congested link adds to the congestion.",
            bestMin = "3",
            bestMax = "7",
            risk = "Above 10 - you are shelling a struggling connection with test pings.",
            cheat = "5 - stock maps the spike well without piling on."
        ),
        "net.spike.burst_gap_ms" to Guide(
            what = "The pause between those spike-mapping pings.",
            raise = "The map covers a longer slice of the spike - truer shape, slower to produce.",
            lower = "The map appears faster but only captures one instant of the spike.",
            pro = "Cheap way to make spike maps honest.",
            con = "Little visible effect outside extremes.",
            bestMin = "100",
            bestMax = "400",
            risk = "0 - all pings hit the same instant; the map is meaningless.",
            cheat = "200 - stock. Right already."
        ),
        "net.spike.clean_mult" to Guide(
            what = "What counts as a 'clean' ping during recovery watch: ping under your baseline times this number.",
            raise = "Easier to qualify as clean - faster all-clears, but 'recovered' may still feel laggy.",
            lower = "Stricter cleanliness - the all-clear truly means clean, but recovery on a naturally slow link may never qualify.",
            pro = "You define what 'back to normal' means.",
            con = "Interacts with your baseline - if you override the baseline, this line moves with it.",
            bestMin = "1.2",
            bestMax = "2.0",
            risk = "Below 1.0 - demands better-than-baseline pings to declare recovery; may never happen.",
            cheat = "1.5 - stock. Matches the carrier profile maths."
        ),

        // ---------------- ActionWindowEngine ----------------
        "net.window.poll_ms" to Guide(
            what = "How often the final GO / CAUTION / HOLD verdict is recomputed from the latest numbers. This verdict is what the rest of the assistant obeys.",
            raise = "The verdict lags behind reality - you can get a green light on a link that just went bad.",
            lower = "The verdict tracks reality near-live, for a tiny cost.",
            pro = "Keeps the traffic light honest.",
            con = "Faster than the measurements refresh just recomputes the same inputs.",
            bestMin = "1000",
            bestMax = "3000",
            risk = "Above 5000 - decisions fire on a verdict from several seconds ago.",
            cheat = "1500 - verdict refreshes right behind every new measurement."
        ),
        "net.window.hold_loss_pct" to Guide(
            what = "When lost packets climb above this percentage, the verdict slams to HOLD - the hard safety ceiling.",
            raise = "More tolerant - play continues on worse connections; more chances, more actions dying in transit.",
            lower = "More protective - HOLD comes sooner; safer actions, fewer play windows.",
            pro = "Direct control of your safety ceiling.",
            con = "Must stay well above the GO bar below, or the verdict gets squeezed and flip-flops.",
            bestMin = "5 (careful)",
            bestMax = "15 (daring)",
            risk = "Above 25 - HOLD basically never triggers; you fire actions into a dying connection.",
            cheat = "8 - holds one notch earlier than stock and saves actions that would have died."
        ),
        "net.window.go_loss_pct" to Guide(
            what = "GO (full green light) requires lost packets BELOW this percentage. The cleanliness bar for full-speed play.",
            raise = "GO appears on dirtier connections - more green lights, less trust in each one.",
            lower = "GO only on a truly clean connection - every green light is real, but there are fewer of them.",
            pro = "You define what 'clean' means.",
            con = "Near zero and a mobile connection may NEVER qualify - permanent yellow light.",
            bestMin = "1",
            bestMax = "4",
            risk = "Above 10 - green lights on genuinely lossy links, which defeats the whole system.",
            cheat = "2 - stock is already the prime pick. Detector may relax to 3 if your link never reads clean."
        ),
        "net.window.hold_jitter_mult" to Guide(
            what = "The wobble arm of the safety gate: HOLD triggers when wobble exceeds your allowance times this number.",
            raise = "Bigger wobble storms are tolerated before holding - fewer pauses, riskier timing on actions.",
            lower = "Holds at milder wobble - beautifully timed actions only, but more frequent pauses.",
            pro = "Protects timing-sensitive actions separately from loss.",
            con = "Too low and normal wireless flutter keeps freezing the verdict.",
            bestMin = "1.5",
            bestMax = "3",
            risk = "Above 5 - wobble effectively stops protecting anything.",
            cheat = "2 - stock sits at the natural knee already."
        ),

        // ---------------- CarrierProfileEngine ----------------
        "net.profile.rtt_ms" to Guide(
            what = "The ping pass-line your connection is judged against. 0 = automatic (the app picks a profile for your carrier - MTN, AIRTEL, WiFi...). Any other number = your own pass-line, used by every engine.",
            raise = "(from auto) A higher pass-line means slower pings still rate as GOOD - fewer alarms, later protection.",
            lower = "A lower pass-line demands faster pings for a GOOD rating - earlier protection, but a normal link may never rate GOOD.",
            pro = "One number that re-tunes the whole stack to YOUR reality instead of a generic carrier guess.",
            con = "Set badly, every engine inherits the mistake - this is the most powerful (and sharpest) knob here.",
            bestMin = "0 (auto)",
            bestMax = "about 1.25x your real ping (see Detector)",
            risk = "Below your actual typical ping - the app rates your link BAD forever and burns battery fighting a ghost.",
            cheat = "Tap the Detector - it measures your real ping and gives the exact number. That beats any fixed suggestion."
        ),
        "net.profile.jitter_tol_ms" to Guide(
            what = "Your wobble allowance - how much ping variation counts as normal before engines start reacting. 0 = automatic by carrier.",
            raise = "More wobble is tolerated - fewer alarms and holds, but real instability is ignored longer.",
            lower = "Less wobble is tolerated - earlier warnings and holds, but normal flutter starts triggering them.",
            pro = "Directly sets the sensitivity of the watchman, the traffic light and the spike system in one place.",
            con = "Same sharpness as the pass-line: a bad value here spreads everywhere.",
            bestMin = "0 (auto)",
            bestMax = "about 2x your real wobble (see Detector)",
            risk = "Below your natural wobble - permanent alarm mode: constant HOLDs on a perfectly playable link.",
            cheat = "Use the Detector's number - it is computed from your live measured wobble."
        ),
        "net.profile.keepalive_s" to Guide(
            what = "The base rhythm (seconds) for the tiny keep-the-modem-awake packets. 0 = automatic by carrier. The app automatically doubles the pace when your link goes bad.",
            raise = "(from auto) Slower rhythm - better battery, higher chance your action pays the modem wake-up delay.",
            lower = "Faster rhythm - modem always ready, battery pays for it.",
            pro = "Simple trade: readiness vs battery.",
            con = "Battery cost is constant while playing - it adds up over a session.",
            bestMin = "0 (auto)",
            bestMax = "15",
            risk = "1 to 2 - near-constant radio wake-ups: drain and heat for almost no extra readiness.",
            cheat = "0 (auto) - the carrier profile plus the auto-doubling on bad links already does the smart thing."
        ),

        // ---------------- FramePacingEngine ----------------
        "lag.frame.alpha" to Guide(
            what = "Memory dial for the frame-smoothness average - how fast the picture follows the newest frames.",
            raise = "Quick to flag a developing stutter, but jumpy on single slow frames.",
            lower = "Very steady trend, but a real slowdown takes longer to show.",
            pro = "Reaction-speed dial for everything that reads frame health.",
            con = "Too high and one heavy frame reads like a trend.",
            bestMin = "0.1",
            bestMax = "0.35",
            risk = "Above 0.6 - every isolated slow frame yanks the average around.",
            cheat = "0.25 - sees real slowdowns a beat sooner than stock, still calm."
        ),
        "lag.frame.report_ms" to Guide(
            what = "How often the frame-smoothness engine hands its summary to the rest of the app.",
            raise = "Smoother, longer-term verdicts - reactions to a fresh lag episode come later.",
            lower = "Fresher verdicts more often, slightly more bookkeeping.",
            pro = "Sets the granularity of the 'how smooth are we' story.",
            con = "Very short windows make verdicts noisy.",
            bestMin = "10000",
            bestMax = "30000",
            risk = "Below 2000 - report churn adds overhead and noise with no insight.",
            cheat = "15000 - catches a lag episode one report earlier than stock."
        ),
        "lag.frame.stall_ms" to Guide(
            what = "A frame slower than this many milliseconds counts as a freeze - the line between 'slow frame' and 'stutter'.",
            raise = "Only big freezes register - micro-stutter slips under the radar.",
            lower = "More frames get booked as freezes - earlier countermeasures, possibly over-reacting to ordinary heavy frames.",
            pro = "Defines the exact pain threshold the helper responds to.",
            con = "Set near the screen's natural frame time, it flags normal rendering.",
            bestMin = "80",
            bestMax = "150",
            risk = "Below 33 - every tiny hiccup is a 'freeze'; the helper never stops intervening.",
            cheat = "90 - catches real stutter runs while ignoring single slow frames."
        ),

        // ---------------- LoadShedGovernor ----------------
        "lag.shed.min_hold_ms" to Guide(
            what = "Once the app starts helping (shedding load to fight lag), this is the minimum time it keeps helping before it may stop - the anti-flip-flop timer.",
            raise = "Steadier helping periods, but full quality returns sluggishly after brief load spikes.",
            lower = "Snappier return to full quality, with growing risk of rapid on-off cycling.",
            pro = "One dial deciding between stability and responsiveness of the whole helping system.",
            con = "A wrong value is invisible until load gets spiky - then it is very visible.",
            bestMin = "5000",
            bestMax = "15000",
            risk = "Below 2000 - help flips on and off so fast the flip-flopping feels worse than the lag it fights.",
            cheat = "6000 - quicker quality recovery while staying clear of the flip-flop zone."
        )
    )
}
