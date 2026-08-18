from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import sys

ROOT = Path.cwd()

PATCHES = {
    "adapter_battery/src/main/java/com/assistant/adapter/battery/BatteryAdapterService.kt": [
        (
            '''if (AdapterSignalBus.manualPerformanceEscalation) 5000L else 30000L''',
            '''30000L'''
        ),
    ],

    "adapter_boot/src/main/java/com/assistant/adapter/boot/BootAdapterService.kt": [
        (
            '''if (AdapterSignalBus.manualPerformanceEscalation) 5000L else 30000L''',
            '''30000L'''
        ),
    ],

    "adapter_ping/src/main/java/com/assistant/adapter/ping/PingAdapterService.kt": [
        (
            '''if (AdapterSignalBus.manualPerformanceEscalation) 5000L else 30000L''',
            '''30000L'''
        ),
    ],

    "adapter_scheduler/src/main/java/com/assistant/adapter/scheduler/SchedulerAdapterService.kt": [
        (
            '''if (AdapterSignalBus.manualPerformanceEscalation) 3000L else 15000L''',
            '''15000L'''
        ),
    ],

    "adapter_thermal/src/main/java/com/assistant/adapter/thermal/ThermalAdapterService.kt": [
        (
            '''if (AdapterSignalBus.manualPerformanceEscalation) 5000L else 30000L''',
            '''30000L'''
        ),
    ],

    "adapter_input/src/main/java/com/assistant/adapter/input/InputLatencyEngine.kt": [
        (
            '''val intervalMs = if (AdapterSignalBus.manualPerformanceEscalation) {
                    100L
                } else {
                    200L
                }''',
            '''val intervalMs = 200L'''
        ),
    ],

    "adapter_input/src/main/java/com/assistant/adapter/input/OomAdaptiveThrottleEngine.kt": [
        (
            '''val intervalMs = if (AdapterSignalBus.manualPerformanceEscalation) {
                    minOf(POLL_MS, 500L)
                } else {
                    POLL_MS
                }''',
            '''val intervalMs = POLL_MS'''
        ),
    ],

    "adapter_input/src/main/java/com/assistant/adapter/input/GestureTimingFeedbackEngine.kt": [
        (
            '''        if (AdapterSignalBus.manualPerformanceEscalation) {
            RuntimeLogger.log(
                "GestureTiming: manual performance escalation active",
                "INPUT"
            )
        }
''',
            ''''''
        ),
    ],

    "adapter_lag/src/main/java/com/assistant/adapter/lag/LoadShedCaptureBrakeEngine.kt": [
        (
            '''val brake = if (AdapterSignalBus.manualPerformanceEscalation) {
            2
        } else {
            when (level) {
                "HEAVY" -> 2
                "LIGHT" -> 1
                else    -> 0
            }
        }''',
            '''val brake = when (level) {
            "HEAVY" -> 2
            "LIGHT" -> 1
            else    -> 0
        }'''
        ),
    ],

    "adapter_memory/src/main/java/com/assistant/adapter/memory/MemoryCaptureGateEngine.kt": [
        (
            '''    fun recommendedIntervalMs(): Long {
        if (AdapterSignalBus.manualPerformanceEscalation) {
            // Manual player truth requests faster capture-health reassessment,
            // while the existing memory throttle remains authoritative.
            return minOf(
                when (captureThrottle) {
                    3    -> 100L
                    2    -> 66L
                    1    -> 50L
                    else -> 33L
                },
                33L
            )
        }

        return when (captureThrottle) {
            3    -> 100L
            2    -> 66L
            1    -> 50L
            else -> 33L
        }
    }''',
            '''    fun recommendedIntervalMs(): Long = when (captureThrottle) {
        3    -> 100L
        2    -> 66L
        1    -> 50L
        else -> 33L
    }'''
        ),
    ],

    "adapter_memory/src/main/java/com/assistant/adapter/memory/MemoryPressureBusEngine.kt": [
        (
            '''    fun publish(tier: String, availMb: Long) {
        if (AdapterSignalBus.manualPerformanceEscalation) {
            RuntimeLogger.log(
                "MemoryPressureBus: manual performance escalation active; " +
                    "memory measurement remains authoritative",
                "MEMBUSENGINE"
            )
        }
        AdapterSignalBus.publishMemory(tier, availMb)
        if (tier == "CRITICAL")
            RuntimeLogger.log("MemoryPressureBus: CRITICAL (avail=${availMb}MB)", "MEMBUSENGINE")
    }''',
            '''    fun publish(tier: String, availMb: Long) {
        AdapterSignalBus.publishMemory(tier, availMb)
        if (tier == "CRITICAL")
            RuntimeLogger.log("MemoryPressureBus: CRITICAL (avail=${availMb}MB)", "MEMBUSENGINE")
    }'''
        ),
    ],

    "adapter_net/src/main/java/com/assistant/adapter/net/ActionWindowEngine.kt": [
        (
            '''val next = when {
                        AdapterSignalBus.manualPerformanceEscalation -> "HOLD"
                        CongestionSentinelEngine.congested || loss > HOLD_LOSS_PCT ||
                            NetProbeEngine.jitter > tol * HOLD_JITTER_MULT -> "HOLD"
                        NetProbeEngine.quality == "GOOD" && loss < GO_LOSS_PCT -> "GO"
                        else -> "CAUTION"
                    }''',
            '''val next = when {
                        CongestionSentinelEngine.congested || loss > HOLD_LOSS_PCT ||
                            NetProbeEngine.jitter > tol * HOLD_JITTER_MULT -> "HOLD"
                        NetProbeEngine.quality == "GOOD" && loss < GO_LOSS_PCT -> "GO"
                        else -> "CAUTION"
                    }'''
        ),
        (
            '''val nap = if (AdapterSignalBus.manualPerformanceEscalation) {
                    minOf(POLL_MS, 250L)
                } else {
                    POLL_MS
                }''',
            '''val nap = POLL_MS'''
        ),
    ],

    "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/contributors/SpeedCompensationContributor.kt": [
        (
            '''                    AdapterSignalBus.manualPerformanceEscalation -> 0.5f
''',
            ''''''
        ),
    ],

    "adapter_stutter/src/main/java/com/assistant/adapter/stutter/BurstForensicsEngine.kt": [
        (
            '''        if (AdapterSignalBus.manualPerformanceEscalation) {
            RuntimeLogger.log(
                "STUTTER MANUAL ESCALATION active: measured burst processing remains authoritative",
                "STUTTER"
            )
        }
''',
            ''''''
        ),
    ],
}

