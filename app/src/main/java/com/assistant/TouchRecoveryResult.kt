package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class TouchRecoveryResult(
    val recoveryBoost: Float
)
// No68-E2-D13/D17: the shield and balance fields are gone — they
// were always equal to each other (identical formula) and no caller
// anywhere read either of them; both call sites
// (TouchRecoveryContributor, ActiveGestureController) read
// recoveryBoost only.

