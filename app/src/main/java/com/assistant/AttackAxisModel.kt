package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*

import kotlin.math.abs


























object AttackAxisModel {
    @Volatile private var direction: Int = 0
    @Volatile private var confidence: Float = 0f
    private var previousBallX: Float = 0f
    private var previousFrame: Long = -1L
    private var previousBallValid: Boolean = false
    private var motionDirection: Int = 0
    private var motionStreak: Int = 0

    @Synchronized fun observe(scene: SceneSnapshot, userHasPossession: Boolean, screenWidth: Float) {
        var userX = 0f
        var userCount = 0
        val players = scene.trackedPlayers
        for (i in players.indices) {
            val player = players[i]
            if (player.isUserTeam && player.x.isFinite() && player.confidence >= 0.20f) {
                userX += player.x
                userCount++
            }
        }
        if (userCount > 0) userX /= userCount.toFloat()

        if (scene.goalDetected && scene.goalConfidence >= 0.45f &&
            scene.goalRightX > scene.goalLeftX && userCount > 0) {
            val goalX = (scene.goalLeftX + scene.goalRightX) * 0.5f
            val delta = goalX - userX
            val minimumSeparation = screenWidth * 0.035f
            if (delta.isFinite() && kotlin.math.abs(delta) >= minimumSeparation) {
                direction = if (delta > 0f) 1 else -1
                confidence = scene.goalConfidence.coerceIn(0f, 1f)
            }
        } else if (userHasPossession && scene.trackedBallVisible && userCount > 0) {
            val consecutive = previousBallValid && scene.frameNumber == previousFrame + 1L
            val delta = if (consecutive) scene.trackedBallX - previousBallX else 0f
            val minimumStep = (screenWidth * 0.003f).coerceAtLeast(3f)
            val candidate = when {
                delta >= minimumStep -> 1
                delta <= -minimumStep -> -1
                else -> 0
            }
            if (candidate != 0) {
                if (candidate == motionDirection) motionStreak++ else {
                    motionDirection = candidate
                    motionStreak = 1
                }
                val shapeSupports = candidate.toFloat() * (scene.trackedBallX - userX) >= -(screenWidth * 0.10f)
                if (motionStreak >= 3 && shapeSupports) {
                    direction = candidate
                    confidence = (0.55f + (motionStreak - 3).coerceAtMost(3) * 0.06f).coerceAtMost(0.73f)
                }
            } else if (consecutive) {
                motionStreak = 0
            }
        }

        previousBallValid = scene.trackedBallVisible
        previousBallX = scene.trackedBallX
        previousFrame = scene.frameNumber
    }

    fun directionIfConfident(): Int = if (confidence >= 0.62f) direction else 0
}

/* ========
ActiveAttackerEngine
======== */
