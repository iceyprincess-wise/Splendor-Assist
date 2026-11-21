package com.assistant

import android.content.Intent

/**
 * ENGINE DATA
 * 
 * A minimal, thread-safe global state holder for transient engine launch codes 
 * and intents. 
 * 
 * CRITICAL FOR RECOVERY: The MediaProjection authorization Intent holds the 
 * Binder token required to resume screen capture without user interaction. 
 * Holding this Intent via WeakReference causes it to be garbage collected 
 * under memory pressure (common on 4GB RAM devices during eFootball 2027). 
 * If collected, the MediaProjection token is permanently lost, forcing the 
 * user to manually re-authorize screen capture. Intents are lightweight 
 * Parcelable data containers and do not leak Activity/Context objects, so 
 * a strong reference is both safe and mandatory for background survival.
 */
object EngineData {
    @Volatile
    private var _code: Int = 0
    
    @Volatile
    private var _intent: Intent? = null

    var code: Int
        get() = _code
        set(value) { _code = value }

    var intent: Intent?
        get() = _intent
        set(value) { 
            _intent = value
        }
}
