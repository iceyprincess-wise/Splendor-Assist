#!/usr/bin/env python3
"""
CORRECTED PATCH: IgnitionEngine.kt
Reapplies BOTH FIX 4a AND FIX 4b.

ROOT CAUSE OF PREVIOUS FAILURE (PROVEN):
  OLD_VERIFY used 8-space indentation on verifyFleetHealth() inner lines.
  Actual file has 12-space indentation inside the try{} block (3 nesting levels:
  object=0, function body=4, try body=8, try contents=12).
  str.replace() found no match -> sys.exit(1) before f.write() -> zero file change.

FIX 4a: Add CRITICAL_ADAPTERS set constant after ADAPTER_TOTAL.
FIX 4b: Replace verifyFleetHealth() try-body with critical-adapter-aware computation.
         Anchor strings are byte-exact copies from the live repository fetch.
"""
import sys

TARGET = "app/src/main/java/com/assistant/IgnitionEngine.kt"

with open(TARGET, "r", encoding="utf-8") as f:
    content = f.read()

original_len = len(content)
changes_applied = 0

# ────────────────────────────────────────────────────────────────
# FIX 4a: Insert CRITICAL_ADAPTERS constant block after ADAPTER_TOTAL
# Anchor: 4-space indent (object-level constants, correct from file)
# ────────────────────────────────────────────────────────────────
OLD_CONSTANTS = \
    "    private const val QUORUM_MINIMUM = 9\n" \
    "    private const val ADAPTER_TOTAL  = 16"

NEW_CONSTANTS = \
    "    private const val QUORUM_MINIMUM = 9\n" \
    "    private const val ADAPTER_TOTAL  = 16\n" \
    "\n" \
    "    // P0 FIX: Critical adapters that MUST ALL be ACTIVE for fleet READY state.\n" \
    "    // PREVIOUS BUG: 9/16 count alone could declare READY while every one of\n" \
    "    // these was dead -- gameplay engines fire blind and ungated.\n" \
    "    //\n" \
    "    // ENFORCEMENT: criticalRequired ⊆ active AND activeCount >= QUORUM_MINIMUM.\n" \
    "    //\n" \
    "    // net         -> network window (GO/CAUTION/HOLD) gating SHOT/PASS/CROSS\n" \
    "    // lag         -> frame pacing verdict (SMOOTH/JITTERY/CHOKING)\n" \
    "    // stutter     -> sub-second burst radar (HICCUP/OSCILLATION/SEIZURE)\n" \
    "    // memory      -> RAM tier (HEALTHY/PRESSURE/CRITICAL) SpeedCompensation\n" \
    "    // thermal     -> device heat 0-6 scaling gesture durations\n" \
    "    // smartassist -> decision health monitor -- core gameplay engine\n" \
    "    // scheduler   -> fleet health counter + fleet-degraded signal\n" \
    "    // watchdog    -> dead adapter guardian and restart engine\n" \
    "    // ping        -> real network RTT -> AdapterSignalBus.pingQuality\n" \
    "    private val CRITICAL_ADAPTERS = setOf(\n" \
    "        \"adapter_net\",\n" \
    "        \"adapter_lag\",\n" \
    "        \"adapter_stutter\",\n" \
    "        \"adapter_memory\",\n" \
    "        \"adapter_thermal\",\n" \
    "        \"adapter_smartassist\",\n" \
    "        \"adapter_scheduler\",\n" \
    "        \"adapter_watchdog\",\n" \
    "        \"adapter_ping\"\n" \
    "    )"

if OLD_CONSTANTS in content:
    content = content.replace(OLD_CONSTANTS, NEW_CONSTANTS, 1)
    print("[OK] FIX 4a: CRITICAL_ADAPTERS val added after ADAPTER_TOTAL")
    changes_applied += 1
else:
    print("[FAIL] FIX 4a: QUORUM_MINIMUM/ADAPTER_TOTAL anchor not found")
    print("       Dumping search region for diagnosis:")
    idx = content.find("QUORUM_MINIMUM")
    if idx >= 0:
        print(repr(content[max(0, idx-10):idx+200]))
    sys.exit(1)

# ────────────────────────────────────────────────────────────────
# FIX 4b: Replace verifyFleetHealth() computation inside try{}
#
# CRITICAL: indentation is 12 spaces (try-body level), NOT 8.
# Every anchor line below is verified byte-for-byte from the
# raw GitHub fetch of the live file.
# ────────────────────────────────────────────────────────────────
OLD_VERIFY = (
    "            val snapshots = com.assistant.diagnostic.registry.AdapterHealthRegistry.getAll()\n"
    "            val activeCount = snapshots.count { snap ->\n"
    "                com.assistant.diagnostic.registry.AdapterHealthRegistry\n"
    "                    .effectiveStatus(snap.adapterName) == \"ACTIVE\"\n"
    "            }\n"
    "\n"
    "            lastVerifiedActiveCount = activeCount\n"
    "            val previousState = fleetState\n"
    "\n"
    "            fleetState = when {\n"
    "                activeCount >= QUORUM_MINIMUM -> FleetLifecycleState.READY\n"
    "                activeCount > 0              -> FleetLifecycleState.WARMING\n"
    "                else                         -> FleetLifecycleState.DEGRADED\n"
    "            }\n"
    "\n"
    "            val transitionNote = if (previousState != fleetState)\n"
    "                \" [TRANSITION: $previousState -> $fleetState]\" else \"\"\n"
    "\n"
    "            RuntimeLogger.log(\n"
    "                \"Fleet verification: active=$activeCount/$ADAPTER_TOTAL \" +\n"
    "                    \"state=$fleetState$transitionNote\",\n"
    "                \"IGNITION\"\n"
    "            )"
)

