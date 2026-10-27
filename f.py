#!/usr/bin/env python3
"""Round 3 patch (f3). Dead-code strip only; every removed symbol is grep-proven zero-caller.

Rules: verify repo root, abort on missing/ambiguous/already-applied anchor,
exact old->new replacement, verify each mutation, PASS/FAIL per fix,
exit nonzero on any failure, never push.

Usage: python3 f3.py [REPO_ROOT]   (default /projects/Splendor-Assist)
"""
import os
import subprocess
import sys

REPO = sys.argv[1] if len(sys.argv) > 1 else "/tmp/sa3"
EXPECTED_HEAD = "6930a13a"
results = []


def record(label, ok, msg):
    results.append((label, ok, msg))
    print(f"{'PASS' if ok else 'FAIL'} | {label} | {msg}")


def git(*args):
    return subprocess.run(["git", "-C", REPO, *args], capture_output=True, text=True)


def read(rel):
    with open(os.path.join(REPO, rel), encoding="utf-8", newline="") as fh:
        return fh.read()


def write(rel, text):
    with open(os.path.join(REPO, rel), "w", encoding="utf-8", newline="") as fh:
        fh.write(text)


def replace_once(label, rel, old, new, must_gone=None):
    """Exact old->new. old must occur exactly once. Verified after write."""
    try:
        text = read(rel)
    except OSError as exc:
        return record(label, False, f"missing file {rel}: {exc}")
    count = text.count(old)
    if count == 0:
        return record(label, False, f"anchor missing in {rel} (already applied or drifted)")
    if count > 1:
        return record(label, False, f"anchor ambiguous in {rel} ({count} matches)")
    updated = text.replace(old, new, 1)
    write(rel, updated)
    back = read(rel)
    if back != updated:
        return record(label, False, f"write verification failed for {rel}")
    for needle in (must_gone or []):
        if needle in back:
            return record(label, False, f"removed text still present in {rel}: {needle!r}")
    return record(label, True, f"{rel} edited ({len(old)} chars out, {len(new)} chars in)")


def remove_block(label, rel, start_marker, end_marker_after_start, must_contain):
    """Remove the block from the unique start_marker line through the first line
    equal to end_marker_after_start that appears AFTER the start_marker's own
    unique occurrence. start_marker must occur exactly once."""
    try:
        text = read(rel)
    except OSError as exc:
        return record(label, False, f"missing file {rel}: {exc}")
    if text.count(start_marker) != 1:
        return record(label, False, f"start anchor count {text.count(start_marker)} in {rel} (need exactly 1)")
    s = text.index(start_marker)
    e_rel = text.index(end_marker_after_start, s + len(start_marker))
    e = e_rel + len(end_marker_after_start)
    block = text[s:e]
    for needle in must_contain:
        if needle not in block:
            return record(label, False, f"block does not contain expected text {needle!r}")
    new_text = text[:s] + text[e:]
    write(rel, new_text)
    back = read(rel)
    if back != new_text:
        return record(label, False, f"write verification failed for {rel}")
    for needle in must_contain:
        if needle in back:
            return record(label, False, f"removed text still present in {rel}: {needle!r}")
    return record(label, True, f"{rel}: removed {block.count(chr(10))} lines")


def grep_count(pattern):
    total = 0
    for root in ("app", "core"):
        for dirpath, dirnames, filenames in os.walk(os.path.join(REPO, root)):
            dirnames[:] = [d for d in dirnames if d != "build"]
            for fn in filenames:
                if fn.endswith(".kt"):
                    with open(os.path.join(dirpath, fn), encoding="utf-8", errors="ignore") as fh:
                        total += fh.read().count(pattern)
    return total


# ---------- repo root verification ----------
if not os.path.isdir(os.path.join(REPO, ".git")):
    print(f"FAIL | repo root | {REPO} is not a git repository")
    sys.exit(2)
head = git("rev-parse", "--short", "HEAD").stdout.strip()
if not head.startswith(EXPECTED_HEAD):
    print(f"FAIL | repo root | HEAD is {head}, expected {EXPECTED_HEAD}")
    sys.exit(2)
if git("status", "--porcelain").stdout.strip():
    print("FAIL | repo root | working tree not clean before patch")
    sys.exit(2)
print(f"OK   | repo root | {REPO} at {head}, clean")

OVERLAY = "app/src/main/java/com/assistant/overlay/metrics/SmartAssistMetrics.kt"
GAMEPLAY = "app/src/main/java/com/assistant/gameplay_engine.kt"
PERF = "app/src/main/java/com/assistant/performance_engine.kt"
REGISTRY = "core/src/main/java/com/assistant/runtime/GameplayEngineRegistry.kt"
SELFAUDIT = "core/src/main/java/com/assistant/audit/SelfAuditRegistry.kt"
MMAP = "core/src/main/java/com/assistant/memory/MmapStateEngine.kt"

