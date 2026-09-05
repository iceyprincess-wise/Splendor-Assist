import os
import subprocess

# Navigate to the MagneticFeetContributor file
with open("app/src/main/java/com/assistant/adapter/smartassist/contributors/MagneticFeetContributor.kt", "r+") as f:
    content = f.read()
    content = content.replace("val magneticRadius = 180.0f", "val magneticRadius = 250.0f")
    f.seek(0)
    f.write(content)
    f.truncate()

# Navigate to the MagneticFeetEngine file
with open("app/src/main/java/com/assistant/adapter/smartassist/MagneticFeetEngine.kt", "r+") as f:
    content = f.read()
    content = content.replace("val physicsTickNanos = 16_666_666L", "val physicsTickNanos = 10_000_000L")
    content = content.replace("val amplification = 1000000.0f", "val amplification = 2000000.0f")
    content = content.replace("val touch = (strength * 0.5f).coerceIn(0f, 10f)", "val touch = (strength * 0.7f).coerceIn(0f, 15f)")
    content = content.replace("val intercept = (pressure * 0.5f).coerceIn(0f, 10f)", "val intercept = (pressure * 0.8f).coerceIn(0f, 12f)")
    content = content.replace("val possession = ((strength + pressure) * 0.25f).coerceIn(0f, 10f)", "val possession = ((strength + pressure) * 0.3f).coerceIn(0f, 12f)")
    f.seek(0)
    f.write(content)
    f.truncate()

# Commit the changes
subprocess.run(["git", "add", "."])
subprocess.run(["git", "commit", "-m", "Upgrade MagneticFeetContributor and MagneticFeetEngine"])

# Push the changes to main
subprocess.run(["git", "push", "origin", "main"])
