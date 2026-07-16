package com.assistant.adapter.smartassist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log

object ActiveAttackerEngine {

    // Upgraded version called by your active gameplay loop to apply hardware anchors
    fun compute(
        service: AccessibilityService,
        currentX: Float,
        currentY: Float,
        scene: SceneSnapshot,
        possession: BallPossessionResult
    ): ActiveAttackerResult {

        if (!possession.hasPossession) {
            return ActiveAttackerResult(found = false)
        }

        val index = possession.ownerIndex
        if (index !in scene.trackedPlayers.indices) {
            return ActiveAttackerResult(found = false)
        }

        val player = scene.trackedPlayers[index]
        val result = ActiveAttackerResult(
            found = true,
            attacker = player,
            attackerIndex = index,
            confidence = possession.confidence
        )

        // UPGRADE: Inject a physical hardware stabilization anchor when attacking possession is verified
        if (result.found && result.confidence > 0.70f) {
            try {
                val path = Path().apply {
                    moveTo(currentX, currentY)
                    // Apply a precision tracking micro-offset to smooth input registration during runs
                    lineTo(currentX + 0.5f, currentY + 0.5f)
                }
                val strokeDescription = GestureDescription.StrokeDescription(path, 0L, 15L)
                val gestureDescription = GestureDescription.Builder().addStroke(strokeDescription).build()
                
                // Dispatch input acceleration to the Android interface compositor layer
                service.dispatchGesture(gestureDescription, null, null)
            } catch (e: Exception) {
                Log.e("ActiveAttacker", "Attacking gesture stabilization skipped: ${e.message}")
            }
        }

        return result
    }

    // Legacy fallback version to prevent compilation breakage inside standard metrics tracking components
    fun compute(
        scene: SceneSnapshot,
        possession: BallPossessionResult
    ): ActiveAttackerResult {
        if (!possession.hasPossession) return ActiveAttackerResult(found = false)
        val index = possession.ownerIndex
        if (index !in scene.trackedPlayers.indices) return ActiveAttackerResult(found = false)
        val player = scene.trackedPlayers[index]
        return ActiveAttackerResult(found = true, attacker = player, attackerIndex = index, confidence = possession.confidence)
    }
}
