#!/bin/bash
FILE="$HOME/projects/Splendor-Assist/app/build.gradle.kts"
CURRENT=$(grep -o "versionCode = [0-9]*" "$FILE" | grep -o "[0-9]*")
NEW=$((CURRENT + 1))
sed -i "s/versionCode = $CURRENT/versionCode = $NEW/" "$FILE"
echo "versionCode: $CURRENT -> $NEW"
