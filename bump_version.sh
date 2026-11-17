#!/bin/bash
# bump_version.sh — bump app versionCode by 1 (manual release step).
#
# Usage (from anywhere): ./bump_version.sh
#   Reads app/build.gradle.kts next to this script, increments the
#   single `versionCode = N` line, verifies the write, prints N -> N+1.
#
# No52-D5: versionName is NOT bumped. It is a static label
# ("1.0-SECURE-LOCKED"), not a counter, and nothing in the Kotlin code
# reads versionCode or versionName, so there is nothing to keep in
# sync. Only versionCode is the machine counter.
# No52-D6 (script status): this script is currently UNINVOKED — no
# caller in README/docs/tools, and the repo has no CI. That is
# deliberate: it is a manual release step, and it must NOT be wired
# into tools/run-proofs.sh (proofs must never mutate the version).
# Run it by hand before a release build.
# GNU sed (Termux): -i needs no backup suffix here.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FILE="$SCRIPT_DIR/app/build.gradle.kts"
# No52-D1/D2: fail loudly instead of silently computing from nothing.
if [ ! -f "$FILE" ]; then
    echo "bump_version: not found: $FILE" >&2
    exit 1
fi
# No52-D3: exactly one versionCode line must exist. Today there is one
# (app/build.gradle.kts, versionCode = 218); if a second ever appears,
# the old grep -o pipeline would have glued two numbers together and
# $((CURRENT + 1)) would have failed or mis-bumped.
MATCHES=$(grep -c "^[[:space:]]*versionCode = [0-9][0-9]*" "$FILE" || true)
if [ "$MATCHES" -ne 1 ]; then
    echo "bump_version: expected exactly 1 versionCode line in $FILE, found $MATCHES" >&2
    exit 1
fi
CURRENT=$(grep -o "versionCode = [0-9]*" "$FILE" | grep -o "[0-9]*")
if ! printf '%s' "$CURRENT" | grep -q "^[0-9][0-9]*$"; then
    echo "bump_version: versionCode is not a number: [$CURRENT]" >&2
    exit 1
fi
NEW=$((CURRENT + 1))
# No52-D4: anchored to the start of the line (leading whitespace kept
# via capture group) so the replacement can only hit the versionCode
# assignment, never a comment or another line mentioning it.
sed -i "s/^\([[:space:]]*\)versionCode = $CURRENT/\1versionCode = $NEW/" "$FILE"
# No52-D4 (verify): confirm the write actually landed.
if ! grep -q "^[[:space:]]*versionCode = $NEW" "$FILE"; then
    echo "bump_version: write verification failed (expected versionCode = $NEW)" >&2
    exit 1
fi
echo "versionCode: $CURRENT -> $NEW"
