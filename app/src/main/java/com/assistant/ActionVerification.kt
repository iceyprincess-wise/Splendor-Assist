package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class ActionVerification(
    val verified: Boolean,
    val detail: String
)

// Letter A item 5: the verification-timestamp field deleted —
// stamped at construction but never read anywhere. The verification
// is produced and consumed synchronously inside one InAppAgentCore
// tick (verify → lastVerification → snapshot/log), so no
// staleness/age check can ever observe a nonzero age; a reader would
// be dead code by construction.

/**
 * Verification is separate from execution.
 *
 * An action is never reported as a successful recovery merely because
 * the command was invoked.
 */
