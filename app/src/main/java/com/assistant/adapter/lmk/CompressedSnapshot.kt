package com.assistant.adapter.lmk

data class CompressedSnapshot(
    val componentName: String,
    val timestamp: Long,
    val payload: ByteArray
)
