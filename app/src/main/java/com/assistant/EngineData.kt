package com.assistant

import android.content.Intent
import java.lang.ref.WeakReference

/**
 * ENGINE DATA
 * 
 * A minimal, thread-safe global state holder for transient engine launch codes 
 * and intents. 
 * 
 * CRITICAL FOR 4GB RAM: Holds Intent via WeakReference to prevent Activity/Context 
 * memory leaks that trigger Low Memory Kills (LMK) during eFootball 2027 gameplay.
 */
object EngineData {
    @Volatile
    private var _code: Int = 0
    
    @Volatile
    private var _intentRef: WeakReference<Intent>? = null

    var code: Int
        get() = _code
        set(value) { _code = value }

    // UPGRADE: Intercept setter to wrap Intent in WeakReference, preventing 
    // strong reference memory leaks while preserving original get/set syntax.
    var intent: Intent?
        get() = _intentRef?.get()
        set(value) { 
            _intentRef = if (value != null) WeakReference(value) else null 
        }

    /**
     * Consumes and returns the intent, then immediately nullifies the reference 
     * to free memory for the Garbage Collector. Use this instead of direct 
     * property access when the intent is meant for one-time execution.
     */
    fun consumeIntent(): Intent? {
        val target = _intentRef?.get()
        _intentRef = null
        return target
    }

    /**
     * Explicitly clears all held references to prevent memory leaks 
     * when the engine is stopped or the app goes to the background.
     */
    fun clear() {
        _code = 0
        _intentRef = null
    }
}