def git_grep(pattern):
    p = subprocess.run(
        ["git", "grep", "-nE", pattern, "--", "*.kt", "*.java"],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return p.returncode, p.stdout.strip()

def atomic_write(path, text):
    fd, tmp = tempfile.mkstemp(
        prefix=path.name + ".",
        suffix=".tmp",
        dir=str(path.parent)
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise

print("=" * 68)
print("DELETED CANONICAL ESCALATION CONSUMER REPAIR")
print("=" * 68)

# ------------------------------------------------------------
# 1. Repository state guard
# ------------------------------------------------------------
status = subprocess.run(
    ["git", "status", "--porcelain"],
    cwd=ROOT,
    text=True,
    stdout=subprocess.PIPE,
    check=True
).stdout.strip()

if status:
    print("FAIL: working tree is not clean.")
    print(status)
    print("Commit/stash existing changes before this structural repair.")
    sys.exit(10)

# ------------------------------------------------------------
# 2. Confirm deleted API is actually absent from canonical bus
# ------------------------------------------------------------
bus = ROOT / "diagnostic_core/src/main/java/com/assistant/diagnostic/AdapterSignalBus.kt"

if not bus.is_file():
    print("FAIL: canonical AdapterSignalBus.kt missing.")
    sys.exit(11)

bus_text = bus.read_text(encoding="utf-8")

if "manualPerformanceEscalation" in bus_text:
    print("FAIL: AdapterSignalBus still defines manualPerformanceEscalation.")
    print("This script is for stale consumers after architecture removal.")
    sys.exit(12)

print("PASS: canonical AdapterSignalBus contains no deleted escalation API.")

# ------------------------------------------------------------
# 3. Preflight every file and every exact anchor.
#    NOTHING is modified unless ALL anchors are valid.
# ------------------------------------------------------------
prepared = {}

for rel, replacements in PATCHES.items():
    path = ROOT / rel

    if not path.is_file():
        print(f"FAIL: missing file: {rel}")
        sys.exit(20)

    original = path.read_text(encoding="utf-8")
    updated = original

    for old, new in replacements:
        count = updated.count(old)

        if count != 1:
            print(f"FAIL: anchor count mismatch in {rel}")
            print(f"Expected exactly 1 occurrence; found {count}")
            print("NO FILES WILL BE MODIFIED.")
            sys.exit(21)

        updated = updated.replace(old, new, 1)

    prepared[path] = (original, updated)

print(f"PASS: {len(prepared)} affected files passed exact-anchor preflight.")

# ------------------------------------------------------------
# 4. Backups + atomic writes
# ------------------------------------------------------------
for path, (original, updated) in prepared.items():
    backup = Path(str(path) + ".pre_deleted_escalation_fix.bak")

    if not backup.exists():
        shutil.copy2(path, backup)

    atomic_write(path, updated)
    print(f"PATCHED: {path.relative_to(ROOT)}")

# ------------------------------------------------------------
# 5. Repository-wide proof that the deleted symbol is gone
# ------------------------------------------------------------
rc, remaining = git_grep("manualPerformanceEscalation")

if remaining:
    print()
    print("FAIL: stale manualPerformanceEscalation references remain:")
    print(remaining)
    print()
    print("ROLLBACK: restoring all files from verified originals.")

    for path, (original, _) in prepared.items():
        atomic_write(path, original)

    sys.exit(30)

print()
print("PASS: manualPerformanceEscalation = ZERO source references.")

# ------------------------------------------------------------
# 6. Deleted DefectEscalationBus must also remain absent.
# ------------------------------------------------------------
rc, defect_bus = git_grep("DefectEscalationBus")

if defect_bus:
    print("FAIL: deleted DefectEscalationBus still has source references:")
    print(defect_bus)

    for path, (original, _) in prepared.items():
        atomic_write(path, original)

    sys.exit(31)

print("PASS: DefectEscalationBus = ZERO source references.")

# ------------------------------------------------------------
# 7. Ensure the accidental deleted architecture wasn't recreated
# ------------------------------------------------------------
rc, manual_names = git_grep(
    "manualPerformance|GLASS_SINGLE|GLASS_DOUBLE|GLASS_SINGLE|GLASS_DOUBLE"
)

if manual_names:
    print("FAIL: deleted manual escalation identifiers remain:")
    print(manual_names)

    for path, (original, _) in prepared.items():
        atomic_write(path, original)

    sys.exit(32)

print("PASS: deleted manual escalation identifiers absent.")

# ------------------------------------------------------------
# 8. Structural checks for important normal behavior
# ------------------------------------------------------------
checks = {
    "Battery 30s cadence":
        "adapter_battery/src/main/java/com/assistant/adapter/battery/BatteryAdapterService.kt",
    "Boot 30s cadence":
        "adapter_boot/src/main/java/com/assistant/adapter/boot/BootAdapterService.kt",
    "Ping 30s cadence":
        "adapter_ping/src/main/java/com/assistant/adapter/ping/PingAdapterService.kt",
    "Thermal 30s cadence":
        "adapter_thermal/src/main/java/com/assistant/adapter/thermal/ThermalAdapterService.kt",
    "Scheduler 15s cadence":
        "adapter_scheduler/src/main/java/com/assistant/adapter/scheduler/SchedulerAdapterService.kt",
}

for label, rel in checks.items():
    text = (ROOT / rel).read_text(encoding="utf-8")

    if label == "Scheduler 15s cadence":
        required = "15000L"
    else:
        required = "30000L"

    if required not in text:
        print(f"FAIL: expected normal cadence missing: {label}")

        for path, (original, _) in prepared.items():
            atomic_write(path, original)

        sys.exit(33)

    print(f"PASS: {label}")

# ------------------------------------------------------------
# 9. Kotlin source sanity: no malformed leftover empty branches
# ------------------------------------------------------------
for path in prepared:
    text = path.read_text(encoding="utf-8")

    if "if ()" in text:
        print(f"FAIL: malformed conditional generated in {path}")
        for p, (original, _) in prepared.items():
            atomic_write(p, original)
        sys.exit(34)

print("PASS: no malformed empty conditionals.")

print()
print("=" * 68)
print("REPAIR COMPLETE")
print("=" * 68)
print("Deleted canonical escalation behavior was NOT recreated.")
print("All stale consumers were removed at their semantic boundaries.")
print("Normal adapter behavior remains in place.")
print()
print("NEXT:")
print("  git diff --check")
print("  git diff --stat")
print("  git diff")
print("  ./gradlew :app:assembleDebug")
print()
