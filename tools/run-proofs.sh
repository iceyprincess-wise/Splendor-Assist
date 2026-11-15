#!/bin/sh
# Run every proof in tools/. Exit non-zero if any proof fails.
# Usage: sh tools/run-proofs.sh   (from the repo root)
# The JNI proof needs a JDK: set JAVA_HOME, or let it fall back to ~/jdk17.
set -u
cd "$(dirname "$0")/.." || exit 1
echo "== tools/verify-termux-aapt2.py =="
python3 tools/verify-termux-aapt2.py || exit 1
echo "== tools/verify-native-contracts.py =="
if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/jdk17" ]; then
    JAVA_HOME="$HOME/jdk17"
    export JAVA_HOME
fi
python3 tools/verify-native-contracts.py || exit 1
echo "ALL PROOFS PASS"
