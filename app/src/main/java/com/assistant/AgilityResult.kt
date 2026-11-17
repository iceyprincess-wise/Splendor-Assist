package com.assistant

import com.assistant.runtime.*

// AgilityResult is LIVE, not dead: ActiveGestureController is its only
// producer (it builds one from the native 10-float output, or a neutral
// all-zero one when the native library is unavailable) and reads all ten
// fields back (shieldActive, shieldAngleDegrees and shieldDurationMs for
// the shield gesture routing; stabilityBoost, controlRetentionBoost and
// turnAssist for the gesture magnitude; escapeAngleDegrees and
// burstIntensity for the V4 burst-exit routing; threatLevel for the
// arbitration stability sum; predictedDist for the burst log line). The
// engine itself moved to NativeBridge (native_agility_physics.c); this
// class is the Kotlin-side carrier for its output, not the engine.
//
// V4 (SPLENDOR_AGILITY_UNITS_V4): the native outBuffer grew from 6 to 10
// floats. The four new fields are the V4 outputs: escapeAngleDegrees
// (tangential burst-dribble exit vector), burstIntensity (knock-on /
// sprint exit urgency), threatLevel (combined proximity + closing-speed
// threat) and predictedDist (projected defender distance at leadTime).
data class AgilityResult(
    val shieldActive: Boolean,
    val stabilityBoost: Float,
    val controlRetentionBoost: Float,
    val turnAssist: Float,
    val shieldAngleDegrees: Float,
    val shieldDurationMs: Long,
    val escapeAngleDegrees: Float,
    val burstIntensity: Float,
    val threatLevel: Float,
    val predictedDist: Float
)

/* AgilityEngine moved to NativeBridge */
