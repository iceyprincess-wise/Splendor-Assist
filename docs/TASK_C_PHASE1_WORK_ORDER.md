# Task C — Phase 1 Work Order (verified trace, no assumptions)

Status of git operations (verified against live repo state, 2026-08-09):

- PR #2 (Task B: control-room truth, health verdicts, trust-chain unlock, self-timed dispatch latch) — **merged into `main`** (merged_at 2026-08-09T14:00:45Z, merged: true).
- `feature/task-c` — **created from `main`** and currently at the exact same commit as `main` (520c281). Clean starting point, no drift.
- `feature/task-b` and `feature/admin-settings` — merged/closed but still present; deletion is a manual GitHub UI step (Branches page).

## Verified module map (traced this session)

Foundation adapter modules (separate Gradle modules):
`adapter_battery, adapter_boot, adapter_input, adapter_interruption, adapter_lag, adapter_lmk, adapter_memory, adapter_net, adapter_ping, adapter_scheduler, adapter_smartassist, adapter_stutter, adapter_sync, adapter_thermal, adapter_watchdog, diagnostic_core`

App packages (`app/src/main/java/com/assistant/`):
`core/AdapterIpcBridge.kt`, `coach/DvrSyncEngine.kt`, `controlroom/ControlRoomBootstrap.kt` (+ `controlroom/ui/`), `contributors/`, `vision/`, `overlay/`, `compliance/`, `diagnostic/`, `recovery/`, `survival/`, plus root engines (`DiagnosticsEngine.kt`, `IgnitionEngine.kt`, `BoosterIgnition.kt`, `OverlayService.kt`, `DeathWatch.kt`, ...).

## Phase 1 scope (in priority order) — nothing below is implemented yet on this branch

### 1. Dispatch throughput (highest felt-effect)
- Current state per last round: watchdog force-clears a stuck latch; self-timed release at gesture duration + 40ms (~5–8× improvement) is still serialized per gesture.
- Target: event-driven, non-blocking dispatch — no fixed dead time per action; concurrent gesture lanes where the injector allows; latch becomes a per-lane token released by the injector callback OR the duration cap, whichever fires first.
- Honest ceiling to verify on-device: Android `dispatchGesture` has real hardware/OS floor; "100+ actions/sec" must be measured, not claimed. Add a dispatch-rate counter to the control room so the real number is visible per match.
- Acceptance: log shows zero `stuck latch force-cleared` events in a full match; measured actions/sec printed every 10s.

### 2. Emergency lane wiring (GK / Interception direct submissions)
- Current state: `Contributions offered=0` — engines speak only through the normal contributor lane; emergency lane has never fired.
- Work: trace the emergency-lane submission API end-to-end, wire GK + Interception engines to submit directly on qualifying events, and add a visible counter (offered / accepted / dispatched) so a silent failure cannot hide.
- Acceptance: emergency counter > 0 in a real match log; end-to-end latency of an emergency submission logged.

### 3. Memory + LMK + input adapter upgrade (4GB device, 50–60% baseline RAM)
- Add pressure-tiered response engines: `onTrimMemory`/`ComponentCallbacks2` tier mapping, LMK-adjacent watermark tracking via `ActivityManager.MemoryInfo` polling with adaptive interval, proactive cache shedding in vision buffers, and input-adapter pooling (no per-event allocations on the hot path).
- Acceptance: steady-state alloc rate on the hot path ~0/frame; baseline RAM of the app itself reported in the control room.

### 4. Overlay/notification unification + Guard Lock removal
- One foreground service notification, one overlay surface; per-node notifications collapse into it. Remove Guard Lock and its UI/paths entirely (no dead code left behind).

### 5. Remaining gameplay-engine onboarding + rest of foundation adapters
- Every engine registered as a contributor with health verdicts in the control room; adapters report through the unified registry.

### 6. Detection upgrade path (honest scope)
- Current: heuristic vision (color/blob/motion). Real upgrade = on-device TFLite (or MediaPipe) detector with the heuristic pipeline as fallback + cross-check.
- Truth constraint: a trained model requires a real `.tflite` asset (pretrained ball/player detector or custom-trained). Integration code can land in-branch; the model binary must be added as an asset and validated on-device. No claim of "trained model active" until an on-device inference log proves it.

### 7. Loophole / silent-failure audit (runs across all of the above)
- Every lane gets a counter (offered/accepted/dropped + reason). Any catch-block that swallows an exception on a gameplay path must log to the diagnosis room. Watchdog rescues must be zero in a healthy match — any rescue is a defect surfaced, not a feature.

## Rule for this branch
No simulated results, no counters incremented without real events, no claims without a corresponding on-device log line. Every fix lands with its own visibility instrumentation so regressions cannot hide.
