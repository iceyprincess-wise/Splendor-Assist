#!/usr/bin/env python3
import os, sys, subprocess, shutil

REPO_DIR = os.path.expanduser('~/projects/Splendor-Assist')
HEAD_EXPECTED = '6cb817ec'

def run_cmd(cmd, cwd=None):
    print(f'Running: {" ".join(cmd)}')
    res = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=600)
    if res.returncode != 0:
        print(f'STDOUT:\n{res.stdout}')
        print(f'STDERR:\n{res.stderr}')
        sys.exit(1)
    return res.stdout

def verify_head():
    res = subprocess.run(['git', 'log', '-1', '--oneline', 'HEAD'], cwd=REPO_DIR, capture_output=True, text=True, timeout=10)
    if not res.stdout.startswith(HEAD_EXPECTED):
        print(f'FATAL: HEAD mismatch. Expected {HEAD_EXPECTED}, got {res.stdout}')
        sys.exit(1)
    print('HEAD verified.')

def apply_patch(filepath, old_str, new_str, description):
    with open(filepath, 'r') as f: content = f.read()
    count = content.count(old_str)
    if count == 0:
        print(f'FATAL: Anchor not found for {description} in {filepath}')
        sys.exit(1)
    if count > 1:
        print(f'FATAL: Multiple anchors found ({count}) for {description} in {filepath}')
        sys.exit(1)
    new_content = content.replace(old_str, new_str)
    tmp_path = filepath + '.tmp'
    with open(tmp_path, 'w') as f: f.write(new_content)
    shutil.move(tmp_path, filepath)
    print(f'PATCHED: {description}')

