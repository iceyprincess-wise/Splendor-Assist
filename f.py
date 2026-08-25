#!/usr/bin/env python3
import os, sys

PATH = "/data/data/com.termux/files/home/projects/Splendor-Assist/app/src/main/java/com/assistant/adapter/smartassist/RuntimeCoordinator.kt"

OLD = """        val healthy = try {
            com.assistant.BoosterIgnition.isFleetReady()
        } catch (_: Throwable) { false }"""

NEW = """        // P0-A WIRING FIX (FIELD-STALL ROOT CAUSE, TASK-CLOSURE TRACED):
        // verifyFleetHealth() is the ONLY transition that can promote
        // fleetState WARMING -> READY. Its documented owner is this G3 refresh
        // path ("RuntimeCoordinator calls this to verify fleet quorum before
        // opening the G3 booster gate") but the call was missing, so
        // isFleetReady() stayed false forever and the runtime stalled at
        // G2_CAPTURE_READY (booster-not-ready, bus-idle, execution starved).
        try {
            com.assistant.BoosterIgnition.verifyFleetHealth()
        } catch (_: Throwable) { }

        val healthy = try {
            com.assistant.BoosterIgnition.isFleetReady()
        } catch (_: Throwable) { false }"""

def main():
    print("=== SPLDOR-ASSIST G3 WIRING PATCH (V3) ===")
    if not os.path.exists(PATH):
        print(f"BLOCKED - file not found: {PATH}"); sys.exit(1)
    with open(PATH, 'rb') as f: raw = f.read()
    text = raw.decode('utf-8')
    n = text.replace('\r\n', '\n')
    if "BoosterIgnition.verifyFleetHealth()" in n:
        print("PROVEN - RuntimeCoordinator.kt already patched (idempotent skip)."); sys.exit(0)
    if OLD not in n:
        print("UNVERIFIED - anchor mismatch; no changes applied."); sys.exit(1)
    n = n.replace(OLD, NEW, 1)
    out = n.replace('\n', '\r\n') if b'\r\n' in raw else n
    with open(PATH, 'wb') as f: f.write(out.encode('utf-8'))
    print("PROVEN - RuntimeCoordinator.kt patched: verifyFleetHealth() wired into G3 refresh.")

if __name__ == "__main__":
    main()
