package com.assistant.adapter.net

import android.content.Context
import android.telephony.TelephonyManager
import com.assistant.admin.AdminConfigStore
import com.assistant.diagnostic.RuntimeLogger

data class CarrierProfile(
    val name: String,
    val expectedRttMs: Int,
    val keepAliveSeconds: Int,
    val jitterToleranceMs: Int
)

/**
 * Detects the live carrier and selects its tuned profile (no extra permission
 * needed). V3: the admin can OVERRIDE the baseline ping, wobble allowance and
 * keepalive rhythm from the admin panel (0 = automatic, follow the detected
 * carrier). Every engine reads the override-aware values below, so an admin
 * override takes effect stack-wide on the next tick.
 */
object CarrierProfileEngine {

    private val MTN     = CarrierProfile("MTN", 65, 8, 25)
    private val AIRTEL  = CarrierProfile("AIRTEL", 75, 10, 30)
    private val GENERIC = CarrierProfile("GENERIC", 90, 12, 40)
    val WIFI            = CarrierProfile("WIFI", 40, 15, 15)

    @Volatile var current: CarrierProfile = GENERIC
        private set

    // ---- override-aware baselines (0 = auto: use the detected profile) ----
    val baselineRttMs: Int get() {
        val o = AdminConfigStore.getInt("net.profile.rtt_ms", 0)
        return if (o > 0) o else current.expectedRttMs
    }
    val jitterTolMs: Int get() {
        val o = AdminConfigStore.getInt("net.profile.jitter_tol_ms", 0)
        return if (o > 0) o else current.jitterToleranceMs
    }
    val keepAliveS: Int get() {
        val o = AdminConfigStore.getInt("net.profile.keepalive_s", 0)
        return if (o > 0) o else current.keepAliveSeconds
    }

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
