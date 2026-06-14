package com.assistant.adapter.lmk

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object SnapshotCompressionEngine {

    fun compress(text: String): ByteArray {

        val output = ByteArrayOutputStream()

        GZIPOutputStream(output).use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }

        return output.toByteArray()
    }

    fun decompress(data: ByteArray): String {

        return GZIPInputStream(
            ByteArrayInputStream(data)
        ).bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
}
