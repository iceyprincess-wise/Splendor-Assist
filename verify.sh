#!/bin/bash
set -e

echo "=== EXTRACTING AND VERIFYING .SO DIRECTLY FROM APK ==="
VERIFY_DIR="$TMPDIR/apk_verify"
rm -rf "$VERIFY_DIR"
mkdir -p "$VERIFY_DIR"
unzip -o app/build/outputs/apk/debug/app-debug.apk "lib/arm64-v8a/libsplendor_native.so" -d "$VERIFY_DIR" > /dev/null 2>&1

if [ -f "$VERIFY_DIR/lib/arm64-v8a/libsplendor_native.so" ]; then
    echo "SUCCESS: libsplendor_native.so is packaged inside the APK."
    echo "=== FINAL SYMBOL VERIFICATION (PROOF OF NATIVE BINDING IN APK) ==="
    readelf -s "$VERIFY_DIR/lib/arm64-v8a/libsplendor_native.so" | grep -E "Java_com_assistant_NativeBridge" | grep -iE "(Native|NativeBridge|Thermal|Input|Predictive)"
else
    echo "FATAL: libsplendor_native.so is MISSING from the APK."
fi
