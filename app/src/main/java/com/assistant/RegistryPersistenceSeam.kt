package com.assistant

import android.content.Context

/**
 * L15 — Registry Persistence Seam (RegistryPersistenceSeam).
 *
 * Gap (Q68-97): VisionConfiguration toggles were lost on restart — no
 * registry persisted today.
 *
 * Contract: in = registry toggle states; out = persisted states restored
 * on restart; caller = App.onCreate (restore) / VisionConfigurationEngine
 * (save on every update/reset); consumer = the registries (VisionConfiguration
 * first; the seam is generic so other registries can adopt it).
 *
 * Platform, decision-neutral: it only restores the user's own saved
 * settings; no decision logic changes. Storage is SharedPreferences
 * ("vision_config"): apply() (async, never blocks the caller) on save,
 * a synchronous read on restore at startup. The pure map conversion
 * (toMap/fromMap) is the harness-testable core: fromMap coerces every
 * value defensively (a corrupt stored value falls back to the default
 * for that key, never throws, never invents a toggle state) and clamps
 * blobThreshold into its 0..1 contract.
 */
object RegistryPersistenceSeam {

    const val PREFS_NAME = "vision_config"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_DEBUG_OVERLAY = "debugOverlayEnabled"
    private const val KEY_BOUNDING_BOX_OVERLAY = "boundingBoxOverlayEnabled"
    private const val KEY_CONFIDENCE_HEATMAP = "confidenceHeatmapEnabled"
    private const val KEY_BALL_OVERLAY = "ballOverlayEnabled"
    private const val KEY_PLAYER_OVERLAY = "playerOverlayEnabled"
    private const val KEY_GOAL_OVERLAY = "goalOverlayEnabled"
    private const val KEY_BLOB_THRESHOLD = "blobThreshold"
    private const val KEY_LATENCY_MONITORING = "latencyMonitoringEnabled"
    private const val KEY_FPS_MONITORING = "fpsMonitoringEnabled"

    /** Pure conversion: config -> flat map (one entry per toggle). */
    fun toMap(config: VisionConfiguration): Map<String, Any> = mapOf(
        KEY_ENABLED to config.enabled,
        KEY_DEBUG_OVERLAY to config.debugOverlayEnabled,
        KEY_BOUNDING_BOX_OVERLAY to config.boundingBoxOverlayEnabled,
        KEY_CONFIDENCE_HEATMAP to config.confidenceHeatmapEnabled,
        KEY_BALL_OVERLAY to config.ballOverlayEnabled,
        KEY_PLAYER_OVERLAY to config.playerOverlayEnabled,
        KEY_GOAL_OVERLAY to config.goalOverlayEnabled,
        KEY_BLOB_THRESHOLD to config.blobThreshold,
        KEY_LATENCY_MONITORING to config.latencyMonitoringEnabled,
        KEY_FPS_MONITORING to config.fpsMonitoringEnabled
    )

    private fun asBoolean(value: Any?, fallback: Boolean): Boolean = when (value) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull() ?: fallback
        is Number -> value.toInt() != 0
        else -> fallback
    }

    private fun asFloat(value: Any?, fallback: Float): Float = when (value) {
        is Number -> value.toFloat()
        is String -> value.toFloatOrNull() ?: fallback
        else -> fallback
    }

    /** Pure conversion: flat map -> config. Unknown/missing keys keep the fallback's value. */
    fun fromMap(values: Map<String, Any?>, fallback: VisionConfiguration = VisionConfiguration()): VisionConfiguration =
        VisionConfiguration(
            enabled = asBoolean(values[KEY_ENABLED], fallback.enabled),
            debugOverlayEnabled = asBoolean(values[KEY_DEBUG_OVERLAY], fallback.debugOverlayEnabled),
            boundingBoxOverlayEnabled = asBoolean(values[KEY_BOUNDING_BOX_OVERLAY], fallback.boundingBoxOverlayEnabled),
            confidenceHeatmapEnabled = asBoolean(values[KEY_CONFIDENCE_HEATMAP], fallback.confidenceHeatmapEnabled),
            ballOverlayEnabled = asBoolean(values[KEY_BALL_OVERLAY], fallback.ballOverlayEnabled),
            playerOverlayEnabled = asBoolean(values[KEY_PLAYER_OVERLAY], fallback.playerOverlayEnabled),
            goalOverlayEnabled = asBoolean(values[KEY_GOAL_OVERLAY], fallback.goalOverlayEnabled),
            blobThreshold = asFloat(values[KEY_BLOB_THRESHOLD], fallback.blobThreshold).coerceIn(0f, 1f),
            latencyMonitoringEnabled = asBoolean(values[KEY_LATENCY_MONITORING], fallback.latencyMonitoringEnabled),
            fpsMonitoringEnabled = asBoolean(values[KEY_FPS_MONITORING], fallback.fpsMonitoringEnabled)
        )

    /** Persists the config. Async apply: never blocks the caller. */
    fun save(context: Context, config: VisionConfiguration) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            for ((key, value) in toMap(config)) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                }
            }
            editor.apply()
        } catch (_: Throwable) { }
    }

    /** Restores the config, or null when nothing was ever saved. */
    fun load(context: Context): VisionConfiguration? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.all.isEmpty()) return null
            val values = prefs.all.mapValues { it.value }
            fromMap(values)
        } catch (_: Throwable) {
            null
        }
    }
}
