#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FILE="$SCRIPT_DIR/app/build.gradle.kts"
CURRENT=$(grep -o "versionCode = [0-9]*" "$FILE" | grep -o "[0-9]*")
NEW=$((CURRENT + 1))
sed -i "s/versionCode = $CURRENT/versionCode = $NEW/" "$FILE"
echo "versionCode: $CURRENT -> $NEW"
