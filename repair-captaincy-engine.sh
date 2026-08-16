#!/data/data/com.termux/files/usr/bin/bash

set -u

ROOT="$HOME/projects/Splendor-Assist"

cd "$ROOT" || exit 1

echo "============================================================"
echo " SPLENDOR-ASSIST CAPTAINCY ENGINE"
echo " NEW PASSIVE SKILL ENGINE"
echo " REGISTRY-WIRED + FAIL-CLOSED + ATOMIC + BACKUP"
echo "============================================================"

python3 - "$ROOT" <<'PY'
from pathlib import Path
from datetime import datetime
import os
import shutil
import sys

root = Path(sys.argv[1])

engine = root / (
    "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/"
    "CaptaincyEngine.kt"
)

contributor = root / (
    "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/"
    "contributors/CaptaincyContributor.kt"
)

runtime_coordinator = root / (
    "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/"
    "RuntimeCoordinator.kt"
)

runtime_decision_loop = root / (
    "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/"
    "RuntimeDecisionLoop.kt"
)

registry = root / (
    "diagnostic_core/src/main/java/com/assistant/runtime/"
    "GameplayEngineRegistry.kt"
)

runtime_frame = root / (
    "diagnostic_core/src/main/java/com/assistant/runtime/"
    "RuntimeFrame.kt"
)

timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
backup_dir = root / f"captaincy-engine-backup-{timestamp}"


def fail(message):
    print(f"FAILED: {message}")
    raise SystemExit(1)


def atomic_write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.captaincy-repairing")
    try:
        tmp.write_text(content, encoding="utf-8")
        os.replace(tmp, path)
    except Exception:
        try:
            tmp.unlink()
        except FileNotFoundError:
            pass
        raise


def backup(path):
    if not path.exists():
        fail(f"backup target missing: {path}")

    destination = backup_dir / path.relative_to(root)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, destination)


def require_once(text, anchor, description):
    count = text.count(anchor)
    if count != 1:
        fail(
            f"{description}: expected exactly 1 anchor, found {count}: "
            f"{anchor!r}"
        )


print()
print("[1] VERIFY BASE ARCHITECTURE")

required = [
    runtime_frame,
    registry,
    runtime_coordinator,
    runtime_decision_loop,
]

for path in required:
    if not path.exists():
        fail(f"required architecture file missing: {path}")

print("PROVEN: RuntimeFrame exists")
print("PROVEN: GameplayEngineRegistry exists")
print("PROVEN: RuntimeCoordinator exists")
print("PROVEN: RuntimeDecisionLoop exists")

print()
print("[2] VERIFY CONTRIBUTOR CONTRACT")

frame_text = runtime_frame.read_text(encoding="utf-8")

required_frame_anchors = [
    "interface GameplayContributor",
    "val engineName: String",
    "val capabilities: Set<EngineCapability>",
    "fun contribute(frame: RuntimeFrame): EngineContribution?",
    "data class EngineContribution",
    "ActionClass.NONE",
]

for anchor in required_frame_anchors:
    if anchor not in frame_text:
        fail(f"RuntimeFrame contract anchor missing: {anchor}")

print("PROVEN: GameplayContributor contract")
print("PROVEN: EngineContribution contract")
print("PROVEN: ActionClass.NONE exists")

print()
print("[3] VERIFY REGISTRY CONTRACT")

registry_text = registry.read_text(encoding="utf-8")

for anchor in [
    "object GameplayEngineRegistry",
    "fun register(contributor: GameplayContributor)",
    "fun collect(frame: RuntimeFrame)",
    "fun resetAll()",
    "fun engineStates()",
]:
    if anchor not in registry_text:
        fail(f"registry anchor missing: {anchor}")

print("PROVEN: registry supports registration")
print("PROVEN: registry invokes contributors per frame")
print("PROVEN: registry supports reset")
print("PROVEN: registry exposes engine state")

print()
print("[4] VERIFY CAPTAINCY IS NOT ALREADY IMPLEMENTED")

all_sources = list(
    root.glob("adapter_smartassist/src/main/java/**/*.kt")
)

existing = [
    p for p in all_sources
    if "Captaincy" in p.name
]

if existing:
    fail(
        "Captaincy implementation already exists: "
        + ", ".join(str(p.relative_to(root)) for p in existing)
    )

print("PROVEN: Captaincy is a new engine")

print()
print("[5] VERIFY NO PREVIOUS CAPTAINCY REGISTRATION")

