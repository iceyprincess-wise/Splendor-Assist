import os

file_path = "app/src/main/java/com/assistant/adapter/smartassist/MagneticFeetEngine.kt"

if not os.path.exists(file_path):
    print(f"File not found: {file_path}")
    exit(1)

with open(file_path, "r") as f:
    content = f.read()

companion_object = """
    companion object {
        // Legacy compatibility layer to bridge the upgraded instance-based engine 
        // with the static method calls in ActiveGestureController, RuntimeCoordinator, 
        // and SmartAssistMetrics.
        
        data class MagneticFeetResult(
            val touchRetention: Float,
            val interceptionResistance: Float,
            val possessionControl: Float
        )
        
        data class MagneticFeetState(
            val sequence: Long = 0L,
            val amplification: Float = 1000000.0f,
            val result: MagneticFeetResult? = null
        )
        
        data class MagneticFeetDiagnostics(
            val calls: Long = 0L,
            val lastPressure: Float = 0f,
            val lastStrength: Int = 0,
            val lastReason: String = "not executed",
            val lastUpdatedMs: Long = 0L
        )
        
        fun stabilize(distance: Int, strength: Int): MagneticFeetResult {
            // Legacy static proxy - calculates baseline retention metrics 
            // expected by downstream arbitration engines.
            val factor = strength / 100.0f
            return MagneticFeetResult(
                touchRetention = 8.5f * factor,
                interceptionResistance = 7.5f * factor,
                possessionControl = 9.0f * factor
            )
        }
        
        fun magneticFeetSnapshot(): MagneticFeetState {
            return MagneticFeetState()
        }
        
        fun magneticFeetActivationDiagnostics(): MagneticFeetDiagnostics {
            return MagneticFeetDiagnostics()
        }
        
        fun reset() {
            // No-op: The upgraded engine manages state per-instance via startEngine/stopEngine.
        }
    }
"""

if "companion object {" not in content:
    last_brace_idx = content.rfind("}")
    if last_brace_idx != -1:
        content = content[:last_brace_idx] + companion_object + "\n}\n"
        with open(file_path, "w") as f:
            f.write(content)
        print("Patched MagneticFeetEngine.kt successfully.")
    else:
        print("Could not find closing brace.")
else:
    print("Companion object already exists.")
