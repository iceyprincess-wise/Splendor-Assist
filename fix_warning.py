#!/usr/bin/env python3
import os
import sys

project_root = os.path.expanduser('~/projects/Splendor-Assist')
if not os.path.exists(project_root):
    project_root = os.path.expanduser('~/projects/SPLENDOR-ASSIST')
if not os.path.exists(project_root):
    print("ERROR: Could not find project directory")
    sys.exit(1)

file_path = os.path.join(project_root, "app/src/main/java/com/assistant/overlay/interceptor/OmnipotentGoalkeeperEngine.kt")

if not os.path.exists(file_path):
    print(f"ERROR: File not found: {file_path}")
    sys.exit(1)

with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

old_str = """        var defensiveActionPerformed = false
        run {
            val t = TelemetryRepository.current()

            defensiveActionPerformed = processGoalkeeperDefensiveLayer("""

new_str = """        val defensiveActionPerformed = run {
            val t = TelemetryRepository.current()

            processGoalkeeperDefensiveLayer("""

if old_str not in content:
    print("ERROR: Could not find the exact string to replace. File may already be fixed or altered.")
    sys.exit(1)

content = content.replace(old_str, new_str, 1)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)

print("SUCCESS: Fixed redundant initializer warning in OmnipotentGoalkeeperEngine.kt")