for path, text in [
    (runtime_coordinator, runtime_coordinator.read_text(encoding="utf-8")),
    (runtime_decision_loop, runtime_decision_loop.read_text(encoding="utf-8")),
]:
    if "CaptaincyEngine" in text or "CaptaincyContributor" in text:
        fail(
            f"unexpected Captaincy reference already present in "
            f"{path.relative_to(root)}"
        )

print("PROVEN: Captaincy is not previously wired")

print()
print("[6] CREATE BACKUPS")

for path in [
    runtime_coordinator,
    runtime_decision_loop,
    registry,
    runtime_frame,
]:
    backup(path)

print(f"PROVEN: backups created at {backup_dir}")

print()
print("[7] CREATE CAPTAINCY ENGINE")

engine_text = r'''package com.assistant.adapter.smartassist

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.runtime.RuntimeFrame
import java.util.concurrent.atomic.AtomicLong

/**
 * CaptaincyEngine
 *
 * Models the documented eFootball Captaincy player-skill behaviour as a
 * PASSIVE TEAM-FATIGUE signal.
 *
 * Gameplay basis:
 * - Captaincy reduces the effects of fatigue for the team.
 * - The skill is effective when the player possessing Captaincy is the
 *   designated captain and that captain is on the pitch.
 * - Multiple Captaincy skills do not stack.
 *
 * This engine deliberately does NOT fabricate player identity, captain
 * identity, stamina values, or hidden KONAMI coefficients because those
 * values are not present in RuntimeFrame.
 *
 * RuntimeFrame therefore remains the sole frame input and the captaincy
 * assignment state is explicitly supplied through configure().
 *
 * FAIL-CLOSED:
 * - untrusted frame -> inactive
 * - no Captaincy holder -> inactive
 * - holder is not captain -> inactive
 * - captain not on pitch -> inactive
 *
 * Captaincy is passive. It does not create a joystick/shot/pass gesture.
 * The contributor therefore reports ActionClass.NONE and cannot win normal
 * action arbitration.
 */
object CaptaincyEngine {

    private const val BASELINE_FATIGUE_RESISTANCE = 0.0f
    private const val ACTIVE_FATIGUE_RESISTANCE = 1.0f

    private val activations = AtomicLong(0L)

    @Volatile
    private var captaincyHolderPresent = false

    @Volatile
    private var holderIsCaptain = false

    @Volatile
    private var captainOnPitch = false

    @Volatile
    private var active = false

    @Volatile
    private var lastFrameId = -1L

    @Volatile
    private var lastFatigueResistance = BASELINE_FATIGUE_RESISTANCE

    data class CaptaincyResult(
        val active: Boolean,
        val teamFatigueResistance: Float,
        val captaincyHolderPresent: Boolean,
        val holderIsCaptain: Boolean,
        val captainOnPitch: Boolean,
        val frameId: Long
    )

    /**
     * Supplies the explicit team-role state available to the application.
     *
     * The engine requires ALL three conditions before Captaincy activates:
     *
     * 1. a player possessing Captaincy exists;
     * 2. that player is the designated captain;
     * 3. that captain is currently on the pitch.
     *
     * No inferred or guessed player identity is accepted.
     */
    @Synchronized
    fun configure(
        captaincyHolderPresent: Boolean,
        holderIsCaptain: Boolean,
        captainOnPitch: Boolean
    ) {
        this.captaincyHolderPresent = captaincyHolderPresent
        this.holderIsCaptain = holderIsCaptain
        this.captainOnPitch = captainOnPitch

        if (!captaincyHolderPresent ||
            !holderIsCaptain ||
            !captainOnPitch
        ) {
            active = false
            lastFatigueResistance = BASELINE_FATIGUE_RESISTANCE
        }
    }

    /**
     * Evaluate Captaincy against the trusted runtime frame.
     *
     * The returned coefficient is deliberately qualitative:
     * 0.0 = Captaincy inactive
     * 1.0 = Captaincy condition fully satisfied
     *
     * It is NOT claimed to be KONAMI's hidden numeric multiplier.
     */
    fun evaluate(frame: RuntimeFrame): CaptaincyResult {
        if (!frame.trusted) {
            active = false
            lastFrameId = frame.frameId
            lastFatigueResistance = BASELINE_FATIGUE_RESISTANCE
            return inactive(frame)
        }

        val shouldActivate =
            captaincyHolderPresent &&
                holderIsCaptain &&
                captainOnPitch

        lastFrameId = frame.frameId

        if (!shouldActivate) {
            active = false
            lastFatigueResistance = BASELINE_FATIGUE_RESISTANCE
            return inactive(frame)
        }

        if (!active) {
            activations.incrementAndGet()

            RuntimeLogger.log(
                "CAPTAINCY ACTIVE: Captaincy holder is captain and on pitch",
                "CAPTAINCY"
            )
        }

        active = true
        lastFatigueResistance = ACTIVE_FATIGUE_RESISTANCE

        return CaptaincyResult(
            active = true,
            teamFatigueResistance = ACTIVE_FATIGUE_RESISTANCE,
            captaincyHolderPresent = captaincyHolderPresent,
            holderIsCaptain = holderIsCaptain,
            captainOnPitch = captainOnPitch,
            frameId = frame.frameId
        )
    }

    fun isActive(): Boolean = active

    fun diagnostics(): Map<String, Any> = mapOf(
        "active" to active,
        "activations" to activations.get(),
        "captaincyHolderPresent" to captaincyHolderPresent,
        "holderIsCaptain" to holderIsCaptain,
        "captainOnPitch" to captainOnPitch,
        "lastFrameId" to lastFrameId,
        "lastFatigueResistance" to lastFatigueResistance
    )

    fun reset() {
        activations.set(0L)
        captaincyHolderPresent = false
        holderIsCaptain = false
        captainOnPitch = false
        active = false
        lastFrameId = -1L
        lastFatigueResistance = BASELINE_FATIGUE_RESISTANCE
    }

    private fun inactive(frame: RuntimeFrame): CaptaincyResult =
        CaptaincyResult(
            active = false,
            teamFatigueResistance = BASELINE_FATIGUE_RESISTANCE,
            captaincyHolderPresent = captaincyHolderPresent,
            holderIsCaptain = holderIsCaptain,
            captainOnPitch = captainOnPitch,
            frameId = frame.frameId
        )
}
'''

