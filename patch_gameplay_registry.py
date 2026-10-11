#!/usr/bin/env python3
import pathlib
import sys

TARGET_FILE = pathlib.Path("core/src/main/java/com/assistant/runtime/GameplayEngineRegistry.kt")

def patch():
    if not TARGET_FILE.exists():
        print(f"FAIL: Target file missing: {TARGET_FILE}")
        sys.exit(1)

    content = TARGET_FILE.read_text(encoding="utf-8")

    OLD_COLLECT_BLOCK = """    fun collect(frame: RuntimeFrame): List<EngineContribution> {
        collectCycles.incrementAndGet()
        ContributionResult.reset()
        for (c in contributors) {
            try {
                c.update(frame)
                val contribution = c.contribute(frame) ?: continue
                lastContribution[c.engineName] = contribution
                contributed[c.engineName] = (contributed[c.engineName] ?: 0L) + 1L
                ContributionResult.addContribution(contribution)
            } catch (t: Throwable) {
                val n = (failures[c.engineName] ?: 0L) + 1L
                failures[c.engineName] = n
                if (n == 1L || n % 50L == 0L) {
                    RuntimeLogger.log("ENGINE FAILURE ${c.engineName} x$n: ${t.message ?: t.javaClass.simpleName}", "RUNTIME")
                }
            }
        }
        return ContributionResult
    }"""

    NEW_COLLECT_BLOCK = """    fun collect(frame: RuntimeFrame): List<EngineContribution> {
        collectCycles.incrementAndGet()
        val results = ArrayList<EngineContribution>(contributors.size)
        for (c in contributors) {
            try {
                c.update(frame)
                val contribution = c.contribute(frame) ?: continue
                lastContribution[c.engineName] = contribution
                contributed.compute(c.engineName) { _, v -> (v ?: 0L) + 1L }
                results.add(contribution)
            } catch (t: Throwable) {
                var currentFailures = 1L
                failures.compute(c.engineName) { _, v ->
                    val next = (v ?: 0L) + 1L
                    currentFailures = next
                    next
                }
                if (currentFailures == 1L || currentFailures % 50L == 0L) {
                    RuntimeLogger.log("ENGINE FAILURE ${c.engineName} x$currentFailures: ${t.message ?: t.javaClass.simpleName}", "RUNTIME")
                }
            }
        }
        return results
    }"""

    OLD_CONTRIB_OBJ = """object ContributionResult : java.util.AbstractList<EngineContribution>() {
    private var pool = Array<EngineContribution?>(64) { null }
    override var size: Int = 0
    fun reset() { size = 0 }
    fun addContribution(c: EngineContribution) {
        if (size >= pool.size) pool = pool.copyOf(pool.size * 2)
        pool[size++] = c
    }
    override fun get(index: Int): EngineContribution = pool[index]!!
}"""

    NEW_CONTRIB_OBJ = """// ContributionResult global singleton replaced with thread-confined ArrayList snapshot return in collect() to eliminate data races and torn reads."""

    content_norm = content.replace("\r\n", "\n")
    old_collect_norm = OLD_COLLECT_BLOCK.replace("\r\n", "\n")
    old_contrib_norm = OLD_CONTRIB_OBJ.replace("\r\n", "\n")

    if old_collect_norm not in content_norm or old_contrib_norm not in content_norm:
        if "val results = ArrayList<EngineContribution>(contributors.size)" in content_norm:
            print("PASS: Patch already applied to GameplayEngineRegistry.kt")
            sys.exit(0)
        else:
            print("FAIL: Expected source block not found in GameplayEngineRegistry.kt")
            sys.exit(1)

    new_content = content_norm.replace(old_contrib_norm, NEW_CONTRIB_OBJ)
    new_content = new_content.replace(old_collect_norm, NEW_COLLECT_BLOCK)

    TARGET_FILE.write_text(new_content, encoding="utf-8")

    updated = TARGET_FILE.read_text(encoding="utf-8")
    if "val results = ArrayList<EngineContribution>(contributors.size)" not in updated:
        print("FAIL: Verification failed - collect() mutation not present.")
        sys.exit(1)

    if "ContributionResult" in updated:
        print("FAIL: Verification failed - ContributionResult still referenced in file.")
        sys.exit(1)

    print("PASS: Successfully patched GameplayEngineRegistry.kt")

if __name__ == "__main__":
    patch()
