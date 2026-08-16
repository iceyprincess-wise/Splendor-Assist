#!/data/data/com.termux/files/usr/bin/bash

set -u

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT" || exit 1

DEATHWATCH="app/src/main/java/com/assistant/DeathWatch.kt"
DIAGNOSIS="app/src/main/java/com/assistant/DiagnosisDetailActivity.kt"
STORAGE="app/src/main/java/com/assistant/storage/SplendorStorageRoot.kt"

STAMP="$(date +%Y%m%d_%H%M%S)"
BACKUP_DIR="$ROOT/storage-repair-backup-$STAMP"

echo "============================================================"
echo " SPLENDOR-ASSIST STORAGE REPAIR"
echo " DEATHWATCH READINESS + DIAGNOSIS FORENSICS"
echo " BACKUP + ATOMIC + FAIL-CLOSED"
echo " NO /tmp"
echo "============================================================"
echo

echo "[1] REPOSITORY STATE"
echo "------------------------------------------------------------"
git status --short
echo "HEAD: $(git rev-parse --short HEAD)"
echo "BRANCH: $(git branch --show-current)"
echo

echo "[2] VERIFY REQUIRED FILES"
echo "------------------------------------------------------------"

for f in "$DEATHWATCH" "$DIAGNOSIS" "$STORAGE"; do
    if [ ! -f "$f" ]; then
        echo "FAILED: missing required file: $f"
        exit 1
    fi
    echo "PROVEN: $f"
done

echo

echo "[3] VERIFY CANONICAL STORAGE OWNER"
echo "------------------------------------------------------------"

grep -q 'object SplendorStorageRoot' "$STORAGE" || {
    echo "FAILED: SplendorStorageRoot object missing"
    exit 1
}

grep -q 'fun isReady()' "$STORAGE" || {
    echo "FAILED: SplendorStorageRoot.isReady() missing"
    exit 1
}

grep -q 'fun directory()' "$STORAGE" || {
    echo "FAILED: SplendorStorageRoot.directory() missing"
    exit 1
}

grep -q 'fun subdirectory(name: String)' "$STORAGE" || {
    echo "FAILED: SplendorStorageRoot.subdirectory() missing"
    exit 1
}

echo "PROVEN: canonical storage owner/API exists"
echo

echo "[4] VERIFY CURRENT DEATHWATCH STARTUP SHAPE"
echo "------------------------------------------------------------"

grep -q 'fun install(ctx: Context)' "$DEATHWATCH" || {
    echo "FAILED: DeathWatch.install() missing"
    exit 1
}

grep -q 'val dir = SplendorStorageRoot.subdirectory("deathwatch")' "$DEATHWATCH" || {
    echo "FAILED: expected canonical DeathWatch directory call missing"
    exit 1
}

echo "PROVEN: current canonical DeathWatch routing exists"

if grep -qE \
'getExternalFilesDir|Environment\.getExternalStoragePublicDirectory|c\.filesDir|/sdcard/|/storage/emulated/0/' \
"$DEATHWATCH"; then
    echo "FAILED: prohibited DeathWatch storage route remains"
    grep -nE \
    'getExternalFilesDir|Environment\.getExternalStoragePublicDirectory|c\.filesDir|/sdcard/|/storage/emulated/0/' \
    "$DEATHWATCH"
    exit 1
fi

echo "PROVEN: DeathWatch has no prohibited fallback storage route"
echo

echo "[5] VERIFY DIAGNOSIS LEGACY PATHS"
echo "------------------------------------------------------------"

COUNT="$(grep -c '/storage/emulated/0/SplendorAssist/Forensics/' "$DIAGNOSIS" || true)"

if [ "$COUNT" -ne 4 ]; then
    echo "FAILED: expected exactly 4 legacy DiagnosisDetailActivity paths; found $COUNT"
    exit 1
fi

echo "PROVEN: exactly 4 legacy DiagnosisDetailActivity Forensics paths found"
grep -n '/storage/emulated/0/SplendorAssist/Forensics/' "$DIAGNOSIS"
echo

echo "[6] CREATE BACKUP"
echo "------------------------------------------------------------"

mkdir -p "$BACKUP_DIR" || {
    echo "FAILED: cannot create backup directory"
    exit 1
}

cp -p "$DEATHWATCH" "$BACKUP_DIR/DeathWatch.kt" || exit 1
cp -p "$DIAGNOSIS" "$BACKUP_DIR/DiagnosisDetailActivity.kt" || exit 1
cp -p "$STORAGE" "$BACKUP_DIR/SplendorStorageRoot.kt" || exit 1