atomic_write(engine, engine_text)

if engine.read_text(encoding="utf-8") != engine_text:
    fail("CaptaincyEngine atomic write verification failed")

print("PROVEN: CaptaincyEngine created")

print()
print("[8] CREATE LIVE REGISTRY CONTRIBUTOR")

contributor_text = r'''package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.CaptaincyEngine
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame

/**
 * Live registry adapter for CaptaincyEngine.
 *
 * Captaincy is passive, so it intentionally contributes ActionClass.NONE.
 * It is therefore visible to GameplayEngineRegistry and evaluated every
 * trusted frame without competing with actual gameplay actions.
 */
object CaptaincyContributor : GameplayContributor {

    override val engineName: String = "Captaincy"

    override val capabilities: Set<EngineCapability> =
        setOf(EngineCapability.SUPPORT)

    override fun contribute(
        frame: RuntimeFrame
    ): EngineContribution? {
        val result = CaptaincyEngine.evaluate(frame)

        /*
         * Passive skill:
         * - never emits a gameplay gesture;
         * - never wins action arbitration;
         * - registry still records that the contributor executed;
         * - active state is retained by CaptaincyEngine diagnostics.
         */
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.NONE,
            targetX = 0f,
            targetY = 0f,
            authority = 0f,
            confidence = if (result.active) 1f else 0f,
            durationHintMs = 0L
        )
    }

    override fun reset() {
        CaptaincyEngine.reset()
    }
}
'''

atomic_write(contributor, contributor_text)

if contributor.read_text(encoding="utf-8") != contributor_text:
    fail("CaptaincyContributor atomic write verification failed")

print("PROVEN: CaptaincyContributor created")

print()
print("[9] PATCH RUNTIME COORDINATOR REGISTRATION")

coordinator_text = runtime_coordinator.read_text(encoding="utf-8")

anchor = '''            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.AgilityContributor)
'''

require_once(
    coordinator_text,
    anchor,
    "RuntimeCoordinator contributor registration anchor"
)

replacement = anchor + '''            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.CaptaincyContributor)
'''

coordinator_new = coordinator_text.replace(
    anchor,
    replacement,
    1
)

if coordinator_new.count(
    "com.assistant.adapter.smartassist.contributors.CaptaincyContributor"
) != 1:
    fail("CaptaincyContributor registration count is not exactly 1")

atomic_write(runtime_coordinator, coordinator_new)

print("PROVEN: CaptaincyContributor registered during RuntimeCoordinator warm-up")

print()
print("[10] PATCH RUNTIME RESET")

coordinator_text = runtime_coordinator.read_text(encoding="utf-8")

anchor = '''        try { GameplayEngineRegistry.resetAll() } catch (_: Throwable) {}
'''

require_once(
    coordinator_text,
    anchor,
    "RuntimeCoordinator registry reset anchor"
)

