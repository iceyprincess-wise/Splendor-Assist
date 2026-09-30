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
    readelf -Ws "$VERIFY_DIR/lib/arm64-v8a/libsplendor_native.so" | grep -E "Java_com_assistant_NativeBridge(_|$)" | tee "$VERIFY_DIR/native_jni_symbols.txt"
NATIVE_JNI_COUNT=$(grep -c "Java_com_assistant_NativeBridge" "$VERIFY_DIR/native_jni_symbols.txt" || true)
echo "PROVEN: NativeBridge JNI symbols found in APK .so: $NATIVE_JNI_COUNT"
[ "$NATIVE_JNI_COUNT" -gt 0 ] || { echo "FATAL: No NativeBridge JNI symbols found in APK .so."; exit 1; }
else
    echo "FATAL: libsplendor_native.so is MISSING from the APK."
fi

echo "=== APK VERSION VERIFICATION ==="

APK="app/build/outputs/apk/debug/app-debug.apk"

APK_PACKAGE=$(aapt2 dump badging "$APK" 2>/dev/null | grep -oE "name='[^']+'" | head -1 | cut -d"'" -f2)
APK_VERSION_CODE=$(aapt2 dump badging "$APK" 2>/dev/null | grep -oE "versionCode='[^']+'" | head -1 | grep -oE "[0-9]+")
APK_VERSION_NAME=$(aapt2 dump badging "$APK" 2>/dev/null | grep -oE "versionName='[^']+'" | head -1 | cut -d"'" -f2)

SOURCE_VERSION_CODE=$(grep -oE 'versionCode[[:space:]]*=[[:space:]]*[0-9]+' app/build.gradle.kts 2>/dev/null | grep -oE '[0-9]+' | head -1)
SOURCE_VERSION_NAME=$(grep -oE 'versionName[[:space:]]*=[[:space:]]*"[^"]+"' app/build.gradle.kts 2>/dev/null | cut -d'"' -f2)

echo "APK package: $APK_PACKAGE"
echo "APK versionCode: $APK_VERSION_CODE"
echo "APK versionName: $APK_VERSION_NAME"

if [ "$APK_VERSION_CODE" = "$SOURCE_VERSION_CODE" ]; then
    echo "SUCCESS: APK versionCode matches app/build.gradle.kts."
else
    echo "FATAL: APK versionCode does not match source."
    exit 1
fi

if [ "$APK_VERSION_NAME" = "$SOURCE_VERSION_NAME" ]; then
    echo "SUCCESS: APK versionName matches app/build.gradle.kts."
else
    echo "FATAL: APK versionName does not match source."
    exit 1
fi

echo "=== KOTLIN / DEX VERIFICATION ==="

unzip -o "$APK" 'classes*.dex' -d "$VERIFY_DIR" > /dev/null 2>&1

has_dex_class() {
    local CLASS_NAME="$1"

    while IFS= read -r -d '' DEX; do
        if grep -a -F -q "L${CLASS_NAME};" "$DEX"; then
            return 0
        fi
    done < <(find "$VERIFY_DIR" -name 'classes*.dex' -type f -print0)

    return 1
}

echo "=== KOTLIN / DEX FULL SOURCE COVERAGE ==="

EXPECTED_KOTLIN="$VERIFY_DIR/expected_kotlin_classes.txt"
FOUND_KOTLIN="$VERIFY_DIR/found_kotlin_classes.txt"
MISSING_KOTLIN="$VERIFY_DIR/missing_kotlin_classes.txt"

: > "$EXPECTED_KOTLIN"
: > "$FOUND_KOTLIN"
: > "$MISSING_KOTLIN"

while IFS= read -r FILE; do
    PACKAGE=$(grep -m1 -oE '^package[[:space:]]+[A-Za-z0-9_.]+' "$FILE" 2>/dev/null | awk '{print $2}')
    [ -n "$PACKAGE" ] || continue

    grep -E \
        '^[[:space:]]*(public[[:space:]]+|internal[[:space:]]+|private[[:space:]]+|protected[[:space:]]+|abstract[[:space:]]+|final[[:space:]]+|open[[:space:]]+|sealed[[:space:]]+|data[[:space:]]+|value[[:space:]]+|enum[[:space:]]+|annotation[[:space:]]+)*((class)|(object)|(interface))[[:space:]]+[A-Za-z_][A-Za-z0-9_]*' \
        "$FILE" 2>/dev/null \
        | grep -oE '((class)|(object)|(interface))[[:space:]]+[A-Za-z_][A-Za-z0-9_]*' \
        | awk '{print $2}' \
        | while IFS= read -r NAME; do
            printf '%s/%s\n' "${PACKAGE//./\/}" "$NAME"
          done >> "$EXPECTED_KOTLIN"
done < <(
    find app/src/main/java core/src/main/java \
        -type f -name '*.kt' 2>/dev/null | sort
)

sort -u "$EXPECTED_KOTLIN" -o "$EXPECTED_KOTLIN"

TOTAL_KOTLIN=0
FOUND_KOTLIN_COUNT=0
MISSING_KOTLIN_COUNT=0

while IFS= read -r CLASS_NAME; do
    [ -n "$CLASS_NAME" ] || continue

    TOTAL_KOTLIN=$((TOTAL_KOTLIN + 1))

    if has_dex_class "$CLASS_NAME"; then
        printf '%s\n' "$CLASS_NAME" >> "$FOUND_KOTLIN"
    else
        printf '%s\n' "$CLASS_NAME" >> "$MISSING_KOTLIN"
        MISSING_KOTLIN_COUNT=$((MISSING_KOTLIN_COUNT + 1))
    fi
done < "$EXPECTED_KOTLIN"

FOUND_KOTLIN_COUNT=$(wc -l < "$FOUND_KOTLIN" | tr -d ' ')

echo "SOURCE KOTLIN DECLARATIONS: $TOTAL_KOTLIN"
echo "APK KOTLIN CLASSES FOUND: $FOUND_KOTLIN_COUNT"
echo "APK KOTLIN CLASSES MISSING: $MISSING_KOTLIN_COUNT"

if [ "$MISSING_KOTLIN_COUNT" -eq 0 ]; then
    echo "PROVEN: All discovered Kotlin class/object/interface declarations are present in APK DEX."
else
    echo "FATAL: Kotlin declarations missing from APK DEX:"
    cat "$MISSING_KOTLIN"
    exit 1
fi

echo "SUCCESS: Kotlin/Dex engine class verification passed."

echo "=== APK BINARY IDENTITY ==="
echo "APK SHA256:"
sha256sum "$APK"
echo "APK SIZE:"
ls -lh "$APK"
