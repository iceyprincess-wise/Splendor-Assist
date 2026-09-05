package com.assistant.diagnostic
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicLong
object Phase7Diagnostics {
    private var writer: PrintWriter? = null
    private val fc = AtomicLong(0)
    fun init(ctx: android.content.Context) {
        try {
            val f = File(ctx.getExternalFilesDir(null), "phase7.csv")
            writer = PrintWriter(FileWriter(f, false))
            writer?.println("frame,scan_us,cce_us,vis_rem_us,asm_us,dec_us,total_us,rs,ps,dir,arr,cap,lim,samp,blobs")
            writer?.flush()
            Log.d("Phase7", "Init: ${f.absolutePath}")
        } catch (e: Throwable) { Log.e("Phase7", "Init fail", e) }
    }
    fun record(scan: Long, cce: Long, vis: Long, asm: Long, dec: Long, total: Long, rs: Int, ps: Int, dir: Boolean, arr: Boolean, cap: Int, lim: Int, samp: Int, blobs: Int) {
        val f = fc.incrementAndGet()
        if (f % 10L == 0L) { // Log every 10th frame to avoid I/O bottleneck
            writer?.println("$f,$scan,$cce,$vis,$asm,$dec,$total,$rs,$ps,$dir,$arr,$cap,$lim,$samp,$blobs")
            writer?.flush()
        }
    }
    fun close() { try { writer?.close() } catch (_: Throwable) {} }
}
