#!/usr/bin/env python3
import os
import subprocess

REPO_DIR = "."
OVERLAY_PATH = os.path.join(REPO_DIR, "app/src/main/java/com/assistant/OverlayService.kt")

print("Injecting missing SuppressLint import into OverlayService.kt...")

if os.path.exists(OVERLAY_PATH):
    with open(OVERLAY_PATH, "r") as f:
        content = f.read()
    
    if "import android.annotation.SuppressLint" not in content:
        # Inject after the first import statement to keep imports grouped
        content = content.replace("import com.assistant.diagnostic.RuntimeLogger", "import android.annotation.SuppressLint\nimport com.assistant.diagnostic.RuntimeLogger", 1)
        with open(OVERLAY_PATH, "w") as f:
            f.write(content)
        print("[PASS] Injected import android.annotation.SuppressLint.")
    else:
        print("[WARN] Import already present.")
else:
    print("[FAIL] OverlayService.kt not found.")

print("\nRunning MANDATORY VERIFICATION STEP (compileDebugKotlin)...")
res = subprocess.run(["./gradlew", "compileDebugKotlin", "--no-daemon"], capture_output=True, text=True, timeout=600)
output = res.stdout + res.stderr

if "BUILD SUCCESSFUL" in output:
    print("[SUCCESS] Kotlin compilation passed with 0 errors.")
else:
    print("[FAIL] Kotlin compilation failed. Remaining errors:")
    errors = [line for line in output.split('\n') if line.startswith("e: ")]
    for e in errors[:10]:
        print(f"  - {e}")