echo "PROVEN: backup created:"
echo "$BACKUP_DIR"
echo

echo "[7] ATOMIC PYTHON PATCH"
echo "------------------------------------------------------------"

python3 - "$DEATHWATCH" "$DIAGNOSIS" <<'PY'
from pathlib import Path
import os
import sys
import tempfile

deathwatch = Path(sys.argv[1])
diagnosis = Path(sys.argv[2])

dw = deathwatch.read_text(encoding="utf-8")
dg = diagnosis.read_text(encoding="utf-8")

# ------------------------------------------------------------
# DeathWatch:
# 1. Do not mark installed before storage readiness.
# 2. Fail closed when canonical storage is unavailable.
# 3. Do not add any fallback.
# 4. Return cleanly so startup itself does not crash.
# 5. Leave installed=false so a later retry can succeed.
# ------------------------------------------------------------

old_dw = '''    fun install(ctx: Context) {
        if (installed) return
        installed = true

        val c = ctx.applicationContext
        procName = resolveProcessName(c)
        startedMs = System.currentTimeMillis()

        val dir = SplendorStorageRoot.subdirectory("deathwatch")
'''

new_dw = '''    fun install(ctx: Context) {
        if (installed) return

        val c = ctx.applicationContext

        if (!SplendorStorageRoot.isReady()) {
            log("DeathWatch not armed: canonical storage is not ready")
            return
        }

        installed = true
        procName = resolveProcessName(c)
        startedMs = System.currentTimeMillis()

        val dir = SplendorStorageRoot.subdirectory("deathwatch")
'''

count_dw = dw.count(old_dw)

if count_dw != 1:
    print(
        f"FAILED: expected exactly 1 DeathWatch startup anchor; found {count_dw}"
    )
    sys.exit(1)

dw = dw.replace(old_dw, new_dw, 1)

# ------------------------------------------------------------
# DiagnosisDetailActivity:
# Replace ONLY the four hardcoded Forensics paths.
# ------------------------------------------------------------

old_diag = '''        files += File("/storage/emulated/0/SplendorAssist/Forensics/execution_chain.log")
        files += File("/storage/emulated/0/SplendorAssist/Forensics/telemetry.log")
        files += File("/storage/emulated/0/SplendorAssist/Forensics/heartbeat.log")
        files += File("/storage/emulated/0/SplendorAssist/Forensics/fieldtest.log")
'''

new_diag = '''        val forensicsDir = SplendorStorageRoot.subdirectory("Forensics")
        files += File(forensicsDir, "execution_chain.log")
        files += File(forensicsDir, "telemetry.log")
        files += File(forensicsDir, "heartbeat.log")
        files += File(forensicsDir, "fieldtest.log")
'''

count_diag = dg.count(old_diag)

if count_diag != 1:
    print(
        f"FAILED: expected exactly 1 Diagnosis Forensics block; found {count_diag}"
    )
    sys.exit(1)

# Verify the import is not already present twice.
import_line = "import com.assistant.storage.SplendorStorageRoot"

if import_line not in dg:
    marker = "package com.assistant\n"
    if marker not in dg:
        print("FAILED: DiagnosisDetailActivity package anchor missing")
        sys.exit(1)
    dg = dg.replace(
        marker,
        marker + "\n" + import_line + "\n",
        1
    )

dg = dg.replace(old_diag, new_diag, 1)

# ------------------------------------------------------------
# Atomic writer
# ------------------------------------------------------------

def atomic_write(path: Path, content: str):
    fd, tmp = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".atomic",
        dir=str(path.parent)
    )

    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as f:
            f.write(content)
            f.flush()
            os.fsync(f.fileno())

        os.replace(tmp, path)
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise

atomic_write(deathwatch, dw)
atomic_write(diagnosis, dg)

print("PROVEN: DeathWatch atomically patched")
print("PROVEN: DiagnosisDetailActivity atomically patched")
PY

status=$?

if [ "$status" -ne 0 ]; then
    echo
    echo "PATCH ABORTED: source files were not modified by the failed patch operation."
    echo "Backup retained: $BACKUP_DIR"
    exit "$status"
fi

echo

echo "[8] STRUCTURAL POST-PATCH VERIFICATION"
echo "------------------------------------------------------------"

echo "--- DeathWatch startup ---"

grep -nE \
'fun install|if \(installed\)|SplendorStorageRoot\.isReady|installed = true|subdirectory\("deathwatch"\)' \
"$DEATHWATCH"

