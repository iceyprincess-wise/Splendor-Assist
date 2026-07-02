#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$HOME/projects/Splendor-Assist"
OUTDIR="/sdcard/SplendorAssist-Audits"
OUTFILE="$OUTDIR/PHASE9_CONFIGURATION_OVERLAY_MASSIVE_AUDIT.txt"

mkdir -p "$OUTDIR"
cd "$ROOT"

{
echo "================ PHASE 9 CONFIGURATION + OVERLAY AUDIT ================"
echo "DATE: $(date)"
echo
echo "OBJECTIVE:"
echo "Verify existing or partially implemented:"
echo " - VisionConfiguration"
echo " - TrackingConfiguration"
echo " - Runtime tuning panel"
echo " - Vision debug overlay"
echo

echo "================ PROJECT FILE INVENTORY ================"
find . -type f | sort
echo

echo "================ CONFIGURATION SEARCH ================"
grep -RInEi \
'VisionConfiguration|TrackingConfiguration|RuntimeConfiguration|Configuration|Config|Configurator|Settings|Preference|SharedPreference|RuntimeSettings|VisionSettings|TrackingSettings|Parameter|Calibration|Profile|Preset' \
adapter_smartassist/src/main/java app/src/main 2>/dev/null || true
echo

echo "================ RUNTIME TUNING SEARCH ================"
grep -RInEi \
'RuntimeTuning|TuningPanel|ControlPanel|DeveloperPanel|DeveloperOptions|DebugPanel|PerformancePanel|RuntimeControl|RuntimeAdjust|CalibrationPanel|Slider|SeekBar|Switch|Toggle|Compose|PreferenceScreen|BottomSheet|Dialog' \
adapter_smartassist/src/main/java app/src/main 2>/dev/null || true
echo

echo "================ DEBUG / OVERLAY SEARCH ================"
grep -RInEi \
'Overlay|DebugOverlay|VisionOverlay|Hud|HUD|Canvas|draw|Draw|Renderer|Render|RenderThread|Paint|ComposeView|SurfaceView|TextureView|GraphicOverlay|DebugRenderer|Layer|DisplayOverlay' \
adapter_smartassist/src/main/java app/src/main 2>/dev/null || true
echo

echo "================ VISION PIPELINE REFERENCES ================"
grep -RInEi \
'VisionCore|SceneTracker|Phase3WorldState|Phase3WorldStateStore|TelemetryCoordinator|TelemetryRepository|GameplayDecisionEngine|RuntimeConfidenceCalibration|OnlineParameterAdaptation|TemporalMemory' \
adapter_smartassist/src/main/java 2>/dev/null || true
echo

echo "================ DEBUG DRAWABLES / LAYOUTS ================"
find app/src/main 2>/dev/null | grep -Ei \
'layout|menu|drawable|xml|overlay|debug|vision|runtime|tracking|config'
echo

echo "================ MANIFEST PERMISSIONS ================"
find . -name AndroidManifest.xml | while read f
do
echo
echo "FILE: $f"
nl -ba "$f"
done
echo

echo "================ TARGET SOURCE DUMP ================"
find adapter_smartassist/src/main/java app/src/main -type f 2>/dev/null | \
grep -Ei \
'Vision|Tracking|Overlay|Runtime|Configuration|Config|Debug|Render|Renderer|Telemetry|Scene|Display|Panel|Settings|Preference' | \
while read f
do
echo
echo "####################################################################"
echo "FILE: $f"
echo "####################################################################"
nl -ba "$f"
echo
done

echo "================ BUILD FILES ================"
find . -name "build.gradle*" -o -name "settings.gradle*" | sort
echo

echo "================ GIT STATUS ================"
git status --short
echo

echo "================ LAST 15 COMMITS ================"
git log --oneline -15
echo

echo "================ AUDIT COMPLETE ================"

} > "$OUTFILE"

echo
echo "AUDIT COMPLETE"
echo "$OUTFILE"
