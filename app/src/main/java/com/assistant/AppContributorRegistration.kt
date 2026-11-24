package com.assistant

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.GameplayEngineRegistry
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class RegistrationState { IDLE, REGISTERING, READY, PARTIAL, FAILED }

object AppContributorRegistration {

    // N1 (f58): 43 after the f57 deletion in no68 —
    // keep this count and EXPECTED_CONTRIBUTOR_NAMES in sync with the
    // listOf below; a stale count throws in check() and leaves the
    // registry EMPTY (proven by Ed's 2026-11-18 logs: 43 != 44, Gens 1-13).
    const val EXPECTED_CONTRIBUTOR_COUNT = 59

    // HIGH: Completeness invariant - explicit set of expected names
    private val EXPECTED_CONTRIBUTOR_NAMES = setOf(
        "ThreatPriority", "CrossClaim", "KeeperBias", "PanicSave", "BallPress", "PressEvade", "Shot", "CrossDelivery",
        "MagneticFeet", "Passing", "Support", "Defense", "Evade", "AttackingVector", "Cross", "Agility", "WingBlock",
        "DashPressure", "InterceptMatrix", "TouchRecovery", "OverloadPlaystyle", "TruePass", "ReceiverEngagement", "ForwardRun",
        "ShotOpportunity", "DefenseAuthority", "ShotAnticipation", "KeeperFeedback", "DashAnchor", "SpeedCompensation",
        "InstantIntercept", "BuildUpPress", "BallRetentionShield", "TrueShot", "TrueCross", "SAUltimateCorrector",
        "SharpTouchTiming", "KickingPosture",
        "DREADNOUGHT_BUMPER", "FORMATIONS_ANCHOR", "MAGNETIC_FEET", "LASER_PASS_DIRECTOR", "APEX_FINISHER",
        // Batch 4 (§4 G-A/G-D/G-E/G-F/G-I/G-J/G-L/G-M + §7 G-N):
        // SetPiece, Restart, Dribble, Marking, Clearance,
        // KeeperDistribution, CombinationPlay, PhysicalBarge, BeastTackle.
        "SetPiece", "Restart", "Dribble", "Marking", "Clearance",
        "KeeperDistribution", "CombinationPlay", "PhysicalBarge", "BeastTackle",
        // Batch 6b: LegGapBlocker (leg-gap stance), BodyWeightPress
        // (sustained lean/shoulder). Count 52 -> 54 with them.
        "LegGapBlocker", "BodyWeightPress",
        // Batch decoy-run (queue item 3): DecoyRun (false run with the
        // offside engine). Count 54 -> 55 with it.
        "DecoyRun",
        // Batch midfield (queue item 5): MidfieldHold (hold the
        // controlled player on the midfield line when the midfield is
        // broken). Count 55 -> 56 with it.
        "MidfieldHold",
        // Queue item 11 (anticipation): Anticipation (pre-move to the
        // predicted pass destination + cutback cover). Count 56 -> 57
        // with it.
        "Anticipation",
        // Phase 5 (cursor switch): PlayerSwitch (press the switch
        // flick toward the best defender when the cursor is wrong).
        // Count 57 -> 58 with it.
        "PlayerSwitch",
        // AutoEngage (aggressive auto-intercept + silent cursor
        // switch for outfield players). Count 58 -> 59 with it.
        "AutoEngage"
    )

    private val state = AtomicReference(RegistrationState.IDLE)
    @Volatile private var lastRetryMs = 0L
    private val generation = AtomicLong(0L)
    
    // Thread-safe lists for runtime proof telemetry
    private val initFailures = CopyOnWriteArrayList<String>()
    private val warmFailures = CopyOnWriteArrayList<String>()
    private val omittedContributors = CopyOnWriteArrayList<String>()
    
    // MEDIUM: Store actual Future for hard cancellation
    @Volatile private var currentFuture: Future<*>? = null

    private val warmupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Splendor-ContributorWarmup").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    fun ensureRegistered() {
        val currentState = state.get()
        // HIGH: Recovery semantics - allow retry from PARTIAL or FAILED
        if (currentState == RegistrationState.READY) {
            // SPLENDOR_V18_READY_DRIFT_BEGIN
            // GER-D5: use the registry's own health check instead of
            // duplicating its logic inline (isExpectedRegistered had no callers).
            if (GameplayEngineRegistry.isExpectedRegistered(EXPECTED_CONTRIBUTOR_COUNT)) return
            state.set(RegistrationState.IDLE)
            // SPLENDOR_V18_READY_DRIFT_END
        }
        if (currentState == RegistrationState.REGISTERING) return

