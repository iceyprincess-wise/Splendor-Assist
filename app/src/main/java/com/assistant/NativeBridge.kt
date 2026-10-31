package com.assistant
import java.nio.ByteBuffer

import com.assistant.diagnostic.RuntimeLogger
import java.util.concurrent.atomic.AtomicBoolean

object NativeBridge {
    private const val LIBRARY = "splendor_native"
    private val nativeReady = AtomicBoolean(false)
    
    @Volatile
    private var nativeFailure = ""
    
    @Volatile
    private var failureLogged = false

    // SPLENDOR_V25_NATIVE_TRUTH_BEGIN
    @Volatile
    var nativeLoaded: Boolean = false
        private set

    @Volatile
    var nativePreprocessAvailable: Boolean = true
        private set

    @Volatile
    var nativeAuthorityAvailable: Boolean = true
        private set

    @Volatile
    private var nativeLastError: String? = null

    @Volatile
    private var nativePreprocessProofLogged: Boolean = false

    @Volatile
    private var nativeAuthorityProofLogged: Boolean = false

    @Volatile
    private var nativeTruthLogged: Boolean = false

    @JvmStatic
    fun markNativeLoaded() {
        nativeLoaded = true
    }

    @JvmStatic
    fun markNativePreprocessUnavailable(t: Throwable? = null) {
        nativePreprocessAvailable = false
        nativeLoaded = false
        nativeLastError = t?.toString()
        try {
            com.assistant.diagnostic.RuntimeLogger.log(
                "PREPROCESS_NATIVE_UNAVAILABLE " + (t?.toString() ?: "unknown"),
                "NATIVE_VISION"
            )
        } catch (e: Throwable) {
            android.util.Log.w("NativeBridge", "RuntimeLogger failed", e)
        }
    }

    @JvmStatic
    fun markNativeAuthorityUnavailable(t: Throwable? = null) {
        nativeAuthorityAvailable = false
        nativeLoaded = false
        nativeLastError = t?.toString()
        try {
            com.assistant.diagnostic.RuntimeLogger.log(
                "AUTHORITY_NATIVE_UNAVAILABLE " + (t?.toString() ?: "unknown"),
                "NATIVE_ARBITRATION"
            )
        } catch (e: Throwable) {
            android.util.Log.w("NativeBridge", "RuntimeLogger failed", e)
        }
    }

