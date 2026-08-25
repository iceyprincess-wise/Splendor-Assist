#!/usr/bin/env python3
import os, sys

P = "/data/data/com.termux/files/home/projects/Splendor-Assist/app/src/main/java/com/assistant/AppContributorRegistration.kt"

def main():
    print("=== SPLDOR-ASSIST RESET-STORM PATCH (V5) ===")
    if not os.path.exists(P):
        print(f"BLOCKED - not found: {P}"); sys.exit(1)
    with open(P, 'rb') as f: raw = f.read()
    t = raw.decode('utf-8').replace('\r\n', '\n')

    if '"SAUltimateCorrector"' in t and 'lastRetryMs' in t:
        print("PROVEN - already patched (idempotent skip)."); sys.exit(0)

    # A) Name alignment: expected set must match the real engineName.
    oldA = '"TrueShot", "TrueCross", "SmartAssistUltimateCorrector"'
    newA = '"TrueShot", "TrueCross", "SAUltimateCorrector"'
    if t.count(oldA) != 1:
        print("UNVERIFIED - name anchor not unique/present; NO change."); sys.exit(1)
    t = t.replace(oldA, newA, 1)
    print("PROVEN - expected name aligned to SAUltimateCorrector.")

    # B) Bounded retry: never allow a per-frame resetAll() storm again.
    oldB = "    private val state = AtomicReference(RegistrationState.IDLE)"
    newB = oldB + "\n    @Volatile private var lastRetryMs = 0L"
    if t.count(oldB) != 1:
        print("UNVERIFIED - state anchor not unique/present; NO change."); sys.exit(1)
    t = t.replace(oldB, newB, 1)

    oldC = "            if (current == RegistrationState.READY || current == RegistrationState.REGISTERING) return"
    newC = oldC + """
            // ROOT-CAUSE FIX (field logs 2026-08-25): PARTIAL state re-triggered
            // resetAll()+warmAll() on EVERY capture frame (sessionEpoch storm,
            // engines wiped ~1/s, contributions starved). Bound retries to 30s.
            val retryNowMs = System.currentTimeMillis()
            if ((current == RegistrationState.PARTIAL || current == RegistrationState.FAILED) &&
                retryNowMs - lastRetryMs < 30_000L) return
            lastRetryMs = retryNowMs"""
    if t.count(oldC) != 1:
        print("UNVERIFIED - retry anchor not unique/present; NO change."); sys.exit(1)
    t = t.replace(oldC, newC, 1)
    print("PROVEN - PARTIAL/FAILED retry bounded to 30s.")

    out = t.replace('\n', '\r\n') if b'\r\n' in raw else t
    with open(P, 'wb') as f: f.write(out.encode('utf-8'))
    print("=== V5 COMPLETE - run: ./gradlew :app:compileDebugKotlin ===")

if __name__ == "__main__":
    main()