        synchronized(this) {
            val current = state.get()
            if (current == RegistrationState.READY || current == RegistrationState.REGISTERING) return
            // ROOT-CAUSE FIX (field logs 2026-08-25): PARTIAL state re-triggered
            // resetAll()+warmAll() on EVERY capture frame (sessionEpoch storm,
            // engines wiped ~1/s, contributions starved). Bound retries to 30s.
            val retryNowMs = System.currentTimeMillis()
            if ((current == RegistrationState.PARTIAL || current == RegistrationState.FAILED) &&
                retryNowMs - lastRetryMs < 30_000L) return
            lastRetryMs = retryNowMs
            
            state.set(RegistrationState.REGISTERING)
            val myGeneration = generation.incrementAndGet()
            initFailures.clear()
            warmFailures.clear()
            omittedContributors.clear()
            
            // MEDIUM: Restart behavior - if retrying from PARTIAL/FAILED, clear registry for clean slate
            if (current == RegistrationState.PARTIAL || current == RegistrationState.FAILED) {
                GameplayEngineRegistry.resetAll()
            }
            
            currentFuture = warmupExecutor.submit {
                if (generation.get() != myGeneration) return@submit
                
                try {
                    // Batch 4 (§4 + §7 new gameplay contributors).
                val allContributors = listOf<GameplayContributor>(
                        com.assistant.contributors.ThreatPriorityContributor,
                        com.assistant.contributors.CrossClaimContributor,
                        com.assistant.contributors.KeeperBiasContributor,
                        com.assistant.contributors.PanicSaveContributor,
                        com.assistant.contributors.BallPressContributor,
                        com.assistant.contributors.PressEvadeContributor,
                        com.assistant.contributors.ShotContributor,
                        com.assistant.contributors.CrossDeliveryContributor,
                        
                        com.assistant.MagneticFeetContributor,
                        com.assistant.PassingContributor,
                        com.assistant.SupportContributor,
                        com.assistant.DefenseContributor,
                        com.assistant.EvadeContributor,
                        com.assistant.AttackingVectorContributor,
                        com.assistant.CrossContributor,
                        com.assistant.AgilityContributor,
                        com.assistant.WingBlockContributor,
                        com.assistant.DashPressureContributor,
                        com.assistant.InterceptMatrixContributor,
                        com.assistant.TouchRecoveryContributor,
                        com.assistant.OverloadPlaystyleContributor,
                        com.assistant.TruePassContributor,
                        com.assistant.ReceiverEngagementContributor,
                        com.assistant.ForwardRunContributor,
                        com.assistant.ShotOpportunityContributor,
                        com.assistant.DefenseAuthorityContributor,
                        com.assistant.ShotAnticipationContributor,
                        com.assistant.KeeperFeedbackContributor,
                        com.assistant.DashAnchorContributor,
                        com.assistant.SpeedCompensationContributor,
                        com.assistant.InstantInterceptContributor,
                        com.assistant.BuildUpPressContributor,
                        com.assistant.BallRetentionShieldContributor,
                        com.assistant.TrueShotContributor,
                        com.assistant.TrueCrossContributor,
                        com.assistant.SmartAssistUltimateCorrectorContributor,
                        com.assistant.SharpTouchTimingContributor,
                        com.assistant.KickingPostureContributor,
                        com.assistant.gameplay.DreadnoughtBumperEngine(),
                        com.assistant.gameplay.FormationsAnchorEngine(),
                        com.assistant.gameplay.AbsoluteMagneticFeetEngine(),
                        com.assistant.gameplay.LaserPassDirectorEngine(),
                        com.assistant.gameplay.ApexFinisherEngine(),
                        com.assistant.contributors.SetPieceContributor,
                        com.assistant.contributors.RestartContributor,
                        com.assistant.contributors.DribbleContributor,
                        com.assistant.contributors.MarkingContributor,
                        com.assistant.contributors.ClearanceContributor,
                        com.assistant.contributors.KeeperDistributionContributor,
                        com.assistant.contributors.CombinationPlayContributor,
                        com.assistant.contributors.PhysicalBargeContributor,
                        com.assistant.contributors.BeastTackleContributor,
                        com.assistant.contributors.LegGapBlockerContributor,
                        com.assistant.contributors.BodyWeightPressContributor,
                        com.assistant.contributors.DecoyRunContributor,
                        com.assistant.contributors.MidfieldHoldContributor,
                        com.assistant.contributors.AnticipationContributor,
                        com.assistant.contributors.PlayerSwitchContributor,
                        com.assistant.contributors.AutoEngageContributor
                    )

                    // MEDIUM: Runtime assertion for 57-contributor contract
                    check(allContributors.size == EXPECTED_CONTRIBUTOR_COUNT) {
                        "CRITICAL: Hardcoded contributor list size (${allContributors.size}) != expected ($EXPECTED_CONTRIBUTOR_COUNT)"
                    }
                    
                    for (c in allContributors) {
                        if (generation.get() != myGeneration) return@submit
                        val success = GameplayEngineRegistry.register(c)
                        if (!success) initFailures.add(c.engineName)
                    }
                    
                    if (generation.get() != myGeneration) return@submit
                    
                    val warmFails = GameplayEngineRegistry.warmAll()
                    warmFailures.addAll(warmFails)
                    
                    if (generation.get() != myGeneration) return@submit
                    
                    // HIGH: Distinguish omission from failure
                    val actualNames = GameplayEngineRegistry.getRegisteredNames()
                    val missing = EXPECTED_CONTRIBUTOR_NAMES - actualNames
                    omittedContributors.addAll(missing)
                    
                    val finalState = when {
                        missing.isNotEmpty() || initFailures.isNotEmpty() || warmFailures.isNotEmpty() -> RegistrationState.PARTIAL
                        else -> RegistrationState.READY
                    }
                    
                    state.set(finalState)
                    RuntimeLogger.log(dumpRuntimeProof(myGeneration), "RUNTIME")
                    
                } catch (e: Throwable) {
                    if (generation.get() == myGeneration) {
                        state.set(RegistrationState.FAILED)
                        RuntimeLogger.log("AppContributorRegistration [Gen $myGeneration] FAILED: ${e.message}", "RUNTIME")
                    }
                }
            }
        }
    }

    fun reset() {
        generation.incrementAndGet()
        currentFuture?.cancel(true) // MEDIUM: Hard cancellation via Future interrupt
        currentFuture = null
        state.set(RegistrationState.IDLE)
        GameplayEngineRegistry.resetAll()
    }
    
    // MEDIUM: Explicit restart behavior for PARTIAL/FAILED
    fun retry() {
        if (state.get() == RegistrationState.PARTIAL || state.get() == RegistrationState.FAILED) {
            state.set(RegistrationState.IDLE)
            ensureRegistered()
        }
    }
    
    // LOW: Live runtime proof
    fun dumpRuntimeProof(gen: Long = generation.get()): String {
        val actualCount = GameplayEngineRegistry.getRegisteredNames().size
        return """
            |=== REGISTRATION DIAGNOSTICS [Gen $gen] ===
            |State: ${state.get()}
            |Expected Count: $EXPECTED_CONTRIBUTOR_COUNT | Actual Count: $actualCount
            |Init Failures: ${initFailures.size} ${if(initFailures.isNotEmpty()) "[${initFailures.joinToString()}]" else ""}
            |Warm Failures: ${warmFailures.size} ${if(warmFailures.isNotEmpty()) "[${warmFailures.joinToString()}]" else ""}
            |Omitted/Missing: ${omittedContributors.size} ${if(omittedContributors.isNotEmpty()) "[${omittedContributors.joinToString()}]" else ""}
            |Registry Snapshot: ${GameplayEngineRegistry.registryRuntimeSnapshot()}
            |Bus acceptance/dispatch: ${SmartAssistMetrics.busExecutionRuntimeSnapshot()}
            |Visual correlation (not causal proof): ${ActionOutcomeVerifier.diagnostics()}
            |=============================================
        """.trimMargin()
    }
    
    fun registrationState(): RegistrationState = state.get()
}
