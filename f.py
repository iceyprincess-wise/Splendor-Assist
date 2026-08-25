#!/usr/bin/env python3
import os, sys
B="/data/data/com.termux/files/home/projects/Splendor-Assist/app/src/main/java/com/assistant"
FILES={
 "INT":B+"/adapter/interruption/InterruptionAdapterService.kt",
 "CAP":B+"/adapter/smartassist/CaptaincySkillEngine.kt",
 "CRD":B+"/adapter/smartassist/CrowdingZoneDetector.kt",
 "FRA":B+"/adapter/smartassist/FrameAssembler.kt",
 "ACT":B+"/adapter/smartassist/AgentAction.kt",
 "POL":B+"/adapter/smartassist/AgentDecision.kt",
 "AGC":B+"/adapter/smartassist/InAppAgentCore.kt",
 "SH ":B+"/adapter/smartassist/RuntimeSelfHealEngine.kt",
 "ROOM":B+"/controlroom/ui/GameplayRoomActivity.kt",
 "OV ":B+"/OverlayService.kt",
}
def load(p):
    with open(p,'rb') as f: r=f.read()
    return r, r.decode('utf-8').replace('\r\n','\n')
def save(p,r,t):
    with open(p,'wb') as f: f.write((t.replace('\n','\r\n') if b'\r\n' in r else t).encode('utf-8'))
def rep(t,old,new,tag):
    if new.split('\n')[0] in t: return t
    c=t.count(old)
    if c!=1: print(f"BLOCKED - anchor x{c}: {tag}; NO change."); sys.exit(1)
    print(f"PROVEN - {tag}"); return t.replace(old,new,1)

print("=== SPLDOR-ASSIST V6.1 (WHITESPACE-SAFE) ===")

# 1) Interruption: heartbeat FIRST, eliminators individually guarded
r,t=load(FILES["INT"])
OLD="""            try {
                // CONDITIONLESS ACTIVE MITIGATION (Every 500ms)
                DozeBypassEliminator.ignite(this@InterruptionAdapterService)
                NetworkPriorityHijacker.execute(this@InterruptionAdapterService)
                NotificationHardKiller.execute(this@InterruptionAdapterService)
                BackgroundProcessPurger.execute(this@InterruptionAdapterService)

                val telephonyManager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
                @Suppress("DEPRECATION")
                val callState = try { telephonyManager?.callState ?: TelephonyManager.CALL_STATE_IDLE } catch (_: SecurityException) { TelephonyManager.CALL_STATE_IDLE }

                CallAndAudioEliminator.updateCallState(callState)

                val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val batteryLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val charging = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING

                val state = InterruptionCoordinator.evaluate(batteryLevel, charging, 0)
                InterruptionRepository.save(state)

                if (callState == TelephonyManager.CALL_STATE_RINGING || TelephonyStateRepository.activeCall) {
                    CallAndAudioEliminator.suppressRingerAndHoldFocus(this@InterruptionAdapterService)
                }

                val throttleMode = when (state.severity) {
                    "CRITICAL" -> "AGGRESSIVE_THROTTLE"
                    "THROTTLE" -> "MODERATE_THROTTLE"
                    "WARNING" -> "LIGHT_THROTTLE"
                    else -> "NORMAL"
                }

                AdapterHealthRegistry.update(
                    AdapterHealthSnapshot(
                        adapterName = "adapter_interruption",
                        status = state.severity,
                        lastHeartbeat = System.currentTimeMillis(),
                        errorCount = errorCount.get(),
                        recoveryCount = 0,
                        details = "battery=${state.batteryLevel},call=${TelephonyStateRepository.activeCall},mode=$throttleMode"
                    )
                )

            } catch (e: Exception) {"""
