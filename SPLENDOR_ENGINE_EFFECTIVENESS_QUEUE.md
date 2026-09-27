# SPLENDOR-ASSIST ENGINE EFFECTIVENESS QUEUE

## Purpose

Use the Bromenyt study as an architectural reference only. Do not copy its GameGuardian/memory mechanism.

Target:

```
OBSERVE → STATE → DECIDE → ONE ACTION → AUTHORITATIVE DISPATCH → ACCEPT/COMPLETE → VERIFY → RECOVER
```

Evidence rule:
- PROVEN = direct source/live evidence.
- LIKELY = supported by exact signatures/paths but not live-proven.
- UNVERIFIED = insufficient evidence.
- BLOCKED = evidence unavailable.

Every engine change must include:

```
CALLER → ENGINE → CALLEE → MUTATION → LIVE EFFECT → ROOT CAUSE → SMALLEST FIX → VERIFY
```

Do not add another engine merely because an existing action is ineffective. First prove where the execution path stops.

---

## G0 — Freeze the evidence baseline

**Status:** required first.

1. Record current HEAD: `dd072f159155428713035bc43d08ea5448cede37`.
2. Preserve current production source; no refactor during this gate.
3. Reconcile this queue against `Splendor_TODO.md` and current field logs.
4. Produce one live baseline showing:
   - frame/capture cycles
   - contributor collect cycles
   - offers
   - accepted/consumed bus requests
   - execution entry
   - Accessibility acceptance
   - gesture completion/cancellation
   - observable next-frame state
5. Any missing counter becomes an evidence gap, not a reason to assume success.

Exit: one end-to-end baseline trace exists.

---

## G1 — Establish the authoritative action path

The current repository contains multiple execution surfaces. Prove which path is authoritative.

Trace:

```
OverlayService / RuntimeDecisionLoop
 → GameplayEngineRegistry.collect
 → contribution arbitration
 → ContributionRegistry / CentralExecutionBus
 → SmartAssistAccessibilityEngine
 → GestureExecutionAuthority
 → AccessibilityService
```

Also trace every legacy/direct path that can reach:
- `ActiveGestureController.injectWinningVector`
- `triggerInstantExecution`
- `NativeInputBridge.injectTap/injectSwipe`
- direct `GestureExecutionAuthority.execute`

Classify every route:
- ACTIVE
- SUPERVISORY
- DUPLICATE
- INERT/DEAD
- FALLBACK
- UNKNOWN

Exit: exactly one production-authoritative execution route per action class, or each intentional alternate route has a proven reason.

---

## G2 — Convert decision output into a single action authority

Current data model already has:
- `RuntimeFrame`
- `EngineContribution`
- `ActionClass`
- `ExecutionRequest`
- `ContributionRegistry`
- `CentralExecutionBus`

Use these existing contracts before introducing new architecture.

Required behavior:

1. Contributors may produce observations/opinions.
2. Arbitration selects one actionable request for the current frame.
3. One action request receives one submission path.
4. Duplicate submission of the same logical action is rejected or superseded deterministically.
5. The selected request contains a target, action class/phase, duration, timestamp, and source.

Exit: one frame → one authoritative action decision.

---

## G3 — Freshness, ownership, and preemption

The current bus already has priority rings and stale-drop logic. Verify them against live timing.

Prove:

- request age at offer
- request age at consume
- priority ordering
- supersession behavior
- capacity drops
- stale drops
- current dispatcher ownership
- whether MOVE can legitimately preempt another action
- whether a completed/cancelled action releases ownership exactly once

Required counters:

```
offered
superseded
expired/stale
accepted
consumed
dispatchEntered
dispatchAccepted
dispatchCompleted
dispatchCancelled
dispatchRejected
```

Exit: no silent queue loss and no duplicate action ownership.

---

## G4 — Action execution contract

Create/verify one narrow execution contract at the real injection boundary.

Required lifecycle:

```
REQUESTED
 → VALIDATED
 → DISPATCHED
 → ACCEPTED
 → COMPLETED | CANCELLED | REJECTED | TIMEOUT
```

Do not use logging text as state. The contract must be represented by actual counters/state.

Validation must cover:
- finite coordinates
- valid action class
- positive duration
- valid service/accessibility availability
- request freshness
- current owner/preemption rules

Exit: every submitted action reaches exactly one terminal execution state.

---

## G5 — Post-action verification / feedback loop

This is the most important architectural gap to prove.

After an accepted gesture:

