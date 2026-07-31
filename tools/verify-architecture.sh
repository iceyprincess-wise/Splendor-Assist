#!/data/data/com.termux/files/usr/bin/bash
cd "$HOME/projects/Splendor-Assist" || exit 1

AS="adapter_smartassist/src/main/java/com/assistant/adapter/smartassist"
DC="diagnostic_core/src/main/java/com/assistant"
APP="app/src/main/java/com/assistant"
EX="--exclude-dir=build --exclude-dir=.repair-backups --exclude-dir=.runtime-freeze"

PASS=0; FAIL=0
ok(){ printf '  PASS  %s\n' "$1"; PASS=$((PASS+1)); }
no(){ printf '  FAIL  %s\n' "$1"; FAIL=$((FAIL+1)); }
chk(){ if eval "$2" >/dev/null 2>&1; then ok "$1"; else no "$1"; fi; }

echo "=============================================="
echo " SPLENDOR-ASSIST FINAL ARCHITECTURE FORENSICS"
echo "=============================================="

echo
echo "[1] RuntimeFrame"
chk "RuntimeFrame.kt exists" "test -f $DC/runtime/RuntimeFrame.kt"
chk "declared as data class (immutable)" "grep -q 'data class RuntimeFrame' $DC/runtime/RuntimeFrame.kt"
chk "no var fields inside RuntimeFrame" "! sed -n '/data class RuntimeFrame/,/^)/p' $DC/runtime/RuntimeFrame.kt | grep -q '  var '"

echo
echo "[2] FrameAssembler"
chk "FrameAssembler.kt exists" "test -f $AS/FrameAssembler.kt"
chk "is the only RuntimeFrame constructor" "test \$(grep -rl 'RuntimeFrame(' $AS $DC $APP 2>/dev/null | grep -v repair-backups | grep -v 'RuntimeFrame.kt' | wc -l) -eq 1"
chk "driven once per capture" "grep -q 'FrameAssembler.assemble' $APP/OverlayService.kt"

