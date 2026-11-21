package com.assistant






































import com.assistant.runtime.*


























import kotlin.math.*




























data class AdaptiveModeAuthority(
    val mode: Int
)

// Letter A item 14: shotScore / passScore / crossScore deleted. They
// were computed in GameplayDecisionEngine.selectVisionAdaptiveMode only
// to pick `mode` (the sole value any reader consumes —
// ActiveGestureController reads mode only), then stored and never read
// anywhere. The three scores still exist as locals at the selection
// site, where they are consumed by the mode decision itself; only the
// unread stored copies are removed. No mode value changes.

