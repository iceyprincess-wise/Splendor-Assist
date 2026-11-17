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
 * on its own channel with its own ID - a drawer full of node rows.
 *
 * F39-D5: the old header claimed a "proven ID collision (input + memory
 * both on 9993)". That claim can no longer be re-proven from the surviving
 * tree: the only occurrence of 9993 anywhere in the repo is this note
 * itself (the old per-node services are gone; `git log -S 9993` hits only
 * build-output embeds). The collision is therefore recorded here as a
 * historical claim, not as re-verified fact. What IS verifiable is the
 * consolidation itself: one channel, one ID (9990), and a shared row.
 *
 * All three node services run in the same :survival process
 * (GameplayEngineService, PerformanceEngineService,
 * WatchdogAdapterService - see AndroidManifest), so the in-process
 * activeNodes set below really is shared between them. OverlayService
 * (ID 101) and PingEliminatorVpnService (ID 1001) keep their own
 * foreground notifications by design - they are not foundation nodes
 * (overlay/media-projection and VPN), so they are not consolidated here.
 *
 * Detach uses STOP_FOREGROUND_DETACH so one node's shutdown never tears
 * down the shared row that other still-running nodes depend on.
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
        val newlyAdded = activeNodes.add(nodeName)
        ensureChannel(service)
        // F39-D2: if startForeground throws (e.g. an FGS start from the
        // background on Android 12+), the name must not stay in the set
        // claiming a node that never became foreground. Remove it, log
        // the failure, and let the caller see the exception.
        try {
            service.startForeground(NOTIFICATION_ID, build(service, activeNodes.size))
        } catch (t: Throwable) {
            activeNodes.remove(nodeName)
            RuntimeLogger.log(
                "Node attach FAILED: $nodeName (${t.javaClass.simpleName}: ${t.message})",
                "NOTIFICATION"
            )
            throw t
        }
        RuntimeLogger.log(
            "Node ${if (newlyAdded) "attached" else "re-attached"} to unified notification: $nodeName (${activeNodes.size} active)",
            "NOTIFICATION"
        )
    }

    fun detach(service: Service, nodeName: String) {
        // F39-D1: detaching a name that was never attached used to fall
        // through and call stopForeground anyway, stripping the foreground
        // protection of a service that is still attached under another
        // name. A no-op detach must be a no-op.
        if (!activeNodes.remove(nodeName)) {
            RuntimeLogger.log(
                "Node detach ignored: $nodeName was not attached (${activeNodes.size} active)",
                "NOTIFICATION"
            )
            return
        }
        // DETACH keeps the shared row alive for the other attached services.
        service.stopForeground(Service.STOP_FOREGROUND_DETACH)
        // F39-D3: snapshot the count once; build() and the log below used
        // to re-read the live set, so concurrent attach/detach could make
        // the row text and the log line disagree with each other.
        val remaining = activeNodes.size
        val nm = service.getSystemService(NotificationManager::class.java)
        if (nm == null) {
            // F39-D4: a null NotificationManager used to skip the
            // cancel/notify silently, leaving the shared row orphaned with
            // no record. Say so loudly instead.
            RuntimeLogger.log(
                "Node detached but NotificationManager unavailable: $nodeName ($remaining active) - shared row may linger",
                "NOTIFICATION"
            )
        } else if (remaining == 0) {
            nm.cancel(NOTIFICATION_ID)
        } else {
            nm.notify(NOTIFICATION_ID, build(service, remaining))
        }
        RuntimeLogger.log(
            "Node detached from unified notification: $nodeName ($remaining active)",
            "NOTIFICATION"
        )
    }

    /** F39-D6: read API so diagnostics/watchdog can verify the hub state instead of trusting it. */
    fun activeNodeCount(): Int = activeNodes.size

    /** F39-D6: read API so diagnostics/watchdog can verify the hub state instead of trusting it. */
    fun isAttached(nodeName: String): Boolean = activeNodes.contains(nodeName)

    private fun ensureChannel(service: Service) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Splendor Foundation Nodes",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        service.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private fun build(service: Service, activeCount: Int): Notification =
        Notification.Builder(service, CHANNEL_ID)
            .setContentTitle("Splendor Assist")
            .setContentText("Foundation active: $activeCount node(s)")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
}
