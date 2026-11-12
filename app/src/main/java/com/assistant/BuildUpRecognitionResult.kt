package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class BuildUpRecognitionResult(
    val detected:Boolean=false,
    val confidence:Float=0f
)

/* ========
CaptaincySkillEngine
======== */
/**
 * CaptaincySkillEngine
 *
 * Models the documented eFootball Captaincy skill as a team-wide
 * fatigue reduction signal.
 *
 * VERIFIED GAMEPLAY BASIS (eFootball 2027):
 * "Captaincy: Reduces the effects of fatigue (entire squad)."
 * One nominated captain provides a persistent team-wide lift: reducing
 * fatigue penalties on accuracy, composure, and defensive organisation
 * for all 11 players throughout the match.
 *
 * IMPORTANT:
 * This engine does not claim to reproduce KONAMI's private server-side
 * fatigue model. RuntimeFrame provides only the local signals available
 * to this application. The engine models the observable downstream
 * effects: composed gestures, sustained decision quality under pressure,
 * and maintained team shape.
 *
 * DESIGN:
 * - Passive, continuous effect — no burst trigger, no cooldown;
 * - Fatigue proxied from: defenderDensity, panic state, frame confidence;
 * - Captain lift grows as fatigue proxy grows (needed most when tired);
 * - Composure bonus (+0..+8ms) applied to gesture duration;
 * - Panic amplifies lift to 1.20x (captain steadies the squad in crisis);
 * - Logs every 30 activations to avoid RuntimeLogger flooding;
 * - Trusted frames required.
 */
