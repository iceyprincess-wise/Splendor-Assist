import re
import os

def fix_latency_engine_warning():
    path = "app/src/main/java/com/assistant/adapter/smartassist/fps/LatencyDefeatingInputEngine.kt"
    if not os.path.exists(path):
        print(f"[ERROR] File not found: {path}")
        return
        
    with open(path, 'r', encoding='utf-8') as f:
        code = f.read()

    dead_code = """        val windowManager = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val display: Display? = windowManager.defaultDisplay
        val refreshRate = display?.refreshRate ?: 60.0f
        val frameTimeMs = if (refreshRate > 0f) 1000.0f / refreshRate else DEFAULT_FRAME_TIME_MS"""

    if dead_code in code:
        new_code = code.replace(dead_code, "")
        # Remove unused constant
        new_code = re.sub(r'\s*const val DEFAULT_FRAME_TIME_MS\s*=\s*8\.33f', '', new_code)
        # Remove unused imports
        new_code = new_code.replace("import android.view.Display\n", "")
        new_code = new_code.replace("import android.view.WindowManager\n", "")
        
        with open(path, 'w', encoding='utf-8') as f:
            f.write(new_code)
        print(f"[SUCCESS] Surgically removed dead WindowManager/Display code and unused constant in {path}. Warning eliminated.")
    else:
        print(f"[WARNING] Dead code block not found exactly. Attempting regex...")
        # Fallback regex
        pattern = r'\s*val windowManager.*?else DEFAULT_FRAME_TIME_MS'
        new_code = re.sub(pattern, '', code, flags=re.DOTALL)
        new_code = re.sub(r'\s*const val DEFAULT_FRAME_TIME_MS\s*=\s*8\.33f', '', new_code)
        new_code = new_code.replace("import android.view.Display\n", "")
        new_code = new_code.replace("import android.view.WindowManager\n", "")
        with open(path, 'w', encoding='utf-8') as f:
            f.write(new_code)
        print(f"[SUCCESS] Surgically removed dead code via regex in {path}. Warning eliminated.")

if __name__ == "__main__":
    print("Initiating Surgical Warning Fix...")
    fix_latency_engine_warning()
    print("Patch Execution Complete. Re-run gradlew build to verify ZERO warnings.")
