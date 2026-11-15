#!/bin/sh
# Run every proof in tools/. Exit non-zero if any proof fails.
# Usage: sh tools/run-proofs.sh   (from the repo root)
# The JNI proof needs a JDK: set JAVA_HOME, or let it fall back to ~/jdk17.
set -u
cd "$(dirname "$0")/.." || exit 1
echo "== gradlew sources the Termux aapt2 wrapper =="
if grep -q 'tools/termux-aapt2.sh' gradlew; then
    echo "PASS gradlew sources tools/termux-aapt2.sh"
else
    echo "FAIL gradlew no longer sources tools/termux-aapt2.sh (regenerated? re-apply the Termux block)" >&2
    exit 1
fi
echo "== tools/verify-termux-aapt2.py =="
python3 tools/verify-termux-aapt2.py || exit 1
echo "== tools/verify-native-contracts.py =="
if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/jdk17" ]; then
    JAVA_HOME="$HOME/jdk17"
    export JAVA_HOME
fi
python3 tools/verify-native-contracts.py || exit 1
echo "ALL PROOFS PASS"
