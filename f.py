#!/usr/bin/env python3
import os

file_path = "app/src/main/java/com/assistant/adapter/smartassist/MagneticFeetEngine.kt"

if not os.path.exists(file_path):
    print(f"Error: {file_path} not found.")
    exit(1)

with open(file_path, 'r') as f:
    content = f.read()

patch_payload = """
    data class MagneticFeetResult(
        val touchRetention: Float = 0.0f,
        val interceptionResistance: Float = 0.0f,
        val possessionControl: Float = 0.0f
    )

    data class MagneticFeetState(
        val sequence: Long = 0L,
        val amplification: Float = 1000000.0f,
        val result: MagneticFeetResult = MagneticFeetResult()
    )

    data class MagneticFeetDiagnostics(
        val calls: Long = 0L,
        val lastPressure: Int = 0,
        val lastStrength: Int = 0,
        val lastReason: String = "none",
        val lastUpdatedMs: Long = 0L
    )

    companion object {
        private var sequence: Long = 0L
        private var calls: Long = 0L
        private var lastPressure: Int = 0
        private var lastStrength: Int = 0
        private var lastReason: String = "none"
        private var lastUpdatedMs: Long = 0L

        fun stabilize(pressure: Int, strength: Int): MagneticFeetResult {
            calls++
            lastPressure = pressure
            lastStrength = strength
            lastReason = "stabilized"
            lastUpdatedMs = System.currentTimeMillis()
            
            val touch = (strength * 0.5f).coerceIn(0f, 10f)
            val intercept = (pressure * 0.5f).coerceIn(0f, 10f)
            val possession = ((strength + pressure) * 0.25f).coerceIn(0f, 10f)
            
            return MagneticFeetResult(touch, intercept, possession)
        }

        fun reset() {
            sequence = 0L
            calls = 0L
            lastPressure = 0
            lastStrength = 0
            lastReason = "none"
            lastUpdatedMs = 0L
        }

        fun magneticFeetSnapshot(): MagneticFeetState? {
            return MagneticFeetState(
                sequence = sequence,
                amplification = 1000000.0f,
                result = stabilize(lastPressure, lastStrength)
            )
        }

        fun magneticFeetActivationDiagnostics(): MagneticFeetDiagnostics {
            return MagneticFeetDiagnostics(
                calls = calls,
                lastPressure = lastPressure,
                lastStrength = lastStrength,
                lastReason = lastReason,
                lastUpdatedMs = lastUpdatedMs
            )
        }
    }
}
"""

if "companion object" not in content:
    last_brace_idx = content.rfind("}")
    if last_brace_idx != -1:
        content = content[:last_brace_idx] + patch_payload
        with open(file_path, 'w') as f:
            f.write(content)
        print(f"Successfully patched {file_path}")
    else:
        print("Error: Could not find closing brace in target file.")
else:
    print("Patch already applied or companion object exists.")
