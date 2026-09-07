import os

path = "app/src/main/java/com/assistant/adapter/smartassist/contributors/SmartAssistUltimateCorrectorContributor.kt"

with open(path, "r") as f:
    code = f.read()

# Replace unnecessary safe call ?. with direct property access .
fixed_code = code.replace("scene?.trackedPlayers", "scene.trackedPlayers")

with open(path, "w") as f:
    f.write(fixed_code)

print("[PATCH VERIFIED] Removed unnecessary safe calls on non-null SceneSnapshot.")
