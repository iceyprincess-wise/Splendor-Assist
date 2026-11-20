package com.assistant







import android.content.Context













import android.os.Build










import android.telephony.TelephonyManager



import com.assistant.diagnostic.RuntimeLogger






























object CarrierProfileEngine {

    private val MTN     = CarrierProfile("MTN", 65, 8, 25)
    private val AIRTEL  = CarrierProfile("AIRTEL", 75, 10, 30)
    private val GENERIC = CarrierProfile("GENERIC", 90, 12, 40)
    val WIFI            = CarrierProfile("WIFI", 40, 15, 15)

    @Volatile var current: CarrierProfile = GENERIC
        private set

    // ---- baselines (auto: from the detected profile) ----
    // Batch 6d: the old getters claimed an admin override (a hardcoded
    // zero with never-true branches) but no admin panel or preference ever
    // wrote an override — the branches were dead code masquerading as a
    // feature. The getters now delegate to the detected profile directly;
    // behaviour is identical (o was always 0). If an admin override is
    // ever wanted, it needs a real preference + writer first.
    val baselineRttMs: Int get() = current.expectedRttMs
    val jitterTolMs: Int get() = current.jitterToleranceMs
    val keepAliveS: Int get() = current.keepAliveSeconds

    fun detect(ctx: Context): CarrierProfile {
        val op = try {
            // PHASE4 FIX: dual-SIM bug — networkOperatorName returns PRIMARY SIM (MTN slot 1)
            // even when active DATA SIM is different (Airtel slot 2).
            // Log-proven: user on Airtel, but carrier profile logged as MTN every session.
            // Fix: read operator from the explicit DATA subscription ID.
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val dataTm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    val subId = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
                    if (subId > 0) tm?.createForSubscriptionId(subId) else tm
                } catch (_: Throwable) { tm }
            } else tm
            dataTm?.networkOperatorName ?: ""
        } catch (_: Throwable) { "" }
        current = when {
            op.contains("MTN", true)    -> MTN
            op.contains("AIRTEL", true) -> AIRTEL
            op.isBlank()                -> GENERIC
            else                        -> GENERIC.copy(name = op.uppercase())
        }
        RuntimeLogger.log("Carrier: " + op + " -> profile " + current.name +
            " baseline=" + current.expectedRttMs + "ms", "NET")
        return current
    }

    fun useWifiProfile() { current = WIFI }
}

/* ========
CongestionSentinelEngine
======== */
// V3 INSTANT-REFLEX

/**
 * V2: reports BOTH edges. Onset warns before lag is felt; recovery (with how
 * long the dirty window lasted) tells the runtime when aggression is safe again.
 * V3: reads the jitter tolerance from the detected carrier profile
 * (CarrierProfileEngine.jitterTolMs), so a carrier change re-tunes congestion
 * detection on the next poll. (Batch 6d: the old doc claimed an admin baseline
 * override re-tunes it — no such override exists.)
 */
