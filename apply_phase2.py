#!/usr/bin/env python3
import os, re

BASE = os.path.expanduser("~/projects/Splendor-Assist")
ok = 0
fail = 0

def patch(rel, old, new, desc):
    global ok, fail
    path = os.path.join(BASE, rel)
    if not os.path.exists(path):
        print(f"  MISSING: {rel}"); fail += 1; return False
    txt = open(path, encoding="utf-8").read()
    if old not in txt:
        print(f"  NOT FOUND: {desc}"); fail += 1; return False
    open(path, "w", encoding="utf-8").write(txt.replace(old, new, 1))
    print(f"  OK: {desc}"); ok += 1; return True

def write_file(rel, content, desc):
    global ok
    path = os.path.join(BASE, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w", encoding="utf-8").write(content)
    print(f"  CREATED: {desc}"); ok += 1

def patch_regex(rel, pattern, replacement, desc):
    global ok, fail
    path = os.path.join(BASE, rel)
    if not os.path.exists(path):
        print(f"  MISSING: {rel}"); fail += 1; return False
    txt = open(path, encoding="utf-8").read()
    new_txt, n = re.subn(pattern, replacement, txt)
    if n == 0:
        print(f"  NOT FOUND (regex): {desc}"); fail += 1; return False
    open(path, "w", encoding="utf-8").write(new_txt)
    print(f"  OK (regex): {desc}"); ok += 1; return True

print("\n[1/7] Admin game_fps fix")
patch_regex("diagnostic_core/src/main/java/com/assistant/admin/AdminConfigStore.kt",
    r'Tunable\("lag\.display\.game_fps",[^,]*,\s*\d+f,',
    'Tunable("lag.display.game_fps",        "Game frame rate (eFootball = 60)",          60f,',
    "AdminConfigStore: game_fps 60")
patch_regex("diagnostic_core/src/main/java/com/assistant/admin/AdminTuningDetector.kt",
    r'Pick\("lag\.display\.game_fps",\s*\d+f,',
    'Pick("lag.display.game_fps", 60f,',
    "AdminTuningDetector: fps pick 60")

print("\n[2/7] NEW MemoryPressureBusEngine.kt")
write_file("adapter_memory/src/main/java/com/assistant/adapter/memory/MemoryPressureBusEngine.kt",
'''package com.assistant.adapter.memory
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
object MemoryPressureBusEngine {
    fun publish(tier: String, availMb: Long) {
        AdapterSignalBus.publishMemory(tier, availMb)
        if (tier == "CRITICAL")
            RuntimeLogger.log("MemoryPressureBus: CRITICAL (avail=${availMb}MB)", "MEMBUSENGINE")
    }
}
''', "MemoryPressureBusEngine.kt")

patch("diagnostic_core/src/main/java/com/assistant/diagnostic/AdapterSignalBus.kt",
    "    fun publishNet(verdict: String) { netWindow = verdict }",
    """    @Volatile var memoryTier: String = "UNKNOWN"; private set
    @Volatile var memoryAvailMb: Long = -1L; private set
    fun publishNet(verdict: String) { netWindow = verdict }
    fun publishMemory(tier: String, availMb: Long) { memoryTier = tier; memoryAvailMb = availMb }
    val memoryIsCritical: Boolean get() = memoryTier == "CRITICAL"
    val memoryIsUnderPressure: Boolean get() = memoryTier == "CRITICAL" || memoryTier == "PRESSURE\"""",
    "AdapterSignalBus: memory fields")

patch("adapter_memory/src/main/java/com/assistant/adapter/memory/MemoryAdapterService.kt",
    "                publishHealth(",
    "                MemoryPressureBusEngine.publish(tier.name, availableMb)\n                publishHealth(",
    "MemoryAdapterService: publish to bus")

print("\n[3/7] NEW InputLatencyEngine.kt")
write_file("adapter_input/src/main/java/com/assistant/adapter/input/InputLatencyEngine.kt",
'''package com.assistant.adapter.input
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.assistant.diagnostic.AdapterSignalBus
import com.assistant.diagnostic.RuntimeLogger
object InputLatencyEngine {
    @Volatile private var running = false
    @Volatile var latencyMs = 0L; private set
    @Volatile var classification = "UNKNOWN"; private set
    @Volatile var measurements = 0L; private set
    @Volatile var lagEvents = 0L; private set
    private val mainHandler = Handler(Looper.getMainLooper())
    fun start() {
        if (running) return; running = true
        val t = Thread {
            while (running) {
                try { measure() } catch (_: Throwable) {}
                try { Thread.sleep(200L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "input-latency"; t.priority = Thread.MAX_PRIORITY; t.start()
        RuntimeLogger.log("InputLatencyEngine started", "INPUT")
    }
    fun stop() { running = false }
    private fun measure() {
        val posted = SystemClock.elapsedRealtime()
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post { latencyMs = SystemClock.elapsedRealtime() - posted; latch.countDown() }
        latch.await(200L, java.util.concurrent.TimeUnit.MILLISECONDS)
        measurements++
        classification = when {
            latencyMs < 16L -> "INSTANT"
            latencyMs < 33L -> "GOOD"
            latencyMs < 66L -> "DELAYED"
            else -> { lagEvents++; "LAGGING" }
        }
        AdapterSignalBus.publishInput(classification, latencyMs)
        if (classification == "LAGGING") {
            RuntimeLogger.log("INPUT LAG: ${latencyMs}ms (total lag events: $lagEvents)", "INPUT")
            mainHandler.post { }
        }
    }
}
''', "InputLatencyEngine.kt")

print("\n[4/7] NEW TouchQualityEngine.kt")
write_file("adapter_input/src/main/java/com/assistant/adapter/input/TouchQualityEngine.kt",
'''package com.assistant.adapter.input
import android.os.Process
import com.assistant.diagnostic.RuntimeLogger
import java.io.BufferedReader
import java.io.FileReader
object TouchQualityEngine {
    @Volatile private var running = false
    @Volatile var oomScore = 0; private set
    @Volatile var oomWarning = false; private set
    @Volatile var irqDropDetected = false; private set
    fun start() {
        if (running) return; running = true
        val t = Thread {
            var prevIrq = -1L; var emptyIrqCycles = 0
            while (running) {
                try {
                    val oom = readOomScore(Process.myPid()); oomScore = oom; oomWarning = oom > 0
                    if (oomWarning) RuntimeLogger.log("TouchQuality: OOM adj=$oom — HyperOS may throttle us", "INPUT")
                    val irq = readTouchIrqCount()
                    if (prevIrq >= 0 && irq >= 0) {
                        val delta = irq - prevIrq
                        if (delta == 0L) { emptyIrqCycles++
                            if (emptyIrqCycles >= 5) { irqDropDetected = true
                                RuntimeLogger.log("TouchQuality: touch IRQ STALL — no kernel events for ${emptyIrqCycles*5}s", "INPUT") }
                        } else { emptyIrqCycles = 0; irqDropDetected = false }
                    }
                    if (irq >= 0) prevIrq = irq
                } catch (_: Throwable) {}
                try { Thread.sleep(5_000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "input-touch-quality"; t.start()
        RuntimeLogger.log("TouchQualityEngine started", "INPUT")
    }
    fun stop() { running = false }
    private fun readOomScore(pid: Int): Int = try {
        BufferedReader(FileReader("/proc/$pid/oom_score_adj")).use { it.readLine()?.trim()?.toIntOrNull() ?: 0 }
    } catch (_: Throwable) { 0 }
    private fun readTouchIrqCount(): Long = try {
        val kw = listOf("touch","fts","focal","hid","i2c","input")
        var total = 0L; var found = false
        BufferedReader(FileReader("/proc/interrupts")).use { br ->
            br.lineSequence().forEach { line ->
                if (kw.any { line.lowercase().contains(it) }) {
                    found = true
                    Regex("""\s+(\d+)""").findAll(line).forEach { total += it.groupValues[1].toLongOrNull() ?: 0L }
                }
            }
        }
        if (found) total else -1L
    } catch (_: Throwable) { -1L }
}
''', "TouchQualityEngine.kt")

print("\n[5/7] NEW InputPriorityEngine.kt")
write_file("adapter_input/src/main/java/com/assistant/adapter/input/InputPriorityEngine.kt",
'''package com.assistant.adapter.input
import android.os.Process
import com.assistant.diagnostic.RuntimeLogger
object InputPriorityEngine {
    @Volatile private var running = false
    @Volatile var reapplyCount = 0; private set
    fun start() {
        if (running) return; running = true
        applyPriority()
        val t = Thread {
            while (running) {
                try {
                    val cur = Process.getThreadPriority(Process.myTid())
                    if (cur > Process.THREAD_PRIORITY_URGENT_DISPLAY) {
                        reapplyCount++
                        RuntimeLogger.log("InputPriority: reset to $cur by OS, reapplying (#$reapplyCount)", "INPUT")
                        applyPriority()
                    }
                } catch (_: Throwable) {}
                try { Thread.sleep(30_000L) } catch (_: Throwable) { return@Thread }
            }
        }
        t.isDaemon = true; t.name = "input-priority"; t.start()
        RuntimeLogger.log("InputPriorityEngine started — URGENT_DISPLAY secured", "INPUT")
    }
    fun stop() { running = false }
    private fun applyPriority() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
              RuntimeLogger.log("InputPriority: URGENT_DISPLAY applied", "INPUT") }
        catch (e: Throwable) { RuntimeLogger.log("InputPriority: failed: ${e.message}", "INPUT") }
    }
}
''', "InputPriorityEngine.kt")

patch("diagnostic_core/src/main/java/com/assistant/diagnostic/AdapterSignalBus.kt",
    "    fun publishMemory(tier: String, availMb: Long) { memoryTier = tier; memoryAvailMb = availMb }",
    """    fun publishMemory(tier: String, availMb: Long) { memoryTier = tier; memoryAvailMb = availMb }
    @Volatile var inputClassification: String = "UNKNOWN"; private set
    @Volatile var inputLatencyMs: Long = 0L; private set
    fun publishInput(classification: String, latencyMs: Long) { inputClassification = classification; inputLatencyMs = latencyMs }
    val inputIsLagging: Boolean get() = inputClassification == "LAGGING\"""",
    "AdapterSignalBus: input fields")

print("\n[6/7] Wire engines into InputAdapterService")
patch("adapter_input/src/main/java/com/assistant/adapter/input/InputAdapterService.kt",
    '        RuntimeLogger.log("InputAdapter heartbeat scheduler started", "HEALTH")',
    '''        InputPriorityEngine.start()
        InputLatencyEngine.start()
        TouchQualityEngine.start()
        RuntimeLogger.log("Input engine stack ignited: 3 engines [LATENCY+QUALITY+PRIORITY]", "INPUT")
        RuntimeLogger.log("InputAdapter heartbeat scheduler started", "HEALTH")''',
    "InputAdapterService: ignite 3 engines")
patch("adapter_input/src/main/java/com/assistant/adapter/input/InputAdapterService.kt",
    "        heartbeatHandler.removeCallbacks(heartbeatRunnable)",
    "        InputLatencyEngine.stop()\n        TouchQualityEngine.stop()\n        InputPriorityEngine.stop()\n        heartbeatHandler.removeCallbacks(heartbeatRunnable)",
    "InputAdapterService: stop engines")

print("\n[7/7] Wire AdapterSignalBus into SmartAssist")
patch("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/contributors/SpeedCompensationContributor.kt",
    "import com.assistant.adapter.smartassist.SpeedCompensationEngine\nimport com.assistant.runtime.*",
    "import com.assistant.adapter.smartassist.SpeedCompensationEngine\nimport com.assistant.diagnostic.AdapterSignalBus\nimport com.assistant.runtime.*",
    "SpeedCompensationContributor: import bus")
patch("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/contributors/SpeedCompensationContributor.kt",
    "            durationHintMs = 30L",
    """            durationHintMs = run {
                val p = when {
                    AdapterSignalBus.lagIsChoking || AdapterSignalBus.memoryIsCritical -> 0.5f
                    AdapterSignalBus.inputIsLagging -> 0.7f
                    AdapterSignalBus.lagVerdict == "JITTERY" || AdapterSignalBus.memoryIsUnderPressure -> 0.8f
                    else -> 1.0f
                }
                (30L * p).toLong().coerceIn(12L, 60L)
            }""",
    "SpeedCompensationContributor: bodyguard-aware duration")
patch("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeDecisionLoop.kt",
    "import com.assistant.runtime.GameplayEngineRegistry",
    "import com.assistant.diagnostic.AdapterSignalBus\nimport com.assistant.runtime.GameplayEngineRegistry",
    "RuntimeDecisionLoop: import bus")
patch("adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeDecisionLoop.kt",
    "        val best: EngineContribution? =\n            contributions.maxByOrNull { it.weight * classScale(it.actionClass) }",
    """        val netHold = AdapterSignalBus.netIsHold
        val best: EngineContribution? =
            contributions
                .filter { c -> if (netHold) c.actionClass == ActionClass.MOVE || c.actionClass == ActionClass.DEFEND else true }
                .maxByOrNull { it.weight * classScale(it.actionClass) }""",
    "RuntimeDecisionLoop: suppress server-commits on NET HOLD")

print(f"\n{'='*50}\nRESULT: {ok} OK  |  {fail} FAILED")
if fail == 0:
    print("All OK. Run the commit block.")
else:
    print("Fix failed patches then re-run.")
