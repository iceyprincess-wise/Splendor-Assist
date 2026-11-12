package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*

import kotlin.math.abs











import kotlin.math.sqrt














object ActiveDefenderEngine {

    // --- AMPLIFIED INPUT EFFECTIVENESS CONSTANTS ---
    // Synchronized to 60/120Hz refresh boundaries for absolute injection precision
    private const val BASE_TICK_RATE_MS = 16L
    private const val SERVER_TICK_COMPENSATION = 1.05f
    
    private const val MAX_TRACKING_DISTANCE = 1200.0f
    
    // Predictive look-ahead scaling based on packet transmission boundaries
    private const val PREDICTION_FRAMES = 3

    // --- INTERNAL ENGINE STATE ---
    private var frameCounter = 0L
    private var lastAttackerX = -1f
    private var lastAttackerY = -1f


    /**
     * Computes the optimal defender to track an attacker using 
     * OMEGA-upgraded predictive vector math and dynamic server-tick scaling.
     */
    fun compute(
        scene: SceneSnapshot,
        attacker: ActiveAttackerResult
    ): ActiveDefenderResult {
        if (!attacker.found || attacker.attacker == null) {
            // Reset trajectory state if no active attacker is present
            lastAttackerX = -1f
            lastAttackerY = -1f
            return ActiveDefenderResult(found = false)
        }

        frameCounter++
        val attackerX = attacker.attacker.x
        val attackerY = attacker.attacker.y
        val attackerTeam = attacker.attacker.isUserTeam

        // --- DYNAMIC VECTOR MAPPING & MOMENTUM CALCULATION ---
        val velX = if (lastAttackerX != -1f) attackerX - lastAttackerX else 0f
        val velY = if (lastAttackerY != -1f) attackerY - lastAttackerY else 0f
        
        lastAttackerX = attackerX
        lastAttackerY = attackerY

        // Predictive intercept coordinate based on server tick delay
        val predictedAttackerX = attackerX + (velX * PREDICTION_FRAMES * SERVER_TICK_COMPENSATION)
        val predictedAttackerY = attackerY + (velY * PREDICTION_FRAMES * SERVER_TICK_COMPENSATION)

        var optimalDefenderIndex = -1
        var minimalEffectiveScore = Float.MAX_VALUE


        for (index in scene.trackedPlayers.indices) {
            if (index == attacker.attackerIndex) continue
            val player = scene.trackedPlayers[index]
            
            // We only track players of the opposing team
            if (player.isUserTeam == attackerTeam) continue

            // Compute relative offset to the *predicted* location
            val dx = player.x - predictedAttackerX
            val dy = player.y - predictedAttackerY

            // Fast culling using Manhattan distance to eliminate heavy sqrt calls per frame
            val manhattanDist = abs(dx) + abs(dy)
            if (manhattanDist > MAX_TRACKING_DISTANCE * 1.5f) continue

            // Precise Euclidean distance calculation
            val rawDistance = sqrt(dx * dx + dy * dy)
            if (rawDistance >= MAX_TRACKING_DISTANCE) continue

            // SERVER-TICK SYNC & EFFECTIVENESS SCORING
            val confidenceWeight = 1.0f - (player.confidence * 0.015f)
            val effectiveScore = (rawDistance * SERVER_TICK_COMPENSATION) * confidenceWeight

            if (effectiveScore < minimalEffectiveScore) {
                minimalEffectiveScore = effectiveScore
                optimalDefenderIndex = index
            }
        }

        return if (optimalDefenderIndex >= 0) {
            val optimalDefender = scene.trackedPlayers[optimalDefenderIndex]
            
            // Recalculate true physical distance without predictive/noise distortion 
            // to feed pristine physical logic back to the client injector
            val trueDx = optimalDefender.x - attackerX
            val trueDy = optimalDefender.y - attackerY
            
            ActiveDefenderResult(
                found = true,
                defender = optimalDefender,
                defenderIndex = optimalDefenderIndex,
                distanceToAttacker = sqrt(trueDx * trueDx + trueDy * trueDy),
                confidence = optimalDefender.confidence
            )
        } else {
            ActiveDefenderResult(found = false)
        }
    }
}

/* ========
ActiveDefenderResult
======== */
