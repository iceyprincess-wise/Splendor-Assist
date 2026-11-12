package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class ActionVerification(
    val verified: Boolean,
    val detail: String,
    val verifiedAtMs: Long = System.currentTimeMillis()
)

/**
 * Verification is separate from execution.
 *
 * An action is never reported as a successful recovery merely because
 * the command was invoked.
 */
