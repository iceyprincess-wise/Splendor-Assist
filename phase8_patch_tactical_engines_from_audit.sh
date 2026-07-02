#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

echo "STOP."

echo
echo "The audit confirms every tactical engine currently returns an EMPTY result object:"
echo "  BuildUpRecognitionResult()"
echo "  CentralOverloadDetectionResult()"
echo "  CounterPressRecognitionResult()"
echo "  DefensiveCompactnessResult()"
echo "  PossessionStyleRecognitionResult()"
echo "  PressingRecognitionResult()"
echo "  TacticalMapResult()"
echo "  WingOverloadDetectionResult()"

echo
echo "Those result classes are NOT present in the audit."
echo
echo "Without the definitions of:"
echo "  • BuildUpRecognitionResult"
echo "  • CentralOverloadDetectionResult"
echo "  • CounterPressRecognitionResult"
echo "  • DefensiveCompactnessResult"
echo "  • PossessionStyleRecognitionResult"
echo "  • PressingRecognitionResult"
echo "  • TacticalMapResult"
echo "  • WingOverloadDetectionResult"
echo
echo "it is impossible to populate richer metrics safely."
echo
echo "Adding fields blindly would almost certainly break VisionCore,"
echo "Phase3WorldState,"
echo "TacticalAnalyticsEngine,"
echo "and every downstream consumer."

echo
echo "Creating fake fields would violate your requirement not to guess architecture."

echo
echo "Run the audit below instead."

OUT=/sdcard/SplendorAssist-Audits/PHASE8_TACTICAL_RESULT_CLASSES_AUDIT.txt

(
echo "================ RESULT CLASSES ================"
echo

find adapter_smartassist/src/main/java \
-type f \
| while read f
do
grep -Eq \
'data class (BuildUpRecognitionResult|CentralOverloadDetectionResult|CounterPressRecognitionResult|DefensiveCompactnessResult|PossessionStyleRecognitionResult|PressingRecognitionResult|TacticalMapResult|WingOverloadDetectionResult)' \
"$f" || continue

echo
echo "############################################################"
echo "$f"
echo "############################################################"
nl -ba "$f"
done

echo
echo "================ WORLD STATE ================"
grep -RIn \
'BuildUpRecognitionResult\|CentralOverloadDetectionResult\|CounterPressRecognitionResult\|DefensiveCompactnessResult\|PossessionStyleRecognitionResult\|PressingRecognitionResult\|TacticalMapResult\|WingOverloadDetectionResult' \
adapter_smartassist/src/main/java

echo
echo "================ ANALYTICS ================"
find adapter_smartassist/src/main/java \
-name "TacticalAnalyticsEngine.kt" \
-exec nl -ba {} \;

echo
echo "================ PHASE3 WORLD STATE ================"
find adapter_smartassist/src/main/java \
-name "Phase3WorldState.kt" \
-exec nl -ba {} \;

) > "$OUT"

echo
echo "AUDIT COMPLETE"
echo "$OUT"