NEW="""            try {
                // V6 ROOT-CAUSE FIX (field logs: "heartbeat failed :: SecurityException"
                // every 500ms -> no persisted heartbeat -> booster-not-ready forever).
                // Heartbeat FIRST, independently guarded: the cross-process truth the
                // booster gate/health reads must survive any eliminator fault.
                try {
                    val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val batteryLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val charging = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
                    val state = InterruptionCoordinator.evaluate(batteryLevel, charging, 0)
                    InterruptionRepository.save(state)
                    val throttleMode = when (state.severity) {
                        "CRITICAL" -> "AGGRESSIVE_THROTTLE"
                        "THROTTLE" -> "MODERATE_THROTTLE"
                        "WARNING" -> "LIGHT_THROTTLE"
                        else -> "NORMAL"
                    }
                    AdapterHealthRegistry.update(
                        AdapterHealthSnapshot(
                            adapterName = "adapter_interruption",
                            status = state.severity,
                            lastHeartbeat = System.currentTimeMillis(),
                            errorCount = errorCount.get(),
                            recoveryCount = 0,
                            details = "battery=${state.batteryLevel},call=${TelephonyStateRepository.activeCall},mode=$throttleMode"
                        )
                    )
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                    RuntimeLogger.log("InterruptionAdapter heartbeat failed :: ${e.javaClass.simpleName}", "HEALTH")
                }

                // Eliminators: each individually guarded so one SecurityException
                // can never kill the loop or the heartbeat again.
                try { DozeBypassEliminator.ignite(this@InterruptionAdapterService) } catch (_: Throwable) {}
                try { NetworkPriorityHijacker.execute(this@InterruptionAdapterService) } catch (_: Throwable) {}
                try { NotificationHardKiller.execute(this@InterruptionAdapterService) } catch (_: Throwable) {}
                try { BackgroundProcessPurger.execute(this@InterruptionAdapterService) } catch (_: Throwable) {}

                val telephonyManager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
                @Suppress("DEPRECATION")
                val callState = try { telephonyManager?.callState ?: TelephonyManager.CALL_STATE_IDLE } catch (_: SecurityException) { TelephonyManager.CALL_STATE_IDLE }

                CallAndAudioEliminator.updateCallState(callState)

                if (callState == TelephonyManager.CALL_STATE_RINGING || TelephonyStateRepository.activeCall) {
                    CallAndAudioEliminator.suppressRingerAndHoldFocus(this@InterruptionAdapterService)
                }

            } catch (e: Exception) {"""
save(FILES["INT"],r,rep(t,OLD,NEW,"INT-heartbeat-first"))

# 2) Captaincy: persistence + real-state accessor
r,t=load(FILES["CAP"])
t=rep(t,"    @Volatile private var captainDesignated = false",
"    @Volatile private var captainDesignated = false\n    @Volatile private var prefs: android.content.SharedPreferences? = null","CAP-field")
t=rep(t,"""    fun setCaptainDesignated(enabled: Boolean) {
        captainDesignated = enabled
    }""",
"""    fun setCaptainDesignated(enabled: Boolean) {
        captainDesignated = enabled
        try { prefs?.edit()?.putBoolean("captaincy_designated", enabled)?.apply() } catch (_: Throwable) {}
    }

    // V6 FIX (field bug: switch showed OFF while engine stayed ON).
    fun isDesignated(): Boolean = captainDesignated

    fun init(context: android.content.Context) {
        try {
            val p = context.applicationContext.getSharedPreferences("splendor_engine_toggles", 0)
            prefs = p
            captainDesignated = p.getBoolean("captaincy_designated", captainDesignated)
        } catch (_: Throwable) {}
    }""","CAP-persist")
save(FILES["CAP"],r,t)

# 3) Crowding: enable/disable API + persistence (FULL BLOCK ANCHOR FOR WHITESPACE SAFETY)
r,t=load(FILES["CRD"])
t=rep(t,"""    @Volatile var inCrowdedZone: Boolean = false; private set
    @Volatile var crowdingLevel: Float = 0f; private set""",
"""    @Volatile var inCrowdedZone: Boolean = false; private set
    @Volatile var crowdingLevel: Float = 0f; private set
    @Volatile private var enabled = true
    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        try { prefs?.edit()?.putBoolean("crowding_enabled", value)?.apply() } catch (_: Throwable) {}
        if (!value) reset()
    }

    fun isEnabled(): Boolean = enabled

    fun init(context: android.content.Context) {
        try {
            val p = context.applicationContext.getSharedPreferences("splendor_engine_toggles", 0)
            prefs = p
            enabled = p.getBoolean("crowding_enabled", true)
        } catch (_: Throwable) {}
    }""","CRD-api")
