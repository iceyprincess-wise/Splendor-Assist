package com.assistant.adapter.net

import android.content.Context
import android.telephony.TelephonyManager
import com.assistant.diagnostic.RuntimeLogger

data class CarrierProfile(
    val name: String,
    val expectedRttMs: Int,
    val keepAliveSeconds: Int,
    val jitterToleranceMs: Int
)

/** Detects the live carrier and selects its tuned profile (no extra permission needed). */
object CarrierProfileEngine {

    private val MTN     = CarrierProfile("MTN", 65, 8, 25)
    private val AIRTEL  = CarrierProfile("AIRTEL", 75, 10, 30)
    private val GENERIC = CarrierProfile("GENERIC", 90, 12, 40)
    val WIFI            = CarrierProfile("WIFI", 40, 15, 15)

    @Volatile var current: CarrierProfile = GENERIC
        private set

    fun detect(ctx: Context): CarrierProfile {
        val op = try {
            (ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
                ?.networkOperatorName ?: ""
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