    @JvmStatic
    fun logNativePreprocessProofOnce() {
        if (!nativePreprocessProofLogged) {
            nativePreprocessProofLogged = true
            try {
                com.assistant.diagnostic.RuntimeLogger.log("NATIVE_VISION_PREPROCESS_ACTIVE", "NATIVE_VISION")
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    fun logNativeAuthorityProofOnce() {
        if (!nativeAuthorityProofLogged) {
            nativeAuthorityProofLogged = true
            try {
                com.assistant.diagnostic.RuntimeLogger.log("AUTHORITY_NATIVE_ACTIVE", "NATIVE_ARBITRATION")
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    fun nativeTruthSnapshot(): String {
        return "nativeLoaded=" + nativeLoaded +
            " preprocessAvailable=" + nativePreprocessAvailable +
            " authorityAvailable=" + nativeAuthorityAvailable +
            " lastError=" + (nativeLastError ?: "none") +
            " camera=" + com.assistant.vision.CameraProfile.diagnostics()
    }

    @JvmStatic
    fun logNativeTruthOnce() {
        if (!nativeTruthLogged) {
            nativeTruthLogged = true
            try {
                com.assistant.diagnostic.RuntimeLogger.log("NATIVE_TRUTH " + nativeTruthSnapshot(), "NATIVE_TRUTH")
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    external fun nativePreprocessFrame(
        src: ByteBuffer,
        dst: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        inputSize: Int,
        cameraProfileId: Int
    ): Int

    @JvmStatic
    external fun nativeAuthorityArbitrate(
        mode: Int,
        passX: Float,
        passY: Float,
        crossX: Float,
        crossY: Float,
        predictiveX: Float,
        predictiveY: Float,
        receiver: Float,
        forward: Float,
        recovery: Float,
        shot: Float,
        stability: Float
    ): Long

    @JvmStatic
    external fun nativeBallRetentionShieldCompute(
        hasBall: Boolean,
        trusted: Boolean,
        confidence: Float,
        ballX: Float,
        ballY: Float,
        viableLaneCount: Int,
        passTargetX: Float,
        passTargetY: Float,
        zonesLeftTheirs: Int,
        zonesMidTheirs: Int,
        zonesRightTheirs: Int,
        defenderDensity: Float,
        laneCount: Int,
        frameWidth: Float,
        frameHeight: Float,
        resultBuffer: FloatArray
    )

    @JvmStatic
    external fun nativeInstantInterceptCompute(
        hasBall: Boolean,
        trusted: Boolean,
        confidence: Float,
        ownerHasOwner: Boolean,
        ownerX: Float,
        ownerY: Float,
        ownerVx: Float,
        ownerVy: Float,
        ownerIsUserTeam: Boolean,
        ballX: Float,
        ballY: Float,
        resultBuffer: FloatArray
    )

    @JvmStatic
    external fun nativeExtractBlobs(
        byteBuffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        threshold: Float,
        outputBlobs: IntArray
    ): Int
    // SPLENDOR_V25_NATIVE_TRUTH_END


    @JvmStatic
    external fun nativeAnalyzeFrame(
        byteBuffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        outBuffer: FloatArray
    )
init {
        try {
            System.loadLibrary(LIBRARY)
            nativeReady.set(true)
            markNativeLoaded()
            RuntimeLogger.log("splendor_native loaded; explicit JNI registration available.", "NativeBridge")
        } catch (e: Throwable) {
            nativeLoaded = false
            nativePreprocessAvailable = false
            nativeAuthorityAvailable = false
            nativeLastError = e.toString()
            nativeFailure = e.message ?: "Unknown load error"
            nativeReady.set(false)
            android.util.Log.e("NativeBridge", "CRITICAL: Failed to load $LIBRARY", e)
        }
    }

    @JvmStatic
    fun isNativeReady(): Boolean = nativeReady.get()

    private fun logFailureOnce() {
        if (!failureLogged) {
            failureLogged = true
            RuntimeLogger.log("Native library unavailable - $nativeFailure", "NativeBridge")
        }
    }

    @JvmStatic
    fun nativeKickingPosture(
        carrierX: Float, carrierY: Float,
        carrierVx: Float, carrierVy: Float,
        targetX: Float, targetY: Float,
        pitchWidth: Float, pitchHeight: Float,
        outBuffer: FloatArray
    ) {
        if (!nativeReady.get()) {
            logFailureOnce()
            return
        }
        nativeKickingPostureImpl(
            carrierX, carrierY, carrierVx, carrierVy,
            targetX, targetY, pitchWidth, pitchHeight, outBuffer
        )
    }

    @JvmStatic
    fun nativeAgilityPhysics(
        playerVelocity: Float, opponentDistance: Float,
        movementAngleDegrees: Float, possessionConfidence: Float,
        turnIntensity: Float, playerX: Float, playerY: Float,
        oppX: Float, oppY: Float, outBuffer: FloatArray,
        nearUnits: Float, proxUnits: Float, softUnits: Float, farUnits: Float,
        maxVelocity: Float, shieldBase: Float, shieldProx: Float,
        shieldSpeed: Float, shieldConf: Float, durationBase: Float,
        confFloor: Float, distGuard: Float
    ) {
        if (!nativeReady.get()) {
            logFailureOnce()
            return
        }
        nativeAgilityPhysicsImpl(
            playerVelocity, opponentDistance, movementAngleDegrees, possessionConfidence,
            turnIntensity, playerX, playerY, oppX, oppY, outBuffer,
            nearUnits, proxUnits, softUnits, farUnits,
            maxVelocity, shieldBase, shieldProx, shieldSpeed,
            shieldConf, durationBase, confFloor, distGuard
        )
    }

    @JvmStatic
    private external fun nativeKickingPostureImpl(
        carrierX: Float, carrierY: Float,
        carrierVx: Float, carrierVy: Float,
        targetX: Float, targetY: Float,
        pitchWidth: Float, pitchHeight: Float,
        outBuffer: FloatArray
    )

    @JvmStatic
    private external fun nativeAgilityPhysicsImpl(
        playerVelocity: Float, opponentDistance: Float,
        movementAngleDegrees: Float, possessionConfidence: Float,
        turnIntensity: Float, playerX: Float, playerY: Float,
        oppX: Float, oppY: Float, outBuffer: FloatArray,
        nearUnits: Float, proxUnits: Float, softUnits: Float, farUnits: Float,
        maxVelocity: Float, shieldBase: Float, shieldProx: Float,
        shieldSpeed: Float, shieldConf: Float, durationBase: Float,
        confFloor: Float, distGuard: Float
    )





    external fun nativeBuildUpPressCompute(
        hasBall: Boolean,
        trusted: Boolean,
        confidence: Float,
        ownerHasOwner: Boolean,
        ownerX: Float,
        ownerY: Float,
        ownerIsUserTeam: Boolean,
        ballX: Float,
        ballY: Float,
        resultBuffer: FloatArray
    )


    // --- V43: Final Critical Core Additions ---

    @JvmStatic
    fun nativeDirectInputHookPrepare(
        xCoords: FloatArray, yCoords: FloatArray, eventTimes: FloatArray,
        pointCount: Int, screenW: Float, screenH: Float, outFlat: FloatArray
    ) {
        if (!nativeReady.get()) { logFailureOnce(); return }
        nativeDirectInputHookImpl(xCoords, yCoords, eventTimes, pointCount, screenW, screenH, outFlat)
    }
    @JvmStatic
    private external fun nativeDirectInputHookImpl(
        xCoords: FloatArray, yCoords: FloatArray, eventTimes: FloatArray,
        pointCount: Int, screenW: Float, screenH: Float, outFlat: FloatArray
    )


    @JvmStatic
    fun nativePredictiveFrameInterp(
        prevX: FloatArray, prevY: FloatArray,
        currX: FloatArray, currY: FloatArray,
        dt: Float, playerCount: Int,
        outX: FloatArray, outY: FloatArray
    ) {
        if (!nativeReady.get()) { logFailureOnce(); return }
        nativePredictiveFrameInterpImpl(prevX, prevY, currX, currY, dt, playerCount, outX, outY)
    }
    @JvmStatic
    private external fun nativePredictiveFrameInterpImpl(
        prevX: FloatArray, prevY: FloatArray,
        currX: FloatArray, currY: FloatArray,
        dt: Float, playerCount: Int,
        outX: FloatArray, outY: FloatArray
    )


    // --- V44/V45: System Core & Dynamic Jitter ---

    // --- V46: Defensive Dominance Matrix ---
    @JvmStatic external fun nativeComputeDominanceVectors(defX: Float, defY: Float, defVx: Float, defVy: Float, targetX: Float, targetY: Float, targetVx: Float, targetVy: Float, isHoldingPressure: Boolean, screenWidth: Float, screenHeight: Float, globalMatchTick: Int, outBuffer: FloatArray)
    // --- V47: Active Aggressive Stopper Matrix ---
    @JvmStatic external fun nativeExecuteStopperMatrix(playerX: Float, playerY: Float, playerVx: Float, playerVy: Float, homeAnchorX: Float, homeAnchorY: Float, oppX: Float, oppY: Float, oppVx: Float, oppVy: Float, isHoldingPressure: Boolean, screenWidth: Float, screenHeight: Float, outBuffer: FloatArray)


    // --- V48: CrossClaim Native Migration ---


    // --- V49: KeeperBias Native Migration ---


    // --- V50: PanicSave Native Migration ---


    // --- V54: TrueShot Native Migration ---
    @JvmStatic external fun nativeTrueShotCompute(ballX: Float, ballY: Float, goalLeftX: Float, goalRightX: Float, goalTopY: Float, goalBottomY: Float, goalkeeperX: Float, goalkeeperVisible: Boolean, goalDetected: Boolean, defenderDensity: Float, screenW: Float, screenH: Float, outBuffer: FloatArray)
    // --- V55: Heavy Mutation Engines ---
    @JvmStatic external fun nativeComputeOpticalVariance(currentBuffer: java.nio.ByteBuffer, previousBuffer: java.nio.ByteBuffer, width: Int, height: Int, stride: Int, roiX: Int, roiY: Int, roiW: Int, roiH: Int): Float
// --- V56: SmartAssistUltimateCorrector Native Migration ---
    @JvmStatic external fun nativeSACorrectPass(ballX: Float, ballY: Float, intendedX: Float, intendedY: Float, receiverVx: Float, receiverVy: Float, nearestOppX: Float, nearestOppY: Float, pressure: Float, screenW: Float, screenH: Float, outBuffer: FloatArray)
    @JvmStatic external fun nativeSACorrectShot(ballX: Float, ballY: Float, glX: Float, grX: Float, gtY: Float, gbY: Float, gkX: Float, gkVis: Boolean, goalDet: Boolean, screenW: Float, screenH: Float, outBuffer: FloatArray)
    @JvmStatic external fun nativeSACorrectCross(ballX: Float, ballY: Float, rx: Float, ry: Float, rvx: Float, rvy: Float, gcx: Float, gcy: Float, laneScore: Float, screenW: Float, screenH: Float, outBuffer: FloatArray)
    @JvmStatic external fun nativeSACorrectKeeper(ballX: Float, ballY: Float, glX: Float, grX: Float, gtY: Float, gbY: Float, screenW: Float, screenH: Float, outBuffer: FloatArray)


    // --- V57: CriticalAttackingVector & CrossPrecision Native Migration ---
    @JvmStatic external fun nativeCriticalScoringVector(strikerX: Float, strikerY: Float, gkX: Float, gkY: Float, goalLeftPostX: Float, goalLeftPostY: Float, goalRightPostX: Float, goalRightPostY: Float, controlOriginX: Float, controlOriginY: Float, controlRadius: Float, outBuffer: FloatArray)
    @JvmStatic external fun nativeCriticalTrueTargetPass(passButtonX: Float, passButtonY: Float, activeStrikerX: Float, activeStrikerY: Float, strikerVx: Float, strikerVy: Float, isLoftedContext: Boolean, screenWidth: Float, screenHeight: Float, outBuffer: FloatArray)


    // --- V58: Pure Math Engines Native Migration ---
// --- V59: Tactical & Compactness Pure Math Native Migration ---
    @JvmStatic external fun nativeTacticalAnalytics(tacticalMapConf: Float, compactnessConf: Float, compactnessVal: Float, wingConf: Float, wingOverloaded: Boolean, centralConf: Float, centralOverloaded: Boolean, pressingConf: Float, pressingDetected: Boolean, counterPressConf: Float, counterPressDetected: Boolean, buildUpConf: Float, buildUpDetected: Boolean, possessionConf: Float, possessionDetected: Boolean, outBuffer: FloatArray)
    @JvmStatic external fun nativeTacticalBehavior(analyticsConf: Float, formationConf: Float, formationFound: Boolean, teamShapeConf: Float, teamShapeCompactness: Float, outBuffer: FloatArray)
    @JvmStatic external fun nativeRuntimeConfidenceCalibration(tacticalConf: Float, formationConf: Float, passingConf: Float, shootingConf: Float, ema: Float, rollingMean: Float, temporalConf: Float, outBuffer: FloatArray)
    @JvmStatic external fun nativeDefensiveCompactness(fieldConf: Float, defLineConf: Float, teamShapeConf: Float, teamShapeWidth: Float, teamShapeDepth: Float, teamShapeCompactness: Float, screenW: Float, screenH: Float, outBuffer: FloatArray)


    // --- V60: Recognition & Overload Pure Math Native Migration ---
    @JvmStatic external fun nativeWingOverloadCompute(playerCount: Int, sceneConf: Float, fieldConf: Float, pressureRows: Int, pressureCols: Int, occupancyRows: Int, occupancyCols: Int, outBuffer: FloatArray)
    @JvmStatic external fun nativeCentralOverloadCompute(playerCount: Int, sceneConf: Float, fieldConf: Float, outBuffer: FloatArray)
    @JvmStatic external fun nativePressingRecognitionCompute(pressureRows: Int, pressureCols: Int, formationFound: Boolean, formationConf: Float, compactnessCompactness: Float, compactnessConf: Float, outBuffer: FloatArray)
    @JvmStatic external fun nativeCounterPressCompute(sceneConf: Float, hasPossession: Boolean, possessionChanged: Boolean, possessionConf: Float, pressureRows: Int, pressureCols: Int, outBuffer: FloatArray)
    /** Current native breadcrumb ring, oldest first; empty string when nothing recorded. */
    @JvmStatic external fun nativeBreadcrumbDump(): String
    @JvmStatic external fun nativeBuildUpRecognitionCompute(formationFound: Boolean, formationConf: Float, teamShapeConf: Float, lanesNotEmpty: Boolean, outBuffer: FloatArray)
    @JvmStatic external fun nativePossessionStyleCompute(hasPossession: Boolean, possessionFrames: Long, possessionConf: Float, lanesNotEmpty: Boolean, pressureRows: Int, pressureCols: Int, outBuffer: FloatArray)
@JvmStatic
    fun nativeComputeAgilityPhysics(
        playerVelocity: Float,
        opponentDistance: Float,
        movementAngleDegrees: Float,
        possessionConfidence: Float,
        turnIntensity: Float,
        playerX: Float, playerY: Float,
        oppX: Float, oppY: Float,
        outBuffer: FloatArray,
        nearUnits: Float, proxUnits: Float, softUnits: Float, farUnits: Float,
        maxVelocity: Float, shieldBase: Float, shieldProx: Float,
        shieldSpeed: Float, shieldConf: Float, durationBase: Float,
        confFloor: Float, distGuard: Float
    ): Boolean {
        // SPLENDOR_AGILITY_LIVE_TELEMETRY_V1 - forward real telemetry; report native readiness.
        if (!nativeReady.get()) {
            logFailureOnce()
            return false
        }
        nativeAgilityPhysicsImpl(
            playerVelocity, opponentDistance,
            movementAngleDegrees, possessionConfidence,
            turnIntensity, playerX, playerY, oppX, oppY, outBuffer,
            nearUnits, proxUnits, softUnits, farUnits,
            maxVelocity, shieldBase, shieldProx, shieldSpeed,
            shieldConf, durationBase, confFloor, distGuard
        )
        return true
    }

    @JvmStatic
    fun nativeComputeBallRetention(
        hasBall: Boolean,
        trusted: Boolean,
        confidence: Float,
        ballX: Float, ballY: Float,
        viableLaneCount: Int,
        laneCount: Int,
        passTargetX: Float, passTargetY: Float,
        zonesLeftTheirs: Int, zonesMidTheirs: Int, zonesRightTheirs: Int,
        defenderDensity: Float,
        resultBuffer: FloatArray
    ): Boolean {
        // SPLENDOR_BALL_RETENTION_REAL_TELEMETRY_V1 - forward real telemetry.
        // The previous wrapper hard-coded hasBall=true, trusted=true,
        // confidence=1.0f, viableLaneCount=3, laneCount=3 and zeroed every
        // zone, so the native layer ALWAYS returned res[0]=1.0f (active) and
        // fabricated a shield target from a zeroed zone vector. It also
        // returned true even when the library was not loaded.
        if (!nativeReady.get()) {
            logFailureOnce()
            return false
        }
        nativeBallRetentionShieldCompute(
            hasBall = hasBall, trusted = trusted, confidence = confidence,
            ballX = ballX, ballY = ballY,
            viableLaneCount = viableLaneCount, laneCount = laneCount,
            passTargetX = passTargetX, passTargetY = passTargetY,
            zonesLeftTheirs = zonesLeftTheirs, zonesMidTheirs = zonesMidTheirs,
            zonesRightTheirs = zonesRightTheirs,
            defenderDensity = defenderDensity,
            frameWidth = com.assistant.vision.CameraProfile.captureWidthOrFallback(),
            frameHeight = com.assistant.vision.CameraProfile.captureHeightOrFallback(),
            resultBuffer = resultBuffer
        )
        return true
    }

}
