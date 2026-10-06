#!/usr/bin/env python3
import sys
import re
from pathlib import Path

def patch_file(filepath, replacements):
    path = Path(filepath)
    if not path.exists():
        print(f"FAIL: File missing {filepath}")
        sys.exit(1)

    content = path.read_text(encoding="utf-8")
    original = content

    for old, new in replacements:
        if old not in content:
            print(f"FAIL: Pattern not found in {filepath}:\n{old[:100]}...")
            sys.exit(1)
        content = content.replace(old, new, 1)

    if content == original:
        print(f"FAIL: No changes made to {filepath}")
        sys.exit(1)

    path.write_text(content, encoding="utf-8")
    print(f"PASS: Successfully patched {filepath}")

def main():
    print("=== APPLYING F2.PY PATCH MUTATIONS ===")

    # 1. OverlayService.kt - Vision Pipeline Concurrency Fix
    overlay_service = "app/src/main/java/com/assistant/OverlayService.kt"
    overlay_replacements = [
        (
            """            // V38 LOOP_FROZEN FIX: Force-kill stuck vision coroutines and unblock visionInFlight
            visionScope.coroutineContext.cancelChildren()
            visionInFlight.set(false)""",
            """            // V38 LOOP_FROZEN FIX: Force-kill stuck vision coroutines safely without race condition
            visionScope.coroutineContext.cancelChildren()
            // Do NOT prematurely force visionInFlight = false while background coroutine is still reading/assembling
            // visionInFlight resets cleanly in the coroutine finally block"""
        )
    ]
    patch_file(overlay_service, overlay_replacements)

    # 2. CameraProfile.kt - Dynamic Wide Geometry Scaling
    camera_profile = "app/src/main/java/com/assistant/vision/CameraProfile.kt"
    camera_replacements = [
        (
            """    // SPLENDOR_V23A_CAMERA_HELPERS_BEGIN
    @JvmStatic
    fun captureWidthOrFallback(fallback: Float = 1650f): Float =
        if (captureWidth > 0) captureWidth.toFloat() else fallback

    @JvmStatic
    fun captureHeightOrFallback(fallback: Float = 720f): Float =
        if (captureHeight > 0) captureHeight.toFloat() else fallback
    // SPLENDOR_V23A_CAMERA_HELPERS_END""",
            """    // SPLENDOR_V23A_CAMERA_HELPERS_BEGIN
    @JvmStatic
    fun captureWidthOrFallback(fallback: Float = 1650f): Float =
        if (isDynamicWide()) fallback else (if (captureWidth > 0) captureWidth.toFloat() else fallback)

    @JvmStatic
    fun captureHeightOrFallback(fallback: Float = 720f): Float =
        if (isDynamicWide()) fallback else (if (captureHeight > 0) captureHeight.toFloat() else fallback)
    // SPLENDOR_V23A_CAMERA_HELPERS_END"""
        )
    ]
    patch_file(camera_profile, camera_replacements)

    # 3. gameplay_engine.kt - Remove FrameAssemblerPool, Add ConAIEngine, ActionOutcomeVerifier, & Fix MOVE Arbitration
    gameplay_engine = "app/src/main/java/com/assistant/gameplay_engine.kt"
    gameplay_replacements = [
        (
            """object FrameAssemblerPool {
    val list = java.util.ArrayList<com.assistant.TrackedPlayer>(22)
}

object FrameAssembler {""",
            """object FrameAssembler {"""
        ),
        (
            """    private fun classScale(actionClass: ActionClass): Float =
        when (actionClass) {
            ActionClass.MOVE ->
                1.0f // UPGRADE: Movement must compete equally in arbitration to prevent magnetic starvation
            ActionClass.NONE -> 0f
            else -> 1f
        }""",
            """    private fun classScale(actionClass: ActionClass, hasTactical: Boolean = false): Float =
        when (actionClass) {
            ActionClass.MOVE -> if (hasTactical) 0.35f else 0.85f
            ActionClass.NONE -> 0f
            else -> 1f
        }"""
        ),
        (
            """        // Zero-alloc manual loop for filtering and max arbitration
        var best: EngineContribution? = null
        var bestScore = -1f
        for (c in contributions) {
            if (netHold && c.actionClass != ActionClass.MOVE && c.actionClass != ActionClass.DEFEND) continue
            val score = c.weight * classScale(c.actionClass)
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }""",
            """        // ConAI Meta-Arbiter decision loop
        val best = ConAIEngine.arbitrate(frame, contributions, netHold)"""
        ),
        (
            """        val accepted = HybridExecutionTerminal.route(terminalRequest)
        if (accepted) {
            routed.incrementAndGet()
            ControlMappingTrainer.recordDispatch(terminalRequest.phase, terminalRequest.duration)
            try {
                com.assistant.events.GameplayEventHub.emit(
                    "routed",
                    "source=${request.source} phase=${request.phase}"
                )
            } catch (_: Throwable) {
            }
        }""",
            """        val accepted = HybridExecutionTerminal.route(terminalRequest)
        if (accepted) {
            routed.incrementAndGet()
            ControlMappingTrainer.recordDispatch(terminalRequest.phase, terminalRequest.duration)
            ActionOutcomeVerifier.recordDispatch(terminalRequest, frame)
            try {
                com.assistant.events.GameplayEventHub.emit(
                    "routed",
                    "source=${request.source} phase=${request.phase}"
                )
            } catch (_: Throwable) {
            }
        }
        ActionOutcomeVerifier.verify(frame)"""
        ),
        (
            """/* ========
ActionVerifier
======== */""",
            """/* ========
ActionOutcomeVerifier
======== */
object ActionOutcomeVerifier {
    @Volatile private var pendingDispatch: ExecutionRequest? = null
    @Volatile private var pendingFrame: RuntimeFrame? = null
    @Volatile private var dispatchTimestamp: Long = 0L

    private val totalDispatched = AtomicLong(0L)
    private val effectObservedCount = AtomicLong(0L)
    private val effectNotObservedCount = AtomicLong(0L)

    fun recordDispatch(request: ExecutionRequest, frame: RuntimeFrame) {
        pendingDispatch = request
        pendingFrame = frame
        dispatchTimestamp = System.currentTimeMillis()
        totalDispatched.incrementAndGet()
    }

    fun verify(currentFrame: RuntimeFrame) {
        val req = pendingDispatch ?: return
        val prevFrame = pendingFrame ?: return
        val age = currentFrame.timestampMs - dispatchTimestamp

        if (age < 30L) return
        if (age > 350L) {
            effectNotObservedCount.incrementAndGet()
            pendingDispatch = null
            pendingFrame = null
            return
        }

        val observed = when (req.phase) {
            ActionClass.PASS.ordinal -> {
                val dx = currentFrame.ballX - prevFrame.ballX
                val dy = currentFrame.ballY - prevFrame.ballY
                kotlin.math.hypot(dx, dy) > 10f
            }
            ActionClass.SHOT.ordinal -> {
                currentFrame.goalDetected || kotlin.math.hypot(currentFrame.ballVelocityX, currentFrame.ballVelocityY) > 15f
            }
            ActionClass.CROSS.ordinal -> {
                kotlin.math.hypot(currentFrame.ballX - prevFrame.ballX, currentFrame.ballY - prevFrame.ballY) > 20f
            }
            ActionClass.DEFEND.ordinal, ActionClass.KEEPER.ordinal -> {
                currentFrame.defenderDensity <= prevFrame.defenderDensity || !currentFrame.panic
            }
            ActionClass.MOVE.ordinal, ActionClass.EVADE.ordinal -> {
                kotlin.math.hypot(currentFrame.ballX - prevFrame.ballX, currentFrame.ballY - prevFrame.ballY) > 3f
            }
            else -> true
        }

        if (observed) {
            effectObservedCount.incrementAndGet()
        } else {
            effectNotObservedCount.incrementAndGet()
        }

        pendingDispatch = null
        pendingFrame = null
    }

    fun diagnostics(): Map<String, Any> = mapOf(
        "dispatched" to totalDispatched.get(),
        "effectObserved" to effectObservedCount.get(),
        "effectNotObserved" to effectNotObservedCount.get()
    )
}

/* ========
ConAIEngine Meta-Arbiter
======== */
object ConAIEngine {
    private val metaDecisions = AtomicLong(0L)
    private val metaOverrides = AtomicLong(0L)

    fun arbitrate(
        frame: RuntimeFrame,
        candidates: List<EngineContribution>,
        netHold: Boolean
    ): EngineContribution? {
        if (candidates.isEmpty()) return null
        metaDecisions.incrementAndGet()

        val validCandidates = candidates.filter { c ->
            !(netHold && c.actionClass != ActionClass.MOVE && c.actionClass != ActionClass.DEFEND)
        }
        if (validCandidates.isEmpty()) return null

        val tacticalCandidates = validCandidates.filter {
            it.actionClass != ActionClass.MOVE && it.actionClass != ActionClass.NONE
        }

        if (frame.hasBall && tacticalCandidates.isNotEmpty()) {
            val bestTactical = tacticalCandidates.maxByOrNull { it.weight * it.authority }
            if (bestTactical != null && bestTactical.authority >= 0.30f) {
                metaOverrides.incrementAndGet()
                return bestTactical
            }
        }

        if (!frame.hasBall) {
            val defCandidates = validCandidates.filter {
                it.actionClass == ActionClass.DEFEND || it.actionClass == ActionClass.KEEPER
            }
            val bestDef = defCandidates.maxByOrNull { it.weight * it.authority }
            if (bestDef != null && bestDef.authority >= 0.25f) {
                return bestDef
            }
        }

        val hasTactical = tacticalCandidates.isNotEmpty()
        return validCandidates.maxByOrNull { c ->
            val scale = if (c.actionClass == ActionClass.MOVE && hasTactical) 0.35f else 1.0f
            c.weight * c.authority * scale
        }
    }

    fun diagnostics(): Map<String, Any> = mapOf(
        "metaDecisions" to metaDecisions.get(),
        "metaOverrides" to metaOverrides.get()
    )
}

/* ========
ActionVerifier
======== */"""
        )
    ]
    patch_file(gameplay_engine, gameplay_replacements)

    print("=== ALL F2.PY PATCH MUTATIONS APPLIED SUCCESSFULLY ===")

if __name__ == "__main__":
    main()
