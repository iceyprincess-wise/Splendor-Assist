#!/bin/bash

# Navigate to the MagneticFeetContributor file
sed -i 's/val magneticRadius = 180.0f/val magneticRadius = 250.0f/g' app/src/main/java/com/assistant/adapter/smartassist/contributors/MagneticFeetContributor.kt

# Navigate to the MagneticFeetEngine file
sed -i 's/val physicsTickNanos = 16_666_666L/val physicsTickNanos = 10_000_000L/g' app/src/main/java/com/assistant/adapter/smartassist/MagneticFeetEngine.kt
sed -i 's/val amplification = 1000000.0f/val amplification = 2000000.0f/g' app/src/main/java/com/assistant/adapter/smartassist/MagneticFeetEngine.kt

# Improve the stabilize method calculations
sed -i 's/val touch = (strength * 0.5f).coerceIn(0f, 10f)/val touch = (strength * 0.7f).coerceIn(0f, 15f)/g' app/src/main/java/com/assistant/adapter/smartassist/MagneticFeetEngine.kt
sed -i 's/val intercept = (pressure * 0.5f).coerceIn(0f, 10f)/val intercept = (pressure * 0.8f).coerceIn(0f, 12f)/g' app/src/main/java/com/assistant/adapter/smartassist/MagneticFeetEngine.kt
sed -i 's/val possession = ((strength + pressure) * 0.25f).coerceIn(0f, 10f)/val possession = ((strength + pressure) * 0.3f).coerceIn(0f, 12f)/g' app/src/main/java/com/assistant/adapter/smartassist/MagneticFeetEngine.kt

# Commit the changes
git add .
git commit -m "Upgrade MagneticFeetContributor and MagneticFeetEngine"

# Push the changes to main
git push origin main
