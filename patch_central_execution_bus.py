#!/usr/bin/env python3
import sys
import os

def main():
    target_rel = "core/src/main/java/com/assistant/execution/CentralExecutionBus.kt"
    if not os.path.exists(target_rel):
        print(f"FAIL: Target file does not exist: {target_rel}")
        sys.exit(1)

    with open(target_rel, "r", encoding="utf-8") as f:
        content = f.read()

    # Precondition 1: Check ExecutionRequest timestamp is val
    old_ts = "val timestamp: Long = System.currentTimeMillis(),"
    new_ts = "var timestamp: Long = System.currentTimeMillis(),"

    if old_ts not in content:
        if new_ts in content:
            print("INFO: ExecutionRequest.timestamp already converted to var.")
        else:
            print("FAIL: Precondition failed - could not find ExecutionRequest.timestamp definition.")
            sys.exit(1)

    # Precondition 2: Check old submit pattern
    old_submit = """        val writePos = ring.writeCursor.getAndIncrement()
        val readPos = ring.readCursor.get()

        if (writePos - readPos >= RING_CAPACITY) {
            ring.writeCursor.decrementAndGet() // Rollback
            capacityDrops.incrementAndGet()
            return false
        }

        request.busAccepted = true
        ring.buffer.set((writePos and MASK).toInt(), request)
        accepted.incrementAndGet()
        updateStatistics()
        return true"""

    new_submit = """        while (true) {
            val writePos = ring.writeCursor.get()
            val readPos = ring.readCursor.get()

            if (writePos - readPos >= RING_CAPACITY) {
                capacityDrops.incrementAndGet()
                return false
            }

            if (ring.writeCursor.compareAndSet(writePos, writePos + 1)) {
                request.timestamp = System.currentTimeMillis()
                request.busAccepted = true
                ring.buffer.set((writePos and MASK).toInt(), request)
                accepted.incrementAndGet()
                updateStatistics()
                return true
            }
        }"""

    # Precondition 3: Check old consumeFrom pattern
    old_consume = """            val request = ring.buffer.get((readPos and MASK).toInt())
            if (ring.readCursor.compareAndSet(readPos, readPos + 1)) {
                if (request == null) continue // Race condition, slot empty
                if (requestIsStale(request, System.currentTimeMillis())) {
                    staleDrops.incrementAndGet()
                    continue // Drop stale and try next
                }
                consumed.incrementAndGet()
                request.busConsumed = true
                return request
            }"""

    new_consume = """            val index = (readPos and MASK).toInt()
            val request = ring.buffer.get(index)
            if (request == null) return null // In-flight write, do not skip slot

            if (ring.readCursor.compareAndSet(readPos, readPos + 1)) {
                ring.buffer.set(index, null)
                if (requestIsStale(request, System.currentTimeMillis())) {
                    staleDrops.incrementAndGet()
                    continue // Drop stale and try next
                }
                consumed.incrementAndGet()
                request.busConsumed = true
                return request
            }"""

    # Precondition 4: Check updateStatistics pattern
    old_update_stats = """    fun start() { running.set(true); updateStatistics() }
    fun stop() { running.set(false); updateStatistics() }"""

    new_update_stats = """    fun start() { running.set(true); updateStatistics(force = true) }
    fun stop() { running.set(false); updateStatistics(force = true) }"""

    old_stats_func = """    private fun updateStatistics() {
        _statistics.value = BusStatistics(
            acceptedCount = accepted.get(), consumedCount = consumed.get(),
            pendingCount = pendingCount(), isRunning = running.get()
        )
    }"""

    new_stats_func = """    private fun updateStatistics(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = lastStatsUpdate.get()
        if (force || now - last >= STATS_UPDATE_INTERVAL_MS) {
            if (force || lastStatsUpdate.compareAndSet(last, now)) {
                _statistics.value = BusStatistics(
                    acceptedCount = accepted.get(), consumedCount = consumed.get(),
                    pendingCount = pendingCount(), isRunning = running.get()
                )
            }
        }
    }"""

    # Apply mutations
    mutated = content
    if old_ts in mutated:
        mutated = mutated.replace(old_ts, new_ts, 1)

    if old_submit in mutated:
        mutated = mutated.replace(old_submit, new_submit, 1)

    if old_consume in mutated:
        mutated = mutated.replace(old_consume, new_consume, 1)

    if old_update_stats in mutated:
        mutated = mutated.replace(old_update_stats, new_update_stats, 1)

    if old_stats_func in mutated:
        # Also ensure STATS_UPDATE_INTERVAL_MS definition is added
        stats_fields = """    private const val STATS_UPDATE_INTERVAL_MS = 100L
    private val lastStatsUpdate = AtomicLong(0L)

    private val _statistics = MutableStateFlow(BusStatistics())"""
        if "private const val STATS_UPDATE_INTERVAL_MS" not in mutated:
            mutated = mutated.replace("    private val _statistics = MutableStateFlow(BusStatistics())", stats_fields, 1)
        mutated = mutated.replace(old_stats_func, new_stats_func, 1)

    with open(target_rel, "w", encoding="utf-8") as f:
        f.write(mutated)

    # Post-verification
    with open(target_rel, "r", encoding="utf-8") as f:
        verify = f.read()

    if "var timestamp: Long = System.currentTimeMillis()" not in verify:
        print("FAIL: Verification failed - timestamp is not var.")
        sys.exit(1)

    if "if (request == null) return null" not in verify:
        print("FAIL: Verification failed - consumeFrom null check missing.")
        sys.exit(1)

    if "ring.buffer.set(index, null)" not in verify:
        print("FAIL: Verification failed - ring slot clearing missing.")
        sys.exit(1)

    if "compareAndSet(writePos, writePos + 1)" not in verify:
        print("FAIL: Verification failed - CAS write reservation missing.")
        sys.exit(1)

    if "request.timestamp = System.currentTimeMillis()" not in verify:
        print("FAIL: Verification failed - timestamp update on submit missing.")
        sys.exit(1)

    if "private const val STATS_UPDATE_INTERVAL_MS = 100L" not in verify:
        print("FAIL: Verification failed - stats throttling missing.")
        sys.exit(1)

    print("PASS: CentralExecutionBus.kt successfully patched and verified.")

if __name__ == "__main__":
    main()