1. identify the expected next-state signature for the action;
2. compare subsequent `RuntimeFrame` observations against that signature;
3. classify:
   - EFFECT_OBSERVED
   - EFFECT_NOT_OBSERVED
   - AMBIGUOUS
   - STATE_CHANGED_EXTERNALLY
4. expose counts and last result.

Do not assume Accessibility acceptance means gameplay success.

Exit:

```
accepted != effective
```

is measurable.

---

## G6 — Recovery engine

For EFFECT_NOT_OBSERVED / TIMEOUT / CANCELLED:

1. determine whether the state is still actionable;
2. invalidate stale target data;
3. obtain a fresh frame;
4. recompute decision;
5. retry only when a fresh target satisfies the action preconditions;
6. record the reason for recovery.

No blind replay of an old gesture.

Exit: failed execution transitions back into observation/decision rather than dead-ending.

---

## G7 — Hot-path allocation audit

Only after G1–G6 are correct.

Audit:

- `GameplayEngineRegistry.collect`
- `ContributionRegistry.offer/drainBest`
- `CentralExecutionBus`
- decision/arbitration code
- `SmartAssistAccessibilityEngine.executeDirectRequest`
- `ActiveGestureController.injectWinningVector`

Current proven allocation/concurrency points include:
- `CopyOnWriteArrayList`
- `ConcurrentHashMap`
- per-collect `ArrayList`
- per-request `ExecutionRequest` data objects
- Kotlin `Path` / `GestureDescription` construction
- `ThreadLocalRandom` for input noise

Do not delete or replace these merely because they allocate. Measure/trace first.

Goal:
- zero avoidable allocation in high-frequency decision/selection paths;
- bounded unavoidable Android framework allocations at the actual Accessibility boundary.

Exit: measured allocation pressure and exact smallest fixes.

---

## G8 — Authority quality, not engine count

For every high-value contributor/engine:

```
caller exists?
 ↓
receives fresh RuntimeFrame?
 ↓
reads only allowed state?
 ↓
computes deterministic contribution?
 ↓
submission reaches arbitration?
 ↓
selected?
 ↓
dispatches?
 ↓
effect observed?
```

An engine that never reaches the execution path is not an effective engine regardless of how sophisticated its calculation is.

Do not add more contributors until this chain is proven for the existing highest-value actions.

---

## G9 — Native migration only after live authority proof

Existing native migration work may continue, but the rule is strict:

```
exact body
+
exact caller
+
exact runtime mutation
+
live native invocation
+
live action effect
```

before deleting the Kotlin implementation.

Native migration optimizes computation. It does not repair a disconnected execution path.

---

## G10 — Action-class completion matrix

Build a matrix for:

- MOVE
- PASS
- SHOT
- CROSS
- DEFEND
- EVADE
- KEEPER

For each class record:

```
frame input
decision producer
arbitration source
queue/ring
execution path
acceptance
completion
feedback
recovery
live proof
```

Exit: every claimed action class has an evidence-backed complete path.

---

## G11 — Regression/build gate

After each patch:

1. build debug;
2. inspect compile warnings/errors;
3. verify native library outputs where touched;
4. verify no unresolved references;
5. run static caller trace for changed path;
6. install manually through the existing project workflow;
7. capture live runtime proof;
8. append field log.

No change is accepted solely because the APK builds.

---

# QWEN EXECUTION ORDER

Run these in order. Do not jump to native rewrites.

1. G0 baseline.
2. G1 authoritative path.
3. G2 single-action authority.
4. G3 freshness/preemption/loss.
5. G4 terminal execution state.
6. G5 post-action feedback.
7. G6 recovery.
8. G7 hot-path allocation.
9. G8 authority matrix.
10. G9 native migration cleanup/optimization.
11. G10 action-class completion matrix.
12. G11 build/live/regression closure.

## Hard stop conditions

Stop and return evidence instead of patching when:

- caller is unproven;
- execution route is duplicated but ownership is unclear;
- an engine has no live consumer;
- acceptance is measured but effect is not;
- a native replacement has no live invocation proof;
- a proposed optimization changes behavior without a measured root cause.

## Final target

Do not target "more engines", "more code", or "more native".

Target:

```
fresh state
  ↓
correct decision
  ↓
single authoritative action
  ↓
timely accepted dispatch
  ↓
observable gameplay state transition
  ↓
verification
  ↓
continuous recovery
```

That is the Splendor-Assist equivalent of the useful architectural property exposed by the Bromenyt study.