echo

READY_COUNT="$(grep -c 'if (!SplendorStorageRoot.isReady())' "$DEATHWATCH" || true)"

if [ "$READY_COUNT" -ne 1 ]; then
    echo "FAILED: expected exactly 1 canonical storage readiness gate; found $READY_COUNT"
    exit 1
fi

INSTALLED_LINE="$(grep -n 'installed = true' "$DEATHWATCH" | head -n 1 | cut -d: -f1)"
READY_LINE="$(grep -n 'if (!SplendorStorageRoot.isReady())' "$DEATHWATCH" | head -n 1 | cut -d: -f1)"

if [ -z "$INSTALLED_LINE" ] || [ -z "$READY_LINE" ]; then
    echo "FAILED: could not determine readiness/install ordering"
    exit 1
fi

if [ "$INSTALLED_LINE" -le "$READY_LINE" ]; then
    echo "FAILED: installed=true occurs before readiness gate"
    exit 1
fi

echo "PROVEN: readiness gate occurs before installed=true"
echo "PROVEN: no fallback was introduced"

if grep -qE \
'getExternalFilesDir|Environment\.getExternalStoragePublicDirectory|c\.filesDir|/sdcard/|/storage/emulated/0/' \
"$DEATHWATCH"; then
    echo "FAILED: prohibited DeathWatch route remains"
    exit 1
fi

echo "PROVEN: DeathWatch canonical-only storage routing"

echo
echo "--- DiagnosisDetailActivity ---"

grep -nE \
'SplendorStorageRoot|forensicsDir|execution_chain|telemetry|heartbeat|fieldtest' \
"$DIAGNOSIS"

if grep -q '/storage/emulated/0/SplendorAssist/Forensics/' "$DIAGNOSIS"; then
    echo "FAILED: hardcoded Diagnosis Forensics path remains"
    exit 1
fi

echo "PROVEN: hardcoded Diagnosis Forensics paths removed"

echo

echo "[9] SECOND WRITER / READ-PATH AUDIT"
echo "------------------------------------------------------------"

echo "--- Legacy public-storage APIs ---"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --include='*.xml' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  'DIRECTORY_DOWNLOADS|DIRECTORY_DOCUMENTS|DIRECTORY_PICTURES|DIRECTORY_MOVIES|DIRECTORY_DCIM|DIRECTORY_MUSIC|DIRECTORY_ALARMS|DIRECTORY_RINGTONES|Environment\.getExternalStoragePublicDirectory' \
  app adapter_* 2>/dev/null || true

echo
echo "--- Hardcoded external paths ---"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --include='*.xml' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  '/sdcard/|/storage/emulated/0/|/storage/emulated/|/mnt/runtime/' \
  app adapter_* 2>/dev/null || true

echo
echo "--- App-specific external storage APIs ---"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  'getExternalFilesDir|getExternalCacheDir|getExternalMediaDirs|getExternalStorageDirectory|getExternalStorageState' \
  app adapter_* 2>/dev/null || true

echo
echo "--- Direct writers ---"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  'writeText|appendText|writeBytes|FileOutputStream|FileWriter|OutputStreamWriter|BufferedWriter|PrintWriter|RandomAccessFile|openFileOutput|createNewFile|\.outputStream\s*\(|\.bufferedWriter\s*\(' \
  app adapter_* 2>/dev/null || true

echo
echo "--- Canonical storage references ---"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  'SplendorStorageRoot' \
  app adapter_* 2>/dev/null || true

echo
echo "--- Diagnosis Forensics references ---"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  'Forensics|execution_chain\.log|telemetry\.log|heartbeat\.log|fieldtest\.log' \
  app adapter_* 2>/dev/null || true

echo
echo "[10] INTEGRITY CHECK"
echo "------------------------------------------------------------"

if ! git diff --check; then
    echo "FAILED: git diff --check"
    exit 1
fi

echo "PROVEN: git diff --check passed"

echo
echo "Working tree:"
git status --short

echo
echo "Changed files:"
git diff --name-only

echo
echo "============================================================"
echo " REPAIR COMPLETE — NOT COMMITTED"
echo "============================================================"
echo "Backup:"
echo "$BACKUP_DIR"
echo
echo "Review with:"
echo "git diff -- app/src/main/java/com/assistant/DeathWatch.kt"
echo "git diff -- app/src/main/java/com/assistant/DiagnosisDetailActivity.kt"
echo
echo "No commit or push was performed."
echo "============================================================"