def main():
    if not os.path.isdir(REPO_DIR):
        print(f'FATAL: Repo directory not found at {REPO_DIR}')
        sys.exit(1)
    verify_head()
    
    base_interceptor = os.path.join(REPO_DIR, "app/src/main/java/com/assistant/overlay/interceptor")
    base_assistant = os.path.join(REPO_DIR, "app/src/main/java/com/assistant")
    
    # 1. ThreatPriorityEngine.kt
    tpe = os.path.join(base_interceptor, "ThreatPriorityEngine.kt")
    apply_patch(tpe, """data class ThreatDecision(
    val threat: ThreatType,
    val zone: ThreatZone,
    val direction: ShotDirection,
    val priority: Int
)""", """enum class HeightBand { TOP, MID, BOTTOM }

data class ThreatDecision(
    val threat: ThreatType,
    val zone: ThreatZone,
    val direction: ShotDirection,
    val priority: Int,
    val heightBand: HeightBand = HeightBand.MID,
    val normX: Float = 0.5f
)""", "ThreatDecision + HeightBand")

    apply_patch(tpe, """    fun evaluate(
        threat: ThreatType,
        zone: ThreatZone
    ): ThreatDecision {

        val direction =
            ShotDirectionEngine.detect(
                zone,
                threat
            )

        var priority = threat.score

        // INTERCEPTION AUTHORITY BOOST
        when (direction) {

            ShotDirection.CROSS ->
                priority += 35

            ShotDirection.LONG_BALL ->
                priority += 30

            else -> {}
        }

        priority +=
            (InterceptionRuntimeRegistry.awareness / 2)

        priority +=
            (InterceptionRuntimeRegistry.prediction / 2)

        priority +=
            GoalkeeperAdaptiveFeedbackEngine
                .interceptionBonus()

        priority +=
            GoalkeeperAdaptiveFeedbackEngine
                .recoveryBonus()

        when (zone) {

            ThreatZone.GOAL_AREA ->
                priority += 40

            ThreatZone.BOX ->
                priority += 25

            ThreatZone.CENTER ->
                priority += 10

            else -> {}
        }

        return ThreatDecision(
            threat = threat,
            zone = zone,
            direction = direction,
            priority = priority
        )
    }""", """    fun evaluate(
        threat: ThreatType,
        zone: ThreatZone,
        x: Int = 0,
        y: Int = 0,
        width: Int = 1,
        height: Int = 1
    ): ThreatDecision {

        val direction =
            ShotDirectionEngine.detect(
                zone,
                threat
            )

        var priority = threat.score

        // INTERCEPTION AUTHORITY BOOST
        when (direction) {

            ShotDirection.CROSS ->
                priority += 35

            ShotDirection.LONG_BALL ->
                priority += 30

            else -> {}
        }

        priority +=
            (InterceptionRuntimeRegistry.awareness / 2)

        priority +=
            (InterceptionRuntimeRegistry.prediction / 2)

        priority +=
            GoalkeeperAdaptiveFeedbackEngine
                .interceptionBonus()

        priority +=
            GoalkeeperAdaptiveFeedbackEngine
                .recoveryBonus()

        when (zone) {

            ThreatZone.GOAL_AREA ->
                priority += 40

            ThreatZone.BOX ->
                priority += 25

            ThreatZone.CENTER ->
                priority += 10

            else -> {}
        }

        val ny = if (height > 0) y.toFloat() / height.toFloat() else 0.5f
        val nx = if (width > 0) x.toFloat() / width.toFloat() else 0.5f
        val band = when {
            ny > 0.80f -> HeightBand.BOTTOM
            ny < 0.40f -> HeightBand.TOP
            else -> HeightBand.MID
        }

        return ThreatDecision(
            threat = threat,
            zone = zone,
            direction = direction,
            priority = priority,
            heightBand = band,
            normX = nx
        )
    }""", "ThreatPriorityEngine.evaluate")

    # 2. GoalkeeperActionRouter.kt
    gar = os.path.join(base_interceptor, "GoalkeeperActionRouter.kt")
    apply_patch(gar, """enum class GoalkeeperAction {
    TRACK,
    DIVE_LEFT,
    DIVE_RIGHT,
    CLAIM_CROSS,
    PUNCH_CROSS,
    RUSH_OUT,
    BLOCK_LEFT,
    BLOCK_RIGHT,
    RECOVER,
    HOLD
}""", """enum class GoalkeeperAction {
    TRACK,
    DIVE_LEFT,
    DIVE_RIGHT,
    CLAIM_CROSS,
    PUNCH_CROSS,
    RUSH_OUT,
    BLOCK_LEFT,
    BLOCK_RIGHT,
    RECOVER,
    HOLD,
    DIVE_BOTTOM_LEFT,
    DIVE_BOTTOM_RIGHT
}""", "GoalkeeperAction enum")

    apply_patch(gar, """            panicOverride &&
                panic ==
                PanicAction.BLOCK_LEFT -> {
                GoalkeeperMetricsRegistry
                    .panicSaves
                    .incrementAndGet()

                GoalkeeperAction.BLOCK_LEFT
            }""", """            panicOverride && decision.heightBand == HeightBand.BOTTOM && decision.normX < 0.4f -> {
                GoalkeeperMetricsRegistry.panicSaves.incrementAndGet()
                GoalkeeperAction.DIVE_BOTTOM_LEFT
            }

            panicOverride && decision.heightBand == HeightBand.BOTTOM && decision.normX > 0.6f -> {
                GoalkeeperMetricsRegistry.panicSaves.incrementAndGet()
                GoalkeeperAction.DIVE_BOTTOM_RIGHT
            }

            panicOverride &&
                panic ==
                PanicAction.BLOCK_LEFT -> {
                GoalkeeperMetricsRegistry
                    .panicSaves
                    .incrementAndGet()

                GoalkeeperAction.BLOCK_LEFT
            }""", "GoalkeeperActionRouter panic overrides")

    apply_patch(gar, """        return when {

            panicOverride &&
                panic ==
                PanicAction.BLOCK_LEFT -> {""", """        return when {

            anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.BOTTOM && decision.normX < 0.4f ->
                GoalkeeperAction.DIVE_BOTTOM_LEFT

            anticipation == AnticipationResult.SAVE && decision.heightBand == HeightBand.BOTTOM && decision.normX > 0.6f ->
                GoalkeeperAction.DIVE_BOTTOM_RIGHT

            panicOverride &&
                panic ==
                PanicAction.BLOCK_LEFT -> {""", "GoalkeeperActionRouter save overrides")

    # 3. GoalkeeperExecutionEngine.kt
    gee = os.path.join(base_interceptor, "GoalkeeperExecutionEngine.kt")
    apply_patch(gee, """            GoalkeeperAction.DIVE_RIGHT ->
                DiveRightActionEngine.vector(width, height)

            GoalkeeperAction.BLOCK_LEFT ->""", """            GoalkeeperAction.DIVE_RIGHT ->
                DiveRightActionEngine.vector(width, height)

            GoalkeeperAction.DIVE_BOTTOM_LEFT ->
                BottomLeftActionEngine.vector(width, height)

            GoalkeeperAction.DIVE_BOTTOM_RIGHT ->
                BottomRightActionEngine.vector(width, height)

            GoalkeeperAction.BLOCK_LEFT ->""", "GoalkeeperExecutionEngine.vectorFor")

    # 4. Create Action Engines
    bl_path = os.path.join(base_interceptor, "BottomLeftActionEngine.kt")
    with open(bl_path, 'w') as f: f.write("""package com.assistant.overlay.interceptor

object BottomLeftActionEngine {
    fun vector(width: Float, height: Float): FloatArray {
        return floatArrayOf(
            width * 0.50f, height * 0.72f,
            width * 0.15f, height * 0.92f
        )
    }
}""")
    print(f"CREATED: {bl_path}")

    br_path = os.path.join(base_interceptor, "BottomRightActionEngine.kt")
    with open(br_path, 'w') as f: f.write("""package com.assistant.overlay.interceptor

object BottomRightActionEngine {
    fun vector(width: Float, height: Float): FloatArray {
        return floatArrayOf(
            width * 0.50f, height * 0.72f,
            width * 0.85f, height * 0.92f
        )
    }
}""")
    print(f"CREATED: {br_path}")

    # 5. OmnipotentGoalkeeperEngine.kt
    oge = os.path.join(base_interceptor, "OmnipotentGoalkeeperEngine.kt")
    apply_patch(oge, """    @Volatile private var capturedWidth = 1650.0f
    @Volatile private var capturedHeight = 720.0f
    private val executionCoordinates = FloatArray(4)
    private var executionThread: HandlerThread? = null
    private var executionHandler: Handler? = null
    private var hintSession: PerformanceHintManager.Session? = null""", """    @Volatile private var capturedWidth = 1650.0f
    @Volatile private var capturedHeight = 720.0f
    private val executionCoordinates = FloatArray(4)""", "OmnipotentGoalkeeperEngine fields")

    apply_patch(oge, """    fun initializeEngine(hintManager: PerformanceHintManager?) {
        if (executionThread != null) return
        executionThread = HandlerThread("OmnipotentGKCoreThread", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
            start()
            executionHandler = Handler(looper)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hintManager != null) {
            try {
                hintSession = hintManager.createHintSession(intArrayOf(executionThread!!.threadId), 2000000L)
            } catch (e: Exception) {
                // Silently bypass hint initialization failures per strict architectural constraints
            }
        }
    }""", "", "OmnipotentGoalkeeperEngine initializeEngine")

    apply_patch(oge, """        var anomalyDetected = false
        var detectedThreat = ThreatType.NONE
        var detectedZone = ThreatZone.CENTER""", """        var anomalyDetected = false
        var detectedThreat = ThreatType.NONE
        var detectedZone = ThreatZone.CENTER
        var detectedX = 0
        var detectedY = 0""", "OmnipotentGoalkeeperEngine scan vars")

    apply_patch(oge, """                        if (threat != ThreatType.NONE) {
                            detectedThreat = threat
                            detectedZone = ThreatZoneEngine.detect(x, y, width, height)

                            TelemetryCoordinator.updatePlayerMotion(""", """                        if (threat != ThreatType.NONE) {
                            detectedThreat = threat
                            detectedZone = ThreatZoneEngine.detect(x, y, width, height)
                            detectedX = x
                            detectedY = y

                            TelemetryCoordinator.updatePlayerMotion(""", "OmnipotentGoalkeeperEngine scan detect")

    apply_patch(oge, """            val decision = ThreatPriorityEngine.evaluate(
                detectedThreat,
                detectedZone
            )""", """            val decision = ThreatPriorityEngine.evaluate(
                detectedThreat,
                detectedZone,
                detectedX,
                detectedY,
                width,
                height
            )""", "OmnipotentGoalkeeperEngine scan evaluate")

    apply_patch(oge, """        executionHandler?.post { 
            val workStartNanos = System.nanoTime()
            try {

                // Absolute Hardware Limit Vectors for Redmi 15C class
                val screenWidthBase = capturedWidth
                val screenHeightBase = capturedHeight""", """        val workStartNanos = System.nanoTime()
        try {

            // Absolute Hardware Limit Vectors for Redmi 15C class
            val screenWidthBase = capturedWidth
            val screenHeightBase = capturedHeight""", "OmnipotentGoalkeeperEngine eval func start")

    apply_patch(oge, """                GoalkeeperMetricsRegistry.recoveryCount.incrementAndGet()
            } finally { 
                // MOVED: reportActualWorkDuration MUST be called AFTER work is done with actual duration
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    hintSession?.reportActualWorkDuration(System.nanoTime() - workStartNanos)
                }
                GoalkeeperStateMachine.transition(GoalkeeperState.IDLE) 
            }
        }
    }""", """            GoalkeeperMetricsRegistry.recoveryCount.incrementAndGet()
        } finally { 
            GoalkeeperStateMachine.transition(GoalkeeperState.IDLE) 
        }
    }""", "OmnipotentGoalkeeperEngine eval finally")

    # 6. ShotAnticipationEngine.kt
    sae = os.path.join(base_interceptor, "ShotAnticipationEngine.kt")
    apply_patch(sae, """    // Upgraded main evaluation routine that takes physical screen anchor coordinates
    fun evaluateAndDispatch(
        decision: ThreatDecision,
        anchorX: Float = 825f,  // Default standard horizontal center fallback
        anchorY: Float = 360f   // Default standard vertical center fallback
    ): AnticipationResult {

        // 1. Run your core evaluation matrix algorithms intact
        val predictionBonus = InterceptionRuntimeRegistry.prediction +
                GoalkeeperAdaptiveFeedbackEngine.interceptionBonus()

        val result = when {
            decision.direction == ShotDirection.CROSS && predictionBonus >= 70 -> AnticipationResult.INTERCEPT
            decision.direction == ShotDirection.LONG_BALL && predictionBonus >= 70 -> AnticipationResult.INTERCEPT
            decision.direction == ShotDirection.CROSS && decision.priority >= 90 -> AnticipationResult.INTERCEPT
            decision.direction == ShotDirection.LONG_BALL && decision.priority >= 85 -> AnticipationResult.INTERCEPT
            decision.priority >= 130 -> AnticipationResult.PANIC
            decision.priority >= 100 -> AnticipationResult.SAVE
            decision.priority >= 75 -> AnticipationResult.INTERCEPT
            else -> AnticipationResult.TRACK
        }

        // 2. UPGRADE: If a high-tier danger state is evaluated, immediately dispatch an automated bus stroke
        if (result == AnticipationResult.SAVE || result == AnticipationResult.INTERCEPT || result == AnticipationResult.PANIC) {
            try {
                // Calculate an interceptive layout direction based on threat priority weight
                val sweepMagnitude = 60f
                val endYOffset = if (result == AnticipationResult.SAVE) -sweepMagnitude else sweepMagnitude

                val request = ExecutionRequest(
                    source = ExecutionSource.INTERCEPTION,
                    phase = 5,
                    startX = anchorX,
                    startY = anchorY,
                    endX = anchorX,
                    endY = anchorY + endYOffset,
                    duration = 30L // Fast-executing 30ms stroke path to defeat low-end budget input latency
                )

                val submitted = ContributionRegistry.offer(request)
                if (submitted) {
                    RuntimeLogger.log("SHOT_ANTICIPATION automated defensive bus dispatch triggered action=$result", "DEFENSE")
                }
            } catch (e: Exception) {
                RuntimeLogger.log("Defensive hardware dispatch step skipped: ${e.message}", "DEFENSE")
            }
        }

        return result
    }

    // Legacy fallback version to guarantee compilation compatibility with your older analysis registries""", """    // Legacy fallback version to guarantee compilation compatibility with your older analysis registries""", "ShotAnticipationEngine evaluateAndDispatch removal")

    # 7. gameplay_engine.kt
    ge = os.path.join(base_assistant, "gameplay_engine.kt")
    apply_patch(ge, """    private fun probe(b: ByteBuffer, w: Int, h: Int, stride: Int, nx: Float, ny: Float): IntArray? {
        val x = (nx * w).toInt(); val y = (ny * h).toInt()
        val idx = y * stride + x * 4
        if (idx < 0 || idx + 2 >= b.capacity()) return null
        probesRead.incrementAndGet()
        return intArrayOf(px(b, idx), px(b, idx + 1), px(b, idx + 2))
    }

    private fun isGreen(c: IntArray) = c[1] > c[0] + 8 && c[1] > c[2] + 8
    private fun isGray(c: IntArray) = kotlin.math.abs(c[0] - c[1]) <= 14 && kotlin.math.abs(c[1] - c[2]) <= 14 && c[0] in 110..215
    private fun isBlue(c: IntArray) = c[2] > c[0] + 50 && c[2] > 140""", """    private val probeBuffer = IntArray(3)
    private fun probe(b: ByteBuffer, w: Int, h: Int, stride: Int, nx: Float, ny: Float): Boolean {
        val x = (nx * w).toInt(); val y = (ny * h).toInt()
        val idx = y * stride + x * 4
        if (idx < 0 || idx + 2 >= b.capacity()) return false
        probesRead.incrementAndGet()
        probeBuffer[0] = px(b, idx); probeBuffer[1] = px(b, idx + 1); probeBuffer[2] = px(b, idx + 2)
        return true
    }

    private fun isGreen() = probeBuffer[1] > probeBuffer[0] + 8 && probeBuffer[1] > probeBuffer[2] + 8
    private fun isGray() = kotlin.math.abs(probeBuffer[0] - probeBuffer[1]) <= 14 && kotlin.math.abs(probeBuffer[1] - probeBuffer[2]) <= 14 && probeBuffer[0] in 110..215
    private fun isBlue() = probeBuffer[2] > probeBuffer[0] + 50 && probeBuffer[2] > 140""", "ControlMappingTrainer probe allocation fix")

    apply_patch(ge, """    fun observe(buffer: ByteBuffer, w: Int, h: Int, rowStride: Int) {
        if (w <= 0 || h <= 0) return
        observed.incrementAndGet()
        val tab = probe(buffer, w, h, rowStride, TAB_NX, TAB_NY)
        val back = probe(buffer, w, h, rowStride, BACK_NX, BACK_NY)
        val tabGray = tab != null && isGray(tab)
        val backBlue = back != null && isBlue(back)
        var green = 0
        for (s in SLOTS) {
            val c = probe(buffer, w, h, rowStride, slotX[s.id], slotY[s.id])
            if (c != null && isGreen(c)) green++
        }
        val joy = probe(buffer, w, h, rowStride, JOY_NX, JOY_NY)
        val joyGray = joy != null && (isGray(joy) || isGreen(joy))""", """    fun observe(buffer: ByteBuffer, w: Int, h: Int, rowStride: Int) {
        if (w <= 0 || h <= 0) return
        observed.incrementAndGet()
        val tabGray = probe(buffer, w, h, rowStride, TAB_NX, TAB_NY) && isGray()
        val backBlue = probe(buffer, w, h, rowStride, BACK_NX, BACK_NY) && isBlue()
        var green = 0
        for (s in SLOTS) {
            if (probe(buffer, w, h, rowStride, slotX[s.id], slotY[s.id]) && isGreen()) green++
        }
        val joyGray = probe(buffer, w, h, rowStride, JOY_NX, JOY_NY) && (isGray() || isGreen())""", "ControlMappingTrainer observe")

    apply_patch(ge, """        if (next == UiMode.SETTINGS) {
            settingsFrames.incrementAndGet()
            for (s in SLOTS) {
                var bestNx = slotX[s.id]; var bestNy = slotY[s.id]; var bestScore = -1; var hit = false
                for (oy in intArrayOf(-2, 0, 2)) for (ox in intArrayOf(-2, 0, 2)) {
                    val nx = s.nx + ox * 0.01f; val ny = s.ny + oy * 0.01f
                    val c = probe(buffer, w, h, rowStride, nx, ny) ?: continue
                    val score = c[1] - (c[0] + c[2]) / 2
                    if (isGreen(c) && score > bestScore) { bestScore = score; bestNx = nx; bestNy = ny; hit = true }
                }
                if (hit) {
                    slotX[s.id] = slotX[s.id] * 0.7f + bestNx * 0.3f
                    slotY[s.id] = slotY[s.id] * 0.7f + bestNy * 0.3f
                    slotHits[s.id]++
                }
            }
            lastMove = "settings-training"
        }""", """        if (next == UiMode.SETTINGS) {
            settingsFrames.incrementAndGet()
            for (s in SLOTS) {
                var bestNx = slotX[s.id]; var bestNy = slotY[s.id]; var bestScore = -1; var hit = false
                for (oy in -2..2 step 2) for (ox in -2..2 step 2) {
                    val nx = s.nx + ox * 0.01f; val ny = s.ny + oy * 0.01f
                    if (probe(buffer, w, h, rowStride, nx, ny) && isGreen()) {
                        val score = probeBuffer[1] - (probeBuffer[0] + probeBuffer[2]) / 2
                        if (score > bestScore) { bestScore = score; bestNx = nx; bestNy = ny; hit = true }
                    }
                }
                if (hit) {
                    slotX[s.id] = slotX[s.id] * 0.7f + bestNx * 0.3f
                    slotY[s.id] = slotY[s.id] * 0.7f + bestNy * 0.3f
                    slotHits[s.id]++
                }
            }
            lastMove = "settings-training"
        }""", "ControlMappingTrainer settings training")

    print("All patches applied successfully.")
    
    print("Running git diff --check...")
    run_cmd(['git', 'diff', '--check'], cwd=REPO_DIR)
    
    print("Running structural build verification...")
    run_cmd(['./gradlew', 'assembleDebug', '--no-daemon', '--stacktrace'], cwd=REPO_DIR)
    
    print("BUILD PASSED. Task closure protocol satisfied.")

if __name__ == '__main__':
    main()