# ---------- F1: overlay SmartAssistMetrics dead mirror calls + alias import ----------
replace_once("F1a overlay alias import", OVERLAY,
             "import com.assistant.SmartAssistMetrics as RuntimeMetrics\n", "",
             must_gone=["as RuntimeMetrics"])
replace_once("F1b overlay recordDecision mirror call", OVERLAY,
             "        RuntimeMetrics.recordDecisionMirror(durationMs, triggeredAction)\n", "",
             must_gone=["recordDecisionMirror"])
replace_once("F1c overlay recordError mirror call", OVERLAY,
             "        RuntimeMetrics.recordDecisionErrorMirror()\n", "",
             must_gone=["recordDecisionErrorMirror"])
replace_once("F1d overlay reset mirror call", OVERLAY,
             "        RuntimeMetrics.resetDecisionMirror()\n", "",
             must_gone=["resetDecisionMirror"])

# ---------- F2: gameplay SmartAssistMetrics decision mirror block (zero external readers) ----------
remove_block(
    "F2 gameplay decision-mirror writers", GAMEPLAY,
    start_marker="    fun recordDecisionMirror(durationMs: Long, triggeredAction: Boolean) {\n",
    end_marker_after_start="    fun decisionRuntimeSnapshot(): Map<String, Any> {\n",
    must_contain=["fun recordDecisionMirror", "fun recordDecisionErrorMirror",
                  "fun resetDecisionMirror"],
)

# ---------- F3: NetJitterEngine.netJitterMs field + its only write ----------
replace_once("F3 netJitterMs field", PERF,
             "    @Volatile var netJitterMs = 0f; private set\n", "",
             must_gone=["var netJitterMs"])
replace_once("F4 netJitterMs update", PERF,
             "                        if (lastRtt > 0f) {\n"
             "                            val d = Math.abs(rtt - lastRtt)\n"
             "                            netJitterMs = netJitterMs * (1 - ALPHA) + d * ALPHA\n"
             "                        }\n",
             "",
             must_gone=["netJitterMs"])

# ---------- F5: GameplayEngineRegistry.engineStates() (zero callers) ----------
remove_block(
    "F5 engineStates()", REGISTRY,
    start_marker="    fun engineStates(): List<Map<String, Any>> = contributors.map { c ->\n",
    end_marker_after_start="\n    }\n",
    must_contain=["fun engineStates", "contributors.map"],
)

# ---------- F6: SelfAuditRegistry dead update(verified: Int, total: Int) overload ----------
replace_once("F6 SelfAuditRegistry Int overload", SELFAUDIT,
             "    @Synchronized\n"
             "    fun update(\n"
             "        verified: Int,\n"
             "        total: Int\n"
             "    ) {\n"
             "        verifiedNodes = verified\n"
             "        totalNodes = total\n"
             "\n"
             "        state =\n"
             "            if (\n"
             "                verified >= total &&\n"
             "                total > 0\n"
             "            ) {\n"
             "                \"PASS\"\n"
             "            } else {\n"
             "                \"WARNING\"\n"
             "            }\n"
             "    }\n"
             "\n",
             "",
             must_gone=["verified: Int"])

# ---------- F7: MmapStateEngine int/long accessors (zero external callers) ----------
replace_once("F7 MmapStateEngine int/long accessors", MMAP,
             "    fun writeInt(offset: Int, value: Int) { buffer?.putInt(offset, value) }\n"
             "    fun readInt(offset: Int): Int = buffer?.getInt(offset) ?: 0\n"
             "    fun writeLong(offset: Int, value: Long) { buffer?.putLong(offset, value) }\n"
             "    fun readLong(offset: Int): Long = buffer?.getLong(offset) ?: 0L\n",
             "",
             must_gone=["fun writeInt(", "fun readLong("])

# ---------- post-patch zero-caller verification ----------
for label, needle in [
    ("zero recordDecisionMirror refs", "recordDecisionMirror"),
    ("zero recordDecisionErrorMirror refs", "recordDecisionErrorMirror"),
    ("zero resetDecisionMirror refs", "resetDecisionMirror"),
    ("zero netJitterMs refs", "netJitterMs"),
    ("zero engineStates refs", "engineStates"),
    ("zero MmapStateEngine int/long refs", "MmapStateEngine.writeInt"),
]:
    n = grep_count(needle)
    record(label, n == 0, f"{n} occurrence(s) of {needle!r} in app/core kotlin")

failed = [r for r in results if not r[1]]
print(f"SUMMARY | {len(results) - len(failed)}/{len(results)} PASS")
sys.exit(1 if failed else 0)