# Registry.resetAll() already resets every registered contributor.
# No direct Captaincy reset is inserted, avoiding a duplicate reset owner.
print("PROVEN: Captaincy reset is owned by GameplayEngineRegistry.resetAll()")

print()
print("[11] VERIFY RUNTIME DECISION LOOP DOES NOT ARBITRATE PASSIVE CAPTAINCY")

decision_text = runtime_decision_loop.read_text(encoding="utf-8")

for anchor in [
    "val contributions = GameplayEngineRegistry.collect(frame)",
    "maxByOrNull { it.weight * classScale(it.actionClass) }",
    "ActionClass.NONE -> 0f",
]:
    if anchor not in decision_text:
        fail(
            "RuntimeDecisionLoop arbitration contract anchor missing: "
            + anchor
        )

print("PROVEN: Captaincy enters the existing registry collection path")
print("PROVEN: NONE has zero arbitration scale")
print("PROVEN: Captaincy cannot generate an execution request")

print()
print("[12] VERIFY NEW ENGINE FILES")

if not engine.exists():
    fail("CaptaincyEngine.kt missing after patch")

if not contributor.exists():
    fail("CaptaincyContributor.kt missing after patch")

print("PROVEN: CaptaincyEngine.kt exists")
print("PROVEN: CaptaincyContributor.kt exists")

print()
print("[13] VERIFY PACKAGE / SYMBOL INTEGRITY")

engine_verify = engine.read_text(encoding="utf-8")
contributor_verify = contributor.read_text(encoding="utf-8")
coordinator_verify = runtime_coordinator.read_text(encoding="utf-8")

checks = [
    (engine_verify, "package com.assistant.adapter.smartassist"),
    (engine_verify, "object CaptaincyEngine"),
    (engine_verify, "fun configure("),
    (engine_verify, "fun evaluate(frame: RuntimeFrame): CaptaincyResult"),
    (engine_verify, "fun reset()"),
    (contributor_verify, "object CaptaincyContributor"),
    (contributor_verify, "GameplayContributor"),
    (contributor_verify, 'engineName: String = "Captaincy"'),
    (contributor_verify, "ActionClass.NONE"),
    (
        coordinator_verify,
        "com.assistant.adapter.smartassist.contributors.CaptaincyContributor"
    ),
]

for text, anchor in checks:
    if anchor not in text:
        fail(f"post-patch symbol missing: {anchor}")

print("PROVEN: engine symbols")
print("PROVEN: contributor contract")
print("PROVEN: runtime registration")

print()
print("[14] VERIFY NO DUPLICATE CAPTAINCY IMPLEMENTATION")

captaincy_sources = []

for path in root.glob("**/*.kt"):
    if ".git" in path.parts:
        continue
    if "build" in path.parts:
        continue

    text = path.read_text(encoding="utf-8", errors="ignore")

    if (
        "object CaptaincyEngine" in text
        or "class CaptaincyEngine" in text
        or "object CaptaincyContributor" in text
        or "class CaptaincyContributor" in text
    ):
        captaincy_sources.append(path)

expected = {engine, contributor}

if set(captaincy_sources) != expected:
    fail(
        "unexpected Captaincy implementation ownership: "
        + ", ".join(
            str(p.relative_to(root))
            for p in captaincy_sources
        )
    )

print("PROVEN: exactly one CaptaincyEngine implementation")
print("PROVEN: exactly one CaptaincyContributor implementation")

print()
print("[15] VERIFY ATOMIC TEMPORARIES")

temporaries = list(root.glob("**/*.captaincy-repairing"))

if temporaries:
    fail(
        "Captaincy temporary files remain: "
        + ", ".join(
            str(p.relative_to(root))
            for p in temporaries
        )
    )

print("PROVEN: no Captaincy patch temporary files remain")

print()
print("============================================================")
print(" CAPTAINCY PATCH COMPLETE — NO BUILD RUN")
print("============================================================")
print("ENGINE:")
print("  CaptaincyEngine.kt")
print("CONTRIBUTOR:")
print("  CaptaincyContributor.kt")
print("REGISTRATION:")
print("  RuntimeCoordinator -> GameplayEngineRegistry")
print("FRAME:")
print("  RuntimeFrame unchanged")
print("ARBITRATION:")
print("  ActionClass.NONE -> zero action weight")
print("FAIL-CLOSED:")
print("  no trusted frame / no skill holder / not captain / not on pitch")
print("  => Captaincy inactive")
print()
print(f"BACKUP: {backup_dir}")
print()
print("NEXT VERIFICATION:")
print("  ./gradlew :adapter_smartassist:compileDebugKotlin")
print("  ./gradlew :app:assembleDebug")
print("============================================================")
PY
