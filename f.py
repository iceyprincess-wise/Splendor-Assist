import os
import subprocess

# Navigate to the MagneticFeetContributor file
with open("app/src/main/java/com/assistant/adapter/smartassist/contributors/MagneticFeetContributor.kt", "r+") as f:
    content = f.read()
    content = content.replace("val possessionWeight = 1.0f", "val possessionWeight = 1.5f")
    content = content.replace("val trustWeight = 1.0f", "val trustWeight = 1.2f")
    content = content.replace("val cap = 1.0f", "val cap = 1.2f")
    content = content.replace("val rawAuthority = (result.touchRetention / 10f) * amplification", "val rawAuthority = (result.touchRetention / 5f) * amplification")
    content = content.replace("val authority = (rawAuthority * fluidMultiplier).coerceIn(0.75f, cap)", "val authority = (rawAuthority * fluidMultiplier).coerceIn(0.9f, cap)")
    f.seek(0)
    f.write(content)
    f.truncate()

# Navigate to the MagneticFeetEngine file
with open("app/src/main/java/com/assistant/adapter/smartassist/MagneticFeetEngine.kt", "r+") as f:
    content = f.read()
    content = content.replace("val calculatedTouch = 10.0f + (pressure - pressure) + (strength - strength)", "val calculatedTouch = 15.0f + (pressure * 0.1f) + (strength * 0.1f)")
    content = content.replace("val interceptionResistance = 10.0f", "val interceptionResistance = 12.0f")
    content = content.replace("val possessionControl = 10.0f", "val possessionControl = 12.0f")
    content = content.replace("val amplification = (1.0f + dummySynergy).coerceIn(1.0f, 1.0f)", "val amplification = (1.2f + dummySynergy).coerceIn(1.0f, 1.2f)")
    f.seek(0)
    f.write(content)
    f.truncate()

# Commit the changes
subprocess.run(["git", "add", "."])
subprocess.run(["git", "commit", "-m", "Upgrade MagneticFeetContributor and MagneticFeetEngine"])

# Push the changes to main
subprocess.run(["git", "push", "origin", "main"])
