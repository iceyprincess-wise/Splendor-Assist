package com.assistant.diagnostic.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import com.assistant.diagnostic.RuntimeLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * SINGLE NOTIFICATION FOR ALL FOUNDATION NODES (Task C item (c)).
 *
 * Before: every adapter service posted its own foreground notification
 * ("Splendor Input Node", "Splendor Memory Node", "Splendor LMK Node", ...)
 * on its own channel with its own ID - a drawer full of node rows, and one
 * proven ID collision (input + memory both on 9993) where the second
 * startForeground silently replaced the first and stopping either service
 * could strip the other's foreground protection (LMK bait on a 4GB device).
 *
 * Now: adapter services attach to ONE shared channel and ONE shared row.
 * Android allows multiple foreground services in the same app to bind the
 * same notification ID; the row simply reports how many nodes are active.
 * Detach uses STOP_FOREGROUND_DETACH so one node's shutdown never tears
 * down the shared row that other still-running nodes depend on - the exact
 * failure mode the old per-node IDs risked.
 *
 * Clarity preserved from the review: separate node notifications never
 * meant the adapters weren't cooperating - they already share data through
 * the registries. This consolidates the user-facing surface only.
 */
object NodeNotificationHub {

    private const val CHANNEL_ID = "splendor_nodes"
    private const val NOTIFICATION_ID = 9990

    private val activeNodes: MutableSet<String> =
        ConcurrentHashMap.newKeySet()

    fun attach(service: Service, nodeName: String) {
        activeNodes.add(nodeName)
        ensureChannel(service)
        service.startForeground(NOTIFICATION_ID, build(service))
        RuntimeLogger.log(
            "Node attached to unified notification: $nodeName (${activeNodes.size} active)",
            "NOTIFICATION"
        )
    }

    fun detach(service: Service, nodeName: String) {
        activeNodes.remove(nodeName)
        // DETACH keeps the shared row alive for the other attached services.
        service.stopForeground(Service.STOP_FOREGROUND_DETACH)
        val nm = service.getSystemService(NotificationManager::class.java)
        if (activeNodes.isEmpty()) {
            nm?.cancel(NOTIFICATION_ID)
        } else {
            nm?.notify(NOTIFICATION_ID, build(service))
        }
        RuntimeLogger.log(
            "Node detached from unified notification: $nodeName (${activeNodes.size} active)",
            "NOTIFICATION"
        )
    }

    private fun ensureChannel(service: Service) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Splendor Foundation Nodes",
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }
        service.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private fun build(service: Service): Notification =
        Notification.Builder(service, CHANNEL_ID)
            .setContentTitle("Splendor Assist")
            .setContentText("Foundation active: ${activeNodes.size} node(s)")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
}
