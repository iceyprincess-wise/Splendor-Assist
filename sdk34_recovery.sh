#!/data/data/com.termux/files/usr/bin/bash
set -e

echo "===== SDK ROLLBACK ====="

find . \
-type f \
-name "build.gradle.kts" \
-exec perl -0pi -e '
s/compileSdk\s*=\s*36/compileSdk = 34/g;
s/targetSdk\s*=\s*36/targetSdk = 34/g;
' {} +

perl -0pi -e '
s/android\.suppressUnsupportedCompileSdk=36\n?//g;
' gradle.properties

echo
echo "===== VERIFY ====="

rg -n 'compileSdk =|targetSdk =' \
app adapter_* diagnostic_core \
--glob 'build.gradle.kts'

echo
echo "===== CLEAN ====="

./gradlew --stop || true
rm -rf .gradle

find . \
-type d \
-name build \
-prune \
-exec rm -rf {} +

echo
echo "===== BUILD ====="

./gradlew :app:assembleDebug --warning-mode all

echo
echo "===== FINAL AUDIT ====="

rg -n 'compileSdk = 36|targetSdk = 36' \
. \
-g '!build/**' \
&& echo "SDK36 STILL EXISTS" \
|| echo "SDK36 REMOVED"

echo
echo "BUILD SUCCESSFUL"
