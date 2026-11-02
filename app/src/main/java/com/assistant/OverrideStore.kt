package com.assistant

import android.content.Context

/** Persistent AUTO/FORCE_ON/FORCE_OFF runtime overrides. Writes use commit(). */
object OverrideStore {
    enum class Mode { AUTO, FORCE_ON, FORCE_OFF }
    private const val PREFS = "splendor_runtime_overrides"
    private const val GAME_KEY = "game_mode"
    private const val TRUST_KEY = "trust_mode"
    @Volatile private var prefs: android.content.SharedPreferences? = null

    @JvmStatic fun initialize(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
    private fun store(): android.content.SharedPreferences? = prefs
    @JvmStatic fun mode(flag: String): Mode {
        val key = if (flag.equals("GAME", true)) GAME_KEY else TRUST_KEY
        return try { Mode.valueOf(store()?.getString(key, Mode.AUTO.name) ?: Mode.AUTO.name) }
            catch (_: Throwable) { Mode.AUTO }
    }
    @JvmStatic fun setMode(flag: String, mode: Mode): Boolean {
        val key = if (flag.equals("GAME", true)) GAME_KEY else TRUST_KEY
        return store()?.edit()?.putString(key, mode.name)?.commit() ?: false
    }
    @JvmStatic fun resolve(flag: String, automatic: Boolean): Boolean = when (mode(flag)) {
        Mode.AUTO -> automatic
        Mode.FORCE_ON -> true
        Mode.FORCE_OFF -> false
    }
    @JvmStatic fun modeLabel(flag: String): String = when (mode(flag)) {
        Mode.AUTO -> "AUTO"
        Mode.FORCE_ON -> "FORCE_ON"
        Mode.FORCE_OFF -> "FORCE_OFF"
    }
}
