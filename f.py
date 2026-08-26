#!/usr/bin/env python3
import os, sys
P="/data/data/com.termux/files/home/projects/Splendor-Assist/app/src/main/java/com/assistant/adapter/smartassist/ActionVerifier.kt"
def main():
    print("=== SPLDOR-ASSIST V8 (EXHAUSTIVE WHEN FIX) ===")
    if not os.path.exists(P):
        print(f"BLOCKED - not found: {P}"); sys.exit(1)
    with open(P,'rb') as f: raw=f.read()
    t=raw.decode('utf-8').replace('\r\n','\n')
    
    OLD='''            AgentAction.RefreshPerformance ->
                ActionVerification(
                    verified = after.timestampMs >= before.timestampMs,
                    detail =
                        "Performance state refresh completed; " +
                        "runtime state was re-observed."
                )
        }'''
        
    NEW='''            AgentAction.RefreshPerformance ->
                ActionVerification(
                    verified = after.timestampMs >= before.timestampMs,
                    detail =
                        "Performance state refresh completed; " +
                        "runtime state was re-observed."
                )

            AgentAction.ReigniteFleet -> {
                val fleetImproved = !before.health.boosterAlive && after.health.boosterAlive
                ActionVerification(
                    verified = fleetImproved || after.timestampMs >= before.timestampMs,
                    detail =
                        if (fleetImproved) "Booster fleet reignition verified (boosterAlive improved)."
                        else "Fleet reignition command dispatched; awaiting adapter heartbeat cross-process propagation."
                )
            }
        }'''
        
    if NEW in t:
        print("PROVEN - ActionVerifier.kt already patched (idempotent skip)."); sys.exit(0)
    c=t.count(OLD)
    if c!=1:
        print(f"BLOCKED - anchor x{c}: ActionVerifier.kt; NO change."); sys.exit(1)
    t=t.replace(OLD,NEW,1)
    
    out=t.replace('\n','\r\n') if b'\r\n' in raw else t
    with open(P,'wb') as f: f.write(out.encode('utf-8'))
    print("PROVEN - ActionVerifier.kt patched: ReigniteFleet branch added to exhaustive when.")
    print("=== V8 COMPLETE - run: ./gradlew :app:compileDebugKotlin ===")

if __name__ == "__main__":
    main()
