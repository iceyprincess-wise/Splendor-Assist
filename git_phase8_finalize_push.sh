#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
OUTDIR="/sdcard/SplendorAssist-Audits"
OUTFILE="$OUTDIR/PHASE8_GIT_FINALIZE_PUSH.txt"

mkdir -p "$OUTDIR"
cd "$ROOT"

{
echo "================ PHASE 8 GIT FINALIZE ================="
echo "DATE: $(date)"
echo

echo "================ CURRENT BRANCH ================="
git branch --show-current
echo

echo "================ REMOTES ================="
git remote -v
echo

echo "================ STATUS (BEFORE) ================="
git status --short
echo
git status
echo

echo "================ STAGE ================="
git add -A
echo

echo "================ STATUS (STAGED) ================="
git status --short
echo

echo "================ COMMIT ================="
if git diff --cached --quiet; then
    echo "Nothing to commit."
else
    git commit -m "Phase 8: Vision-derived adaptive authority for pass/cross/shoot selection"
fi
echo

echo "================ STATUS (AFTER COMMIT) ================="
git status --short
echo
git status
echo

echo "================ PUSH TO MAIN ================="
CURRENT_BRANCH="$(git branch --show-current)"

if [ "$CURRENT_BRANCH" != "main" ]; then
    git checkout main
fi

git merge --ff-only "${CURRENT_BRANCH}" || true
git push origin main
echo

echo "================ FINAL STATUS ================="
git status --short
echo
git status
echo

echo "================ LAST 5 COMMITS ================="
git log --oneline -5
echo

echo "================ DONE ================="

} | tee "$OUTFILE"

echo
echo "REPORT SAVED:"
echo "$OUTFILE"
