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
        with open(path, 'r', encoding='utf-8') as f:
            content = f.read()
    except Exception as e:
        print(f"BLOCKED - Failed to read {file_desc}: {e}")
        return False
        
    if old_str not in content:
        print(f"UNVERIFIED - Exact anchor match failed for {file_desc}. No changes applied.")
        return False
        
    patched_content = content.replace(old_str, new_str)
    
    try:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(patched_content)
        print(f"PROVEN - {file_desc} patched successfully.")
        return True
    except Exception as e:
        print(f"BLOCKED - Failed to write {file_desc}: {e}")
        return False

def main():
    print("=== SPLDOR-ASSIST PATCH EXECUTION INITIATED ===")
    
    # 1. DashboardInjector.kt Fix (Type Mismatch: Any vs Boolean)
    d_old = """            val snapshot = BoosterIgnition.fleetSnapshot()
            val state = snapshot["state"] ?: "COLD"
            val ignited = snapshot["ignited"] ?: false
            val degraded = snapshot["fleetDegraded"] ?: false"""
            
    d_new = """            val snapshot = BoosterIgnition.fleetSnapshot()
            val state = snapshot["state"] as? String ?: "COLD"
            val ignited = snapshot["ignited"] as? Boolean ?: false
            val degraded = snapshot["fleetDegraded"] as? Boolean ?: false"""
            
    d_ok = apply_patch(DASHBOARD_PATH, d_old, d_new, "DashboardInjector.kt")
    
    # 2. MagneticFeetContributor.kt Fix (Unresolved min() + Plus Overload Ambiguity)
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
