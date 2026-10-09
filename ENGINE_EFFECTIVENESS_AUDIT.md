# ENGINE EFFECTIVENESS AUDIT

## STATUS SUMMARY

- PROVEN:
  - 139 Kotlin source files, 37 C/C++ source files (244 total repository files).
  - 84 named `*Engine` declarations and 30 `GameplayContributor` implementations inside `gameplay_engine.kt`.
  - 37 named performance engines inside `performance_engine.kt`.
  - Native gesture calculation (`nativeInjectStrobePackets`, `nativeCompileMotionEvent`, `nativeDirectInputHookImpl`) operates in memory only and does not invoke Linux uinput or kernel touch drivers directly.
  - `nativeInjectTap` and `nativeInjectSwipe` in `native_input.cpp` previously returned `JNI_TRUE` without performing touch injection.

- LIKELY:
  - Accessibility gesture submission (`dispatchGesture`) via `SmartAssistAccessibilityEngine` / `GestureExecutionAuthority` is the single supported user-space touch injection mechanism on non-rooted Android 16 devices.

- UNVERIFIED / BLOCKED:
  - Direct multi-pointer simultaneous hardware multi-touch without Accessibility gesture dispatch on stock Android builds is unsupported without root/uinput.

---

## REPOSITORY INVENTORY & CLASSIFICATION

### 1. Gameplay Engine Components (gameplay_engine.kt)
- Declared types: 341
- Functions: 497
- Engine Blocks: 84 named engines (e.g., AutoEvadeEngine, AntiCutbackSubEngine, ActiveAttackerEngine, etc.)
  - Classification: CALCULATOR / DECISION
  - Caller: `RuntimeDecisionLoop` / `GameplayEngineRegistry`
  - Callee: `ContributionRegistry` / `CentralExecutionBus`
  - Mutation: Local memory calculation / state evaluation
  - Execution Boundary: `ExecutionRequest` creation
- Contributors: 30 `GameplayContributor` implementations (e.g., CrossClaimContributor, KeeperBiasContributor, PanicSaveContributor, ThreatPriorityContributor, TrueShotContributor)
  - Classification: CONTRIBUTOR
  - Caller: `GameplayEngineRegistry.collect()`
  - Callee: Native JNI thin adapter or calculation routine
  - Returned Data: `EngineContribution` / vector array
  - Execution Boundary: `ContributionRegistry.offer()`

### 2. Performance Engine Components (performance_engine.kt)
- Named Performance Engines: 37 (e.g., FrameStabilityEngine, ThermalGovernorEngine, MemoryOptimizerEngine, etc.)
  - Classification: SUPERVISOR / STATE/TELEMETRY
  - Caller: `PerformanceEngineService`
  - Callee: System telemetry & process metrics
  - Mutation: System settings / process priority flags

### 3. Native C/C++ Input & Compute Components
- `native_input_strobe.c` (`nativeInjectStrobePackets`)
  - Classification: CALCULATOR
  - Caller: `AsynchronousGestureQueue.kt`
  - Returned Data: `FloatArray(16)` populated with strobe packet coordinates
  - Execution Boundary: JNI array release
- `native_input.cpp` (`nativeInjectTap`, `nativeInjectSwipe`)
  - Classification: INERT / BROKEN (Repaired)
  - Caller: `NativeInputBridge.kt`
  - Behavior: Returns `JNI_FALSE` so caller delegates to Accessibility Service authority.
- `native_direct_input_hook.c` (`nativeDirectInputHookImpl`)
  - Classification: CALCULATOR
  - Behavior: Clamps and transforms coordinate arrays into output buffers.
- `native_system_core.c` (`nativeCompileMotionEvent`)
  - Classification: CALCULATOR
  - Behavior: Packs coordinates and duration into a 64-bit integer (`jlong`).

### 4. Interceptor & Overlay Domain (48 files)
- Goalkeeper and Interception action engines
  - Classification: DECISION / REQUEST_PRODUCER
  - Caller: `GoalkeeperStateMachine` / `GoalkeeperExecutionEngine`
  - Callee: `CentralExecutionBus`
