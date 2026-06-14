package com.assistant.adapter.lmk

object RehydrationEngine {

    fun restore(
        componentName: String
    ): RehydratedStateSnapshot? {

        val compressed =
            CompressedSnapshotRepository.get(componentName)
                ?: return null

        val decompressed =
            SnapshotCompressionEngine.decompress(
                compressed.payload
            )

        val parts = decompressed.split("|")

        if (parts.size < 5) {
            return null
        }

        return RehydratedStateSnapshot(
            componentName = parts[0],
            lifecycleState = parts[1],
            timestamp = parts[2].toLongOrNull() ?: 0L,
            memoryPressure = parts[3],
            details = parts[4]
        )
    }
}
