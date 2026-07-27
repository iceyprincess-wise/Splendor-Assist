package com.assistant.adapter.memory

import android.app.ActivityManager
import android.content.Context
import com.assistant.diagnostic.RuntimeLogger

/**
 * AGGRESSIVE MEMORY HOARDING ENGINE
 * Ruthlessly terminates background processes and forces Garbage Collection
 * to ensure the foreground application (like a game) has maximum RAM allocation.
 */
object AggressiveMemoryHoarding {

    /**
     * Executes the hoarding sequence. 
     * REQUIRES: android.permission.KILL_BACKGROUND_PROCESSES in AndroidManifest.xml
     */
    @JvmStatic
    fun executePurge(context: Context) {
        RuntimeLogger.log("Initiating Aggressive Memory Purge...", "MEMORY_HOARDER")
        
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningApps = activityManager.runningAppProcesses
        
        if (runningApps != null) {
            val myPid = android.os.Process.myPid()
            var killedCount = 0
            
            for (app in runningApps) {
                // Do not kill ourselves or the absolute foreground UI
                if (app.pid != myPid && app.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                    app.pkgList.forEach { pkg ->
                        try {
                            activityManager.killBackgroundProcesses(pkg)
                            killedCount++
                        } catch (e: Exception) {
                            // Silently ignore security exceptions if a system package cannot be killed
                        }
                    }
                }
            }
            RuntimeLogger.log("Purged $killedCount background packages.", "MEMORY_HOARDER")
        }
        
        // Force GC on an IO Thread to reclaim the memory freed by killing processes instantly
        Thread {
            System.gc()
            System.runFinalization()
            
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val availMb = memoryInfo.availMem / (1024 * 1024)
            
            RuntimeLogger.log("Purge Complete. Available Memory Hoarded: ${availMb}MB", "MEMORY_HOARDER")
        }.start()
    }
}