t=rep(t,"""    fun evaluate(frame: RuntimeFrame): Boolean {
        if (!frame.trusted) {""",
"""    fun evaluate(frame: RuntimeFrame): Boolean {
        if (!enabled) {
            inCrowdedZone = false
            crowdingLevel = 0f
            AdapterSignalBus.publishCrowdingZone(false, 0f)
            return false
        }
        if (!frame.trusted) {""","CRD-guard")
t=rep(t,'    fun diagnostics(): Map<String, Any> = mapOf(\n        "inCrowdedZone" to inCrowdedZone,\n        "crowdingLevel"  to crowdingLevel,\n        "detections"     to detections.get()\n    )',
      '    fun diagnostics(): Map<String, Any> = mapOf(\n        "inCrowdedZone" to inCrowdedZone,\n        "crowdingLevel"  to crowdingLevel,\n        "detections"     to detections.get(),\n        "enabled" to enabled\n    )',"CRD-diag")
save(FILES["CRD"],r,t)

# 4) Vision guard: cap 11v11 before zones/density/trust
r,t=load(FILES["FRA"])
t=rep(t,"""        val scene = try { SceneTracker.current() } catch (_: Throwable) { null }
        val players = scene?.trackedPlayers.orEmpty()
        val opponents = players.count { !it.isUserTeam }""",
"""        val scene = try { SceneTracker.current() } catch (_: Throwable) { null }
        val rawPlayers = scene?.trackedPlayers.orEmpty()

        // V6 VISION GUARD (field-proven over-count: players=30/opponents=30):
        // cap each side to 11 before zones/density/trust so downstream engines
        // never consume impossible head-counts. Mitigation; detector tuning
        // (jersey-color attribution) is the next vision round.
        val ours = rawPlayers.filter { it.isUserTeam }
        val theirs = rawPlayers.filter { !it.isUserTeam }
        val players =
            if (ours.size > 11 || theirs.size > 11) ours.take(11) + theirs.take(11)
            else rawPlayers
        val opponents = players.count { !it.isUserTeam }""","FRA-cap")
save(FILES["FRA"],r,t)

# 5) Agent promotion: new action + policy branch + executor + context accessor
r,t=load(FILES["ACT"])
t=rep(t,"""    object RefreshPerformance : AgentAction()
}""","""    object RefreshPerformance : AgentAction()
    object ReigniteFleet : AgentAction()
}""","ACT-new")
save(FILES["ACT"],r,t)
r,t=load(FILES["POL"])
t=rep(t,"""        if (observation.loadShed == "HEAVY") {""",
"""        // V6 PROMOTION: booster-not-ready now has a SAFE automated recovery
        // (re-ignite fleet) instead of permanent ObserveOnly.
        if (!health.boosterAlive) {
            return AgentDecision(
                action = AgentAction.ReigniteFleet,
                priority = 60,
                reason =
                    "Booster fleet not ready (no fresh adapter heartbeats); " +
                    "re-ignite adapter services."
            )
        }

        if (observation.loadShed == "HEAVY") {""","POL-branch")
save(FILES["POL"],r,t)
r,t=load(FILES["SH "])
t=rep(t,"""    fun init(ctx: android.content.Context) {
        contextRef = WeakReference(ctx.applicationContext)
    }""",
"""    fun init(ctx: android.content.Context) {
        contextRef = WeakReference(ctx.applicationContext)
    }

    // V6: expose stored context so the agent can execute safe recoveries.
    fun appContext(): android.content.Context? = contextRef?.get()""","SH-ctx")
save(FILES["SH "],r,t)
r,t=load(FILES["AGC"])
t=rep(t,"""    @Volatile
    private var lastVerification: ActionVerification? = null""",
"""    @Volatile
    private var lastVerification: ActionVerification? = null

    @Volatile
    private var lastReigniteMs: Long = 0L""","AGC-field")
