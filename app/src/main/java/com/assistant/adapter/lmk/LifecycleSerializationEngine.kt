package com.assistant.adapter.lmk

import android.content.ComponentCallbacks2

object LifecycleSerializationEngine {

    fun capture(
        componentName: String,
        lifecycleState: String,
        trimLevel: Int = ComponentCallbacks2.TRIM_MEMORY_COMPLETE
    ) {

        val pressure =
            when {
                trimLevel >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ->
                    "CRITICAL"

                trimLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                    "LOW"

                else ->
                    "NORMAL"
            }

        val snapshot =
            StateSnapshot(
                componentName = componentName,
                lifecycleState = lifecycleState,
                timestamp = System.currentTimeMillis(),
                memoryPressure = pressure,
                details = "Lifecycle snapshot captured"
            )

        LifecycleSnapshotRepository.save(snapshot)

        val serialized =
            "${snapshot.componentName}|${snapshot.lifecycleState}|${snapshot.timestamp}|${snapshot.memoryPressure}|${snapshot.details}"

        val compressed =
            SnapshotCompressionEngine.compress(serialized)

        CompressedSnapshotRepository.save(
            CompressedSnapshot(
                componentName = snapshot.componentName,
                timestamp = snapshot.timestamp,
                payload = compressed
            )
        )
    }
}