echo
echo "[3] GameplayContributor interface"
chk "interface declared" "grep -q 'interface GameplayContributor' $DC/runtime/RuntimeFrame.kt"
N=$(ls $AS/contributors/*Contributor.kt | wc -l); chk "all $N adapters implement interface" "test \$(grep -l 'GameplayContributor' $AS/contributors/*.kt | wc -l) -eq $N"

echo
echo "[4] Repository reads removed from contributors"
chk "no SceneTracker read in contributors" "! grep -rq 'SceneTracker\.' $AS/contributors/"
chk "no Phase3WorldStateStore read" "! grep -rq 'Phase3WorldStateStore\.' $AS/contributors/"
chk "no TelemetryRepository read" "! grep -rq 'TelemetryRepository\.' $AS/contributors/"
chk "OverlayService no longer offers directly" "! grep -q 'ContributionRegistry.offer' $APP/OverlayService.kt"

echo
echo "[5] Singleton ownership / reset lifecycle"
chk "coordinator has resetRuntimeState" "grep -q 'resetRuntimeState' $AS/RuntimeCoordinator.kt"
chk "resets >= 8 components" "test \$(grep -c 'reset()\|clear()\|resetAll()' $AS/RuntimeCoordinator.kt) -ge 8"
chk "shutdown invoked by a real caller" "grep -rq 'RuntimeCoordinator.shutdown' $APP"

echo
echo "[6] Engine dependency isolation"
chk "no contributor calls another contributor" "! grep -rq 'Contributor\.' $AS/contributors/"
chk "decision loop calls no engine directly" "! grep -qE 'MagneticFeetEngine|TrueTargetPassingEngine|DefenseAuthorityEngine' $AS/RuntimeDecisionLoop.kt"

echo
echo "[7] GameplayEngineRegistry"
chk "registry exists" "test -f $DC/runtime/GameplayEngineRegistry.kt"
chk "registrations match adapter count" "test \$(grep -c 'GameplayEngineRegistry.register' $AS/RuntimeCoordinator.kt) -eq \$(ls $AS/contributors/*Contributor.kt | wc -l)"
chk "coordinator warms all" "grep -q 'GameplayEngineRegistry.warmAll' $AS/RuntimeCoordinator.kt"

echo
echo "[8] Immutable runtime state"
chk "EngineContribution is data class" "grep -q 'data class EngineContribution' $DC/runtime/RuntimeFrame.kt"
chk "contributors never mutate frame" "! grep -rq 'frame\..* =' $AS/contributors/"

echo
echo "[9] Unified contribution model"
chk "no contributor builds ExecutionRequest" "! grep -rq 'ExecutionRequest(' $AS/contributors/"
chk "all adapters return EngineContribution" "test \$(grep -l 'EngineContribution' $AS/contributors/*.kt | wc -l) -eq \$(ls $AS/contributors/*Contributor.kt | wc -l)"

echo
echo "[10] One decision per frame"
chk "exactly one route() in loop" "test \$(grep -c 'HybridExecutionTerminal.route' $AS/RuntimeDecisionLoop.kt) -eq 1"
chk "submit sites limited to terminal+goalkeeper" "test \$(grep -rl 'CentralExecutionBus.submit(' $APP $AS $DC 2>/dev/null | grep -v repair-backups | grep -v ContributionRegistry | wc -l) -eq 2"

echo
echo "[11] Runtime health monitor"
chk "monitor exists" "test -f $AS/RuntimeHealthMonitor.kt"
chk "tracks booster" "grep -q 'boosterAlive' $AS/RuntimeHealthMonitor.kt"
chk "reports degraded reasons" "grep -q 'degradedReasons' $AS/RuntimeHealthMonitor.kt"
chk "permissions gate wired" "grep -rq 'reportPermissionsVerified()' $APP $AS"

echo
echo "[12] Engine capability flags"
chk "capability flags on every adapter" "test \$(grep -l 'override val capabilities' $AS/contributors/*.kt | wc -l) -eq \$(ls $AS/contributors/*Contributor.kt | wc -l)"
chk "EngineCapability enum exists" "grep -q 'enum class EngineCapability' $DC/runtime/RuntimeFrame.kt"

echo
echo "[13] Hot-upgrade architecture"
chk "MagneticFeet public API intact" "grep -q 'fun stabilize' $AS/MagneticFeetEngine.kt"
chk "adapter wraps engine, not inlined" "grep -q 'MagneticFeetEngine.stabilize' $AS/contributors/MagneticFeetContributor.kt"

echo
echo "[14] Event bus separation"
chk "event hubs file exists" "test -f $DC/events/RuntimeEvents.kt"
chk "3 channels declared" "test \$(grep -c 'object SystemEventHub\|object RuntimeEventHub\|object GameplayEventHub' $DC/events/RuntimeEvents.kt) -eq 3"
chk "SYSTEM channel emits" "grep -rq 'SystemEventHub.emit' $AS"
chk "RUNTIME channel emits" "grep -rq 'RuntimeEventHub.emit' $AS"
chk "GAMEPLAY channel emits" "grep -rq 'GameplayEventHub.emit' $AS"
chk "hubs reset on shutdown" "grep -q 'EventHubs.resetAll' $AS/RuntimeCoordinator.kt"

echo
echo "[15] Thread ownership"
chk "single bus consumer" "test \$(grep -rc 'CentralExecutionBus.consume' $AS/SmartAssistAccessibilityEngine.kt) -eq 1"
chk "single gameplay dispatcher" "test \$(grep -rl 'service.dispatchGesture\|dispatchGesture(gesture' $AS 2>/dev/null | grep -v repair-backups | grep -v GridRecents | wc -l) -eq 1"
chk "decision loop on capture thread" "grep -q 'RuntimeDecisionLoop.onFrame' $APP/OverlayService.kt"

echo
echo "[EXTRA] Runtime blockers fixed"
chk "booster ignition wired to start path" "grep -q 'BoosterIgnition.ensureIgnited' $APP/OverlayService.kt"
chk "ball telemetry bridge exists" "test -f $AS/BallTelemetryBridge.kt"
chk "vision publishes ball telemetry" "grep -q 'BallTelemetryBridge.publish' $AS/VisionCore.kt"
chk "coordinates clamped (no negative Path)" "grep -q 'coerceAtLeast(0f)' $AS/SmartAssistAccessibilityEngine.kt"
chk "build marker present" "grep -q 'BUILD_MARKER' $AS/SmartAssistAccessibilityEngine.kt"

echo
echo "[16] GAP1 vision self-mask"
chk "50 selfmask registry surface" 'grep -rq "publishBounds" adapter_smartassist/src/main/java'
chk "51 selfmask leaves published" 'grep -rq "publishHierarchy" app/src/main/java'
chk "52 capture-space mapping" 'grep -rq "setCaptureScale" app/src/main/java'
chk "53 ocr drops self-drawn blocks" 'grep -rq "isSelfDrawnCapture" app/src/main/java'
chk "54 mask released on teardown" 'grep -rq clearPrefix app/src/main/java'
chk "55 container guard present" 'grep -rq "maxAreaFraction" app/src/main/java'

echo
echo "[17] GAP2/GAP3 vision trust"
chk "56 gap2 trust engine exists" "test -f adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/VisionTrust.kt"
chk "57 gap2 ball stamped" "grep -q 'VisionTrust.stampBall' adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/TelemetryCoordinator.kt"
chk "58 gap2 confidence gated" "grep -q 'VisionTrust.frameTrusted' adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/FrameAssembler.kt"
chk "59 gap3 lane spread derived" "grep -q 'stampLaneSpread' adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/FrameAssembler.kt"
chk "60 gap1c own-ui gate wired" "grep -q 'ForegroundGate.shouldSkipCapture' app/src/main/java/com/assistant/OverlayService.kt"

echo
echo "[18] Crash + death detection"
chk "61 deathwatch exists" "test -f app/src/main/java/com/assistant/DeathWatch.kt"
chk "62 deathwatch armed in Application" "grep -q 'DeathWatch.install' app/src/main/java/com/assistant/App.kt"
chk "63 crash reports reach Downloads" "grep -q 'DIRECTORY_DOWNLOADS' app/src/main/java/com/assistant/GlobalCrashHandler.kt"
chk "64 no duplicate handler install" "! grep -q 'GlobalCrashHandler(this)' app/src/main/java/com/assistant/MainActivity.kt"
chk "65 featureFault has context" "! grep -q 'getLogFile(null' app/src/main/java/com/assistant/GlobalCrashHandler.kt"

echo
echo "[19] Recursion / thread survival"
chk "66 no coordinator recursion" "! grep -qE '^[[:space:]]+reportBoosterReady\\(\\)' adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeCoordinator.kt"
chk "67 evaluate has reentrancy guard" "grep -q 'evaluating.compareAndSet' adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeCoordinator.kt"
chk "68 capture thread survives Errors" "grep -q 'catch (t: Throwable)' app/src/main/java/com/assistant/OverlayService.kt"
chk "69 booster gate set directly" "grep -q 'healthy && boosterReady.compareAndSet' adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/RuntimeCoordinator.kt"
chk "70 single kernel process" "test \$(grep -rhoE 'android:process=\":[a-z_]+\"' adapter_*/src/main/AndroidManifest.xml | sort -u | wc -l) -eq 1"
chk "legacy labels removed from control room" "! grep -q 'DecisionCycles=' $APP/controlroom/ui/SmartAssistControlRoomActivity.kt"

echo
echo "=============================================="
printf ' TOTAL: %s PASS / %s FAIL\n' "$PASS" "$FAIL"


echo "=============================================="
[ "$FAIL" -eq 0 ] && echo "VERDICT: ALL TASKS VERIFIED COMPLETE" || echo "VERDICT: $FAIL CHECK(S) NEED ATTENTION"
