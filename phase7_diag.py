import os
import sys

base_dir = "/tmp/repo"
if not os.path.exists(base_dir):
    base_dir = os.path.expanduser("~/projects/Splendor-Assist")

diag_dir = os.path.join(base_dir, "app/src/main/java/com/assistant/diagnostic")
os.makedirs(diag_dir, exist_ok=True)

# 1. Create Phase7 Context
ctx_code = """package com.assistant.diagnostic
object Phase7FrameContext {
    @Volatile var rowStride = 0; @Volatile var pixelStride = 0
    @Volatile var isDirect = false; @Volatile var hasArray = false
    @Volatile var capacity = 0; @Volatile var limit = 0
    @Volatile var scanTimeNs = 0L; @Volatile var cceTimeNs = 0L
    @Volatile var visRemTimeNs = 0L; @Volatile var asmTimeNs = 0L
    @Volatile var decTimeNs = 0L; @Volatile var sampleCount = 0
    @Volatile var blobCount = 0
    fun reset() { scanTimeNs=0; cceTimeNs=0; visRemTimeNs=0; asmTimeNs=0; decTimeNs=0; sampleCount=0; blobCount=0 }
}
"""
with open(os.path.join(diag_dir, "Phase7FrameContext.kt"), "w") as f: f.write(ctx_code)

# 2. Create Phase7 Logger
diag_code = """package com.assistant.diagnostic
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
"""
with open(os.path.join(diag_dir, "Phase7Diagnostics.kt"), "w") as f: f.write(diag_code)

# 3. Patch OverlayService.kt
os_path = os.path.join(base_dir, "app/src/main/java/com/assistant/OverlayService.kt")
with open(os_path, "r") as f: c = f.read()

c = c.replace("com.assistant.vision.ForegroundGate.install(application)", 
              "com.assistant.vision.ForegroundGate.install(application)\n        com.assistant.diagnostic.Phase7Diagnostics.init(applicationContext)")
c = c.replace("super.onDestroy()", "com.assistant.diagnostic.Phase7Diagnostics.close()\n        super.onDestroy()")

old_img = "            try {\n                val scanBuffer = image.planes[0].buffer.duplicate()\n                val normalized = com.assistant.adapter.smartassist.FrameNormalizer.normalize(scanBuffer.duplicate(), image.width, image.height)"
new_img = """            try {
                val t_start = System.nanoTime()
                val plane = image.planes[0]
                val buf = plane.buffer
                com.assistant.diagnostic.Phase7FrameContext.rowStride = plane.rowStride
                com.assistant.diagnostic.Phase7FrameContext.pixelStride = plane.pixelStride
                com.assistant.diagnostic.Phase7FrameContext.isDirect = buf.isDirect
                com.assistant.diagnostic.Phase7FrameContext.hasArray = buf.hasArray()
                com.assistant.diagnostic.Phase7FrameContext.capacity = buf.capacity()
                com.assistant.diagnostic.Phase7FrameContext.limit = buf.limit()
                com.assistant.diagnostic.Phase7FrameContext.reset()

                val scanBuffer = buf.duplicate()
                val normalized = com.assistant.adapter.smartassist.FrameNormalizer.normalize(scanBuffer.duplicate(), image.width, image.height)"""
c = c.replace(old_img, new_img)

old_block = """                val state = com.assistant.adapter.smartassist.VisionCore.process(normalized)
                com.assistant.BoosterIgnition.ensureIgnited(this)
                com.assistant.AppContributorRegistration.ensureRegistered()
                com.assistant.adapter.smartassist.RuntimeCoordinator.reportCaptureReady()
                val frame = com.assistant.adapter.smartassist.FrameAssembler.assemble()
                com.assistant.adapter.smartassist.RuntimeDecisionLoop.onFrame(frame)
                com.assistant.adapter.smartassist.GameStateBuilder.update(state)"""

new_block = """                val state = com.assistant.adapter.smartassist.VisionCore.process(normalized)
                com.assistant.BoosterIgnition.ensureIgnited(this)
                com.assistant.AppContributorRegistration.ensureRegistered()
                com.assistant.adapter.smartassist.RuntimeCoordinator.reportCaptureReady()
                
                val t_asm_start = System.nanoTime()
                val frame = com.assistant.adapter.smartassist.FrameAssembler.assemble()
                com.assistant.diagnostic.Phase7FrameContext.asmTimeNs = System.nanoTime() - t_asm_start
                
                val t_dec_start = System.nanoTime()
                com.assistant.adapter.smartassist.RuntimeDecisionLoop.onFrame(frame)
                com.assistant.diagnostic.Phase7FrameContext.decTimeNs = System.nanoTime() - t_dec_start
                
                com.assistant.adapter.smartassist.GameStateBuilder.update(state)
                
                val t_end = System.nanoTime()
                val ctx = com.assistant.diagnostic.Phase7FrameContext
                com.assistant.diagnostic.Phase7Diagnostics.record(
                    ctx.scanTimeNs / 1000, ctx.cceTimeNs / 1000, ctx.visRemTimeNs / 1000,
                    ctx.asmTimeNs / 1000, ctx.decTimeNs / 1000, (t_end - t_start) / 1000,
                    ctx.rowStride, ctx.pixelStride, ctx.isDirect, ctx.hasArray,
                    ctx.capacity, ctx.limit, ctx.sampleCount, ctx.blobCount
                )"""
c = c.replace(old_block, new_block)
with open(os_path, "w") as f: f.write(c)

# 4. Patch VisionCore.kt
vc_path = os.path.join(base_dir, "app/src/main/java/com/assistant/adapter/smartassist/VisionCore.kt")
with open(vc_path, "r") as f: vc = f.read()

old_vc = """        val samples =
            FrameScanner.scan(frame)

        val blobs =
            ConnectedComponentEngine.extract(samples)

        val filteredBlobs =
            NoiseFilter.filter(blobs)"""

new_vc = """        val t0 = System.nanoTime()
        val samples =
            FrameScanner.scan(frame)
        val t1 = System.nanoTime()
        com.assistant.diagnostic.Phase7FrameContext.scanTimeNs = t1 - t0
        com.assistant.diagnostic.Phase7FrameContext.sampleCount = samples.count

        val blobs =
            ConnectedComponentEngine.extract(samples)
        val t2 = System.nanoTime()
        com.assistant.diagnostic.Phase7FrameContext.cceTimeNs = t2 - t1
        com.assistant.diagnostic.Phase7FrameContext.blobCount = blobs.size

        val filteredBlobs =
            NoiseFilter.filter(blobs)"""
vc = vc.replace(old_vc, new_vc)

old_vc_end = """    Phase3WorldStateStore.update(
        Phase3WorldState("""
new_vc_end = """    val t3 = System.nanoTime()
    com.assistant.diagnostic.Phase7FrameContext.visRemTimeNs = t3 - t2

    Phase3WorldStateStore.update(
        Phase3WorldState("""
vc = vc.replace(old_vc_end, new_vc_end)
with open(vc_path, "w") as f: f.write(vc)

print("SUCCESS: Phase 7 diagnostic instrumentation injected.")
print("Next steps:")
print("1. Build and install the app on your device.")
print("2. Play eFootball for 1-2 minutes to generate data.")
print("3. Extract the CSV: cat /sdcard/Android/data/com.assistant.overlay/files/phase7.csv")
print("4. Paste the CSV output back to me.")
print("5. CRITICAL: Run 'git reset --hard HEAD' and 'git clean -fd' to remove all temporary diagnostics.")