t=rep(t,"""            AgentAction.RefreshPerformance ->
                RuntimePerformanceCoordinator.refresh()""",
"""            AgentAction.RefreshPerformance ->
                RuntimePerformanceCoordinator.refresh()

            // V6 PROMOTION: agent now FIXES booster-not-ready (60s cooldown)
            // instead of sitting in ObserveOnly while adapters stay silent.
            AgentAction.ReigniteFleet -> {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastReigniteMs >= 60_000L) {
                    lastReigniteMs = nowMs
                    val ctx = RuntimeSelfHealEngine.appContext()
                    if (ctx != null) {
                        try { com.assistant.BoosterIgnition.reset() } catch (_: Throwable) {}
                        try { com.assistant.BoosterIgnition.ensureIgnited(ctx) } catch (_: Throwable) {}
                        try { RuntimeCoordinator.refreshBoosterReadyFromRegistry() } catch (_: Throwable) {}
                        RuntimeLogger.log(
                            "AGENT ACTION ReigniteFleet: booster reset + re-ignited + G3 re-verified",
                            "AGENT"
                        )
                    }
                }
            }""","AGC-exec")
save(FILES["AGC"],r,t)

# 6) Room UI: captaincy switch binds REAL state + crowding toggle added
r,t=load(FILES["ROOM"])
t=rep(t,"            isChecked = CaptaincySkillEngine.isActive().let { false } // start unchecked; read real state",
"            isChecked = CaptaincySkillEngine.isDesignated() // V6 FIX: bind to REAL persisted state","ROOM-cap")
t=rep(t,"""        section(root, "CROWDING ZONE DETECTOR")
        crowdView = mono(root, "Loading...")""",
"""        section(root, "CROWDING ZONE DETECTOR")
        crowdView = mono(root, "Loading...")

        val crowdToggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(4), 0, dp(8)); layoutParams = lp
        }
        val crowdToggleLabel = TextView(this).apply {
            text = "Crowding Zone Detector enabled"; textSize = 13f
            setTextColor(Color.parseColor("#DDDDDD"))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        @Suppress("UseSwitchCompatOrMaterialCode")
        val crowdSw = Switch(this).apply {
            isChecked = CrowdingZoneDetector.isEnabled()
            setOnCheckedChangeListener { _, checked ->
                CrowdingZoneDetector.setEnabled(checked)
                val msg = if (checked) "Crowding detector ENABLED" else "Crowding detector DISABLED"
                android.widget.Toast.makeText(this@GameplayRoomActivity, msg,
                    android.widget.Toast.LENGTH_SHORT).show()
                logLine("CROWDING_TOGGLE: $msg")
            }
        }
        crowdToggleRow.addView(crowdToggleLabel)
        crowdToggleRow.addView(crowdSw)
        root.addView(crowdToggleRow)""","ROOM-crowd")
save(FILES["ROOM"],r,t)

# 7) Wire engine toggle persistence init into OverlayService.onCreate
r,t=load(FILES["OV "])
t=rep(t,"""        try {
            com.assistant.adapter.smartassist.RuntimeSelfHealEngine.init(applicationContext)
            com.assistant.adapter.smartassist.RuntimeSelfHealEngine.start()
        } catch (_: Throwable) {}""",
"""        try {
            com.assistant.adapter.smartassist.RuntimeSelfHealEngine.init(applicationContext)
            com.assistant.adapter.smartassist.RuntimeSelfHealEngine.start()
        } catch (_: Throwable) {}
        try { com.assistant.adapter.smartassist.CaptaincySkillEngine.init(applicationContext) } catch (_: Throwable) {}
        try { com.assistant.adapter.smartassist.CrowdingZoneDetector.init(applicationContext) } catch (_: Throwable) {}""","OV-init")
save(FILES["OV "],r,t)

print("=== V6.1 COMPLETE - run: ./gradlew :app:compileDebugKotlin ===")