NEW_VERIFY = (
    "            val snapshots = com.assistant.diagnostic.registry.AdapterHealthRegistry.getAll()\n"
    "\n"
    "            // P0 FIX: Single-pass active-name set computation.\n"
    "            // PREVIOUS BUG: snapshots.count { effectiveStatus == ACTIVE } -- count only,\n"
    "            //   no critical adapter verification. 9 non-critical adapters alive = READY\n"
    "            //   while every critical adapter is dead. Gameplay runs blind and ungated.\n"
    "            // FIXED: build activeNames Set<String> in one filter pass.\n"
    "            //   criticalAllActive = all 9 critical adapters in activeNames.\n"
    "            //   READY requires BOTH: activeCount >= quorum AND criticalAllActive.\n"
    "            val activeNames = snapshots\n"
    "                .filter { snap ->\n"
    "                    com.assistant.diagnostic.registry.AdapterHealthRegistry\n"
    "                        .effectiveStatus(snap.adapterName) == \"ACTIVE\"\n"
    "                }\n"
    "                .map { it.adapterName }\n"
    "                .toSet()\n"
    "\n"
    "            val activeCount = activeNames.size\n"
    "\n"
    "            // P0 FIX: criticalRequired ⊆ active AND activeCount >= quorum.\n"
    "            val criticalAllActive = CRITICAL_ADAPTERS.all { it in activeNames }\n"
    "\n"
    "            lastVerifiedActiveCount = activeCount\n"
    "            val previousState = fleetState\n"
    "\n"
    "            fleetState = when {\n"
    "                activeCount >= QUORUM_MINIMUM && criticalAllActive -> FleetLifecycleState.READY\n"
    "                activeCount > 0 -> FleetLifecycleState.WARMING\n"
    "                else            -> FleetLifecycleState.DEGRADED\n"
    "            }\n"
    "\n"
    "            val transitionNote = if (previousState != fleetState)\n"
    "                \" [TRANSITION: $previousState -> $fleetState]\" else \"\"\n"
    "\n"
    "            // VISIBLE EVIDENCE: logged to DiagnosisRoom when critical adapters missing.\n"
    "            val criticalNote = if (!criticalAllActive) {\n"
    "                val missing = CRITICAL_ADAPTERS - activeNames\n"
    "                \" [CRITICAL_MISSING: $missing]\"\n"
    "            } else \"\"\n"
    "\n"
    "            RuntimeLogger.log(\n"
    "                \"Fleet verification: active=$activeCount/$ADAPTER_TOTAL \" +\n"
    "                    \"state=$fleetState$transitionNote$criticalNote\",\n"
    "                \"IGNITION\"\n"
    "            )"
)

if OLD_VERIFY in content:
    content = content.replace(OLD_VERIFY, NEW_VERIFY, 1)
    print("[OK] FIX 4b: verifyFleetHealth() try-body -- critical-adapter quorum enforced")
    changes_applied += 1
else:
    print("[FAIL] FIX 4b: try-body anchor not found after 4a was applied")
    print("       Indentation diagnosis -- searching for 'val snapshots' in file:")
    idx = content.find("val snapshots = com.assistant.diagnostic")
    if idx >= 0:
        region = content[max(0, idx-4):idx+120]
        print("       repr:", repr(region))
    else:
        print("       'val snapshots' not found at all -- check if AdapterHealthRegistry import changed")
    sys.exit(1)

# ────────────────────────────────────────────────────────────────
# Write output only if BOTH changes succeeded
# ────────────────────────────────────────────────────────────────
with open(TARGET, "w", encoding="utf-8") as f:
    f.write(content)

print(f"\n[DONE] IgnitionEngine.kt: {changes_applied}/2 changes applied")
print(f"       Original: {original_len} chars  ->  New: {len(content)} chars")
print()
print("VISIBLE EVIDENCE:")
print("  DiagnosisRoom/logs: 'Fleet verification: active=7/16 state=WARMING")
print("  [CRITICAL_MISSING: [adapter_lag, adapter_net]]' -- fleet holds, engines gated.")
print("  Dashboard: WARMING until all 9 critical adapters report ACTIVE heartbeats.")
print("  Once all critical + quorum met: READY -> G3 opens -> engines fire.")
print()
print("REGRESSION SAFETY:")
print("  activeCount > 0 -> WARMING (not DEGRADED) when critical missing but count>0.")
print("  Retry loop (5s) continues. WatchdogAdapter restarts dead services.")
print("  Once recovered: criticalAllActive=true + count>=9 -> READY -> G3 unlocks.")
