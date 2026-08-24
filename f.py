#!/usr/bin/env python3
import os
import sys

DASHBOARD_PATH = "/data/data/com.termux/files/home/projects/Splendor-Assist/app/src/main/java/com/assistant/DashboardInjector.kt"
MAGNETIC_PATH = "/data/data/com.termux/files/home/projects/Splendor-Assist/app/src/main/java/com/assistant/adapter/smartassist/contributors/MagneticFeetContributor.kt"

def apply_patch(path, old_str, new_str, file_desc):
    if not os.path.exists(path):
        print(f"BLOCKED - {file_desc} not found at {path}")
        return False
    
    try:
        with open(path, 'rb') as f:
            raw_content = f.read()
        content = raw_content.decode('utf-8')
    except Exception as e:
        print(f"BLOCKED - Failed to read {file_desc}: {e}")
        return False
        
    # Normalize line endings for matching to avoid CRLF vs LF issues
    normalized_content = content.replace('\r\n', '\n').replace('\r', '\n')
    normalized_old = old_str.replace('\r\n', '\n').replace('\r', '\n')
    normalized_new = new_str.replace('\r\n', '\n').replace('\r', '\n')
        
    if normalized_old not in normalized_content:
        if normalized_new in normalized_content:
            print(f"PROVEN - {file_desc} already patched.")
            return True
        print(f"UNVERIFIED - Exact anchor match failed for {file_desc}. No changes applied.")
        return False
        
    patched_normalized = normalized_content.replace(normalized_old, normalized_new, 1)
    
    # Restore original line endings if the file was CRLF
    if b'\r\n' in raw_content:
        patched_final = patched_normalized.replace('\n', '\r\n')
    else:
        patched_final = patched_normalized
        
    try:
        with open(path, 'wb') as f:
            f.write(patched_final.encode('utf-8'))
        print(f"PROVEN - {file_desc} patched successfully.")
        return True
    except Exception as e:
        print(f"BLOCKED - Failed to write {file_desc}: {e}")
        return False

def main():
    print("=== SPLDOR-ASSIST PATCH EXECUTION INITIATED (V2 - CRLF SAFE) ===")
    
    # 1. DashboardInjector.kt Fix (Type Mismatch: Any vs Boolean)
    # Exact 16-space indentation verified from GitHub raw main branch
    d_old = """                val snapshot = BoosterIgnition.fleetSnapshot()
                val state = snapshot["state"] ?: "COLD"
                val ignited = snapshot["ignited"] ?: false
                val degraded = snapshot["fleetDegraded"] ?: false"""
            
    d_new = """                val snapshot = BoosterIgnition.fleetSnapshot()
                val state = snapshot["state"] as? String ?: "COLD"
                val ignited = snapshot["ignited"] as? Boolean ?: false
                val degraded = snapshot["fleetDegraded"] as? Boolean ?: false"""
            
    d_ok = apply_patch(DASHBOARD_PATH, d_old, d_new, "DashboardInjector.kt")
    
    # 2. MagneticFeetContributor.kt Fix (Unresolved min() + Plus Overload Ambiguity)
    # Already applied by user, but included for completeness and idempotency
    m_old = """            if (speed > 8f) {
                val extra = ((min(speed, 15f) - 8f) / 7f * 10f).toLong()
                durationHintMs = (durationHintMs + extra).coerceAtMost(95L)
            }"""
            
    m_new = """            if (speed > 8f) {
                val extra = ((minOf(speed, 15f) - 8f) / 7f * 10f).toLong()
                durationHintMs = (durationHintMs + extra).coerceAtMost(95L)
            }"""
            
    m_ok = apply_patch(MAGNETIC_PATH, m_old, m_new, "MagneticFeetContributor.kt")
    
    if d_ok and m_ok:
        print("\n=== PATCH SCRIPT COMPLETED SUCCESSFULLY ===")
        print("Run './gradlew assembleDebug' to verify compilation.")
        sys.exit(0)
    else:
        print("\n=== PATCH SCRIPT ENCOUNTERED ISSUES ===")
        sys.exit(1)

if __name__ == "__main__":
    main()
