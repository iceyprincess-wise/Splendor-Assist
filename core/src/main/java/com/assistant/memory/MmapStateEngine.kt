package com.assistant.memory

import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

object MmapStateEngine {
    private const val FILE_NAME = "splendor_mmap_state.bin"
    private const val SIZE = 4096 // 4KB page
    
    private var raf: RandomAccessFile? = null
    private var buffer: MappedByteBuffer? = null
    
    fun initialize(cacheDir: File) {
        if (raf != null && buffer != null) return
        try {
            val file = File(cacheDir, FILE_NAME)
            if (!file.exists()) file.createNewFile()
            raf = RandomAccessFile(file, "rw")
            buffer = raf?.channel?.map(FileChannel.MapMode.READ_WRITE, 0, SIZE.toLong())
        } catch (_: Throwable) {}
    }
    
    
    fun release() {
        try { raf?.close() } catch (_: Throwable) {}
        raf = null
        buffer = null
    }
}
