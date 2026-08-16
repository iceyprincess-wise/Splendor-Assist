#!/data/data/com.termux/files/usr/bin/bash

set -u

ROOT="$HOME/projects/Splendor-Assist"
cd "$ROOT" || exit 1

REPORT="$ROOT/storage-writer-forensic-audit.txt"

{
echo "============================================================"
echo " SPLENDOR-ASSIST LOCAL STORAGE WRITER FORENSIC AUDIT"
echo " READ ONLY — NO SOURCE FILES MODIFIED"
echo " $(date)"
echo "============================================================"
echo

echo "[1] REPOSITORY STATE"
echo "------------------------------------------------------------"
git status --short
echo
echo "HEAD:"
git rev-parse --short HEAD 2>/dev/null || true
echo
echo "BRANCH:"
git branch --show-current 2>/dev/null || true
echo

echo "[2] SOURCE FILE INVENTORY"
echo "------------------------------------------------------------"
find . \
  -type f \
  \( -name '*.kt' -o -name '*.java' -o -name '*.xml' \) \
  -not -path './.git/*' \
  -not -path '*/build/*' \
  -not -path '*/.gradle/*' \
  | sort
echo

echo "[3] CANONICAL STORAGE OWNER REFERENCES"
echo "------------------------------------------------------------"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --include='*.xml' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  'SplendorStorageRoot|ROOT_PATH|Splendor-Assist' \
  . 2>/dev/null || true
echo

echo "[4] LEGACY PUBLIC STORAGE PATHS"
echo "------------------------------------------------------------"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --include='*.xml' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  'DIRECTORY_DOWNLOADS|DIRECTORY_DOCUMENTS|DIRECTORY_PICTURES|DIRECTORY_MOVIES|DIRECTORY_DCIM|DIRECTORY_MUSIC|DIRECTORY_ALARMS|DIRECTORY_RINGTONES|Environment\.getExternalStoragePublicDirectory' \
  . 2>/dev/null || true
echo

echo "[5] HARD-CODED EXTERNAL STORAGE PATHS"
echo "------------------------------------------------------------"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --include='*.xml' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  '/sdcard/|/storage/emulated/0/|/storage/emulated/|/mnt/runtime/' \
  . 2>/dev/null || true
echo

echo "[6] APP-SPECIFIC EXTERNAL STORAGE APIs"
echo "------------------------------------------------------------"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  'getExternalFilesDir|getExternalCacheDir|getExternalMediaDirs|getExternalStorageDirectory|getExternalStorageState' \
  . 2>/dev/null || true
echo

echo "[7] DIRECTORY CREATION"
echo "------------------------------------------------------------"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  '\.mkdirs?\s*\(|createDirectory|createDirectories|Files\.createDirectories' \
  . 2>/dev/null || true
echo

echo "[8] FILE CONSTRUCTION"
echo "------------------------------------------------------------"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  '\bFile\s*\(|java\.io\.File\s*\(|Path\s*\(' \
  . 2>/dev/null || true
echo

echo "[9] DIRECT FILE WRITERS"
echo "------------------------------------------------------------"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  'writeText|appendText|writeBytes|FileOutputStream|FileWriter|OutputStreamWriter|BufferedWriter|PrintWriter|RandomAccessFile|openFileOutput|createNewFile' \
  . 2>/dev/null || true
echo

echo "[10] GENERIC OUTPUT / STREAM WRITERS"
echo "------------------------------------------------------------"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  'OutputStream|Writer\s*\(|\.outputStream\s*\(|\.bufferedWriter\s*\(|\.writer\s*\(' \
  . 2>/dev/null || true
echo

echo "[11] LOGGER INITIALIZATION / FILE LOGGING"
echo "------------------------------------------------------------"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  'RuntimeLogger|Logger|logFile|health.*log|crash.*log|diagnostic.*log|telemetry.*log|heartbeat.*log|forensics.*log|HealLog|appendLine' \
  . 2>/dev/null || true
echo

echo "[12] MEDIASTORE / CONTENTRESOLVER STORAGE"
echo "------------------------------------------------------------"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  'MediaStore|ContentResolver|insert\s*\(|openOutputStream|ParcelFileDescriptor' \
  . 2>/dev/null || true
echo

echo "[13] STORAGE PERMISSIONS / ACCESS STATE"
echo "------------------------------------------------------------"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --include='*.xml' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  'MANAGE_EXTERNAL_STORAGE|isExternalStorageManager|WRITE_EXTERNAL_STORAGE|READ_EXTERNAL_STORAGE|requestLegacyExternalStorage' \
  . 2>/dev/null || true
echo

echo "[14] STORAGE ROOT / STARTUP LIFECYCLE"
echo "------------------------------------------------------------"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  'class App|: Application|onCreate\s*\(|SplendorStorageRoot\.initialize|RuntimeLogger\.initialize|GlobalCrashHandler' \
  app adapter_* 2>/dev/null || true
echo

echo "[15] LOG-LIKE FILENAMES"
echo "------------------------------------------------------------"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  '["'\''][^"'\'']*(log|Log|crash|Crash|health|Health|telemetry|Telemetry|diagnostic|Diagnostic|forensic|Forensic|heal|Heal)[^"'\'']*\.(txt|log|json|csv|xml|dat)["'\'']' \
  . 2>/dev/null || true
echo

echo "[16] SPLENDOR STORAGE ROOT USERS"
echo "------------------------------------------------------------"
grep -RIl \
  --include='*.kt' \
  --include='*.java' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  'SplendorStorageRoot' \
  . 2>/dev/null | sort || true
echo

echo "[17] FALLBACK / CANDIDATE DIRECTORY LOGIC"
echo "------------------------------------------------------------"
grep -RInE \
  --include='*.kt' \
  --include='*.java' \
  --exclude-dir=.git \
  --exclude-dir=build \
  --exclude-dir=.gradle \
  'fallback|Fallback|candidate|candidates|alternative|backup.*directory|secondary.*directory|default.*directory|internal.*storage|external.*storage' \
  app adapter_* 2>/dev/null || true
echo

echo "[18] FINAL INTEGRITY CHECK"
echo "------------------------------------------------------------"
echo "git diff --check:"
git diff --check 2>&1 || true
echo
echo "Working tree:"
git status --short
echo

echo "============================================================"
echo " END LOCAL STORAGE WRITER FORENSIC AUDIT"
echo " REPORT: $REPORT"
echo "============================================================"

} | tee "$REPORT"

echo
echo "============================================================"
echo " AUDIT COMPLETE"
echo " REPORT SAVED:"
echo "$REPORT"
echo "============================================================"
