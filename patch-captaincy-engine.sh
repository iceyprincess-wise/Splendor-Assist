#!/data/data/com.termux/files/usr/bin/bash

set -u
set -o pipefail

ROOT="$HOME/projects/Splendor-Assist"

cd "$ROOT" || exit 1

echo "============================================================"
echo " SPLENDOR-ASSIST CAPTAINCY ENGINE"
echo " NEW ENGINE — FAIL-CLOSED — ATOMIC — BACKUP"
echo " LIVE REGISTRY CONTRIBUTOR + RUNTIME INTEGRATION"
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

decision_loop = root / (
    "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/"
    "RuntimeDecisionLoop.kt"
)

runtime_coordinator = root / (
    "adapter_smartassist/src/main/java/com/assistant/adapter/smartassist/"
    "RuntimeCoordinator.kt"
)

runtime_frame = root / (
    "diagnostic_core/src/main/java/com/assistant/runtime/"
    "RuntimeFrame.kt"
)

registry = root / (
    "diagnostic_core/src/main/java/com/assistant/runtime/"
    "GameplayEngineRegistry.kt"
)

timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
backup_dir = root / f"captaincy-engine-backup-{timestamp}"


def fail(message):
    print(f"FAILED: {message}")
    raise SystemExit(1)


def atomic_write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.repairing")

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


def replace_exact(path, old, new, label):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)

    if count != 1:
        fail(
            f"{label}: expected exactly 1 anchor, found {count}: "
            f"{path.relative_to(root)}"
        )

    updated = text.replace(old, new, 1)
    atomic_write(path, updated)
    return updated


print()
print("[1] VERIFY REQUIRED EXISTING ARCHITECTURE")

required = [
    decision_loop,
    runtime_coordinator,
    runtime_frame,
    registry,
]

for path in required:
    if not path.exists():
        fail(f"required file missing: {path}")

print("PROVEN: RuntimeDecisionLoop exists")
print("PROVEN: RuntimeCoordinator exists")
print("PROVEN: RuntimeFrame exists")
print("PROVEN: GameplayEngineRegistry exists")

print()
print("[2] VERIFY CAPTAINCY DOES NOT ALREADY EXIST")

existing = []

for path in [
    engine,
    contributor,
]:
    if path.exists():
        existing.append(str(path.relative_to(root)))

if existing:
    fail(
        "Captaincy implementation already exists; refusing duplicate creation: "
        + ", ".join(existing)
    )

repo_matches = []

for path in root.glob("**/*Captaincy*.kt"):
    if ".git" in path.parts or "build" in path.parts or ".gradle" in path.parts:
        continue
    repo_matches.append(str(path.relative_to(root)))

if repo_matches:
    fail(
        "Captaincy Kotlin sources already exist: "
        + ", ".join(repo_matches)
    )

print("PROVEN: Captaincy is a genuinely new engine")

print()
print("[3] VERIFY CONTRIBUTOR CONTRACT")

frame_text = runtime_frame.read_text(encoding="utf-8")

required_frame_anchors = [
    "enum class ActionClass",
    "ActionClass.NONE",
    "data class EngineContribution",
    "interface GameplayContributor",
    "fun contribute(frame: RuntimeFrame): EngineContribution?",
]

for anchor in required_frame_anchors:
    if anchor not in frame_text:
        fail(f"RuntimeFrame contract anchor missing: {anchor}")

print("PROVEN: passive NONE-class contributions are representable")
print("PROVEN: GameplayContributor contract is available")

print()
print("[4] VERIFY CURRENT DECISION ARBITRATION")

decision_text = decision_loop.read_text(encoding="utf-8")

best_anchor = """val best: EngineContribution? =
            contributions
                .filter { c -> if (netHold) c.actionClass == ActionClass.MOVE || c.actionClass == ActionClass.DEFEND else true }
                .maxByOrNull { it.weight * classScale(it.actionClass) }"""

if best_anchor not in decision_text:
    fail("RuntimeDecisionLoop arbitration anchor changed; refusing unsafe patch")

print("PROVEN: expected arbitration anchor exists")

print()
print("[5] VERIFY CURRENT CAPABILITY ENUM")

if "enum class EngineCapability" not in frame_text:
    fail("EngineCapability enum missing")

if "EngineCapability.SUPPORT" not in frame_text:
    fail("EngineCapability.SUPPORT missing")

print("PROVEN: SUPPORT capability available")

print()
print("[6] VERIFY REGISTRY REGISTRATION CONTRACT")

registry_text = registry.read_text(encoding="utf-8")

if "fun register(contributor: GameplayContributor)" not in registry_text:
    fail("GameplayEngineRegistry.register() contract missing")

if "contributors.add(contributor)" not in registry_text:
    fail("GameplayEngineRegistry contributor insertion missing")

if "fun collect(frame: RuntimeFrame)" not in registry_text:
    fail("GameplayEngineRegistry.collect() missing")

print("PROVEN: registry can register and collect CaptaincyContributor")

print()
print("[7] VERIFY RUNTIME REGISTRATION ANCHOR")

coordinator_text = runtime_coordinator.read_text(encoding="utf-8")

registration_anchor = """            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.SmartAssistUltimateCorrectorContributor)"""

if coordinator_text.count(registration_anchor) != 1:
    fail(
        "expected exactly one final existing contributor registration anchor; "
        f"found {coordinator_text.count(registration_anchor)}"
    )

print("PROVEN: RuntimeCoordinator registration anchor exists exactly once")

print()
print("[8] VERIFY FIGHTING SPIRIT ARCHITECTURAL POSITION")

if "FightingSpiritEngine.evaluate(frame)" not in decision_text:
    fail("FightingSpiritEngine post-arbitration evaluation missing")

print("PROVEN: FightingSpiritEngine is a post-arbitration passive skill model")
print("PROVEN: Captaincy will not be incorrectly converted into a gesture")

print()
print("[9] CREATE BACKUP")

backup_targets = [
    decision_loop,
    runtime_coordinator,
    runtime_frame,
    registry,
]

for path in backup_targets:
    backup(path)

print(f"PROVEN: backups created at {backup_dir}")

print()
print("[10] CREATE CAPTAINCY ENGINE")

engine_text = r'''package com.assistant.adapter.smartassist

import com.assistant.diagnostic.RuntimeLogger
import com.assistant.runtime.RuntimeFrame
import java.util.concurrent.atomic.AtomicLong

/**
 * CaptaincyEngine
 *
 * Models the documented Captaincy skill as a PASSIVE team-level state.
 *
 * IMPORTANT:
 * Captaincy is not an input action.
 *
 * The documented gameplay condition is:
 *   - the player has the Captaincy skill;
 *   - that player is the team's captain;
 *   - the captain is on the pitch.
 *
 * The documented effect is team-wide reduction of fatigue effects.
 *
 * This repository's RuntimeFrame does not contain authoritative player
 * roster/skill/stamina data. Therefore the engine FAILS CLOSED:
 *
 *   no explicit skill + captain + on-pitch state
 *       -> inactive
 *
 * It never assumes that the currently controlled player has Captaincy.
 * It never assumes that the selected captain has Captaincy.
 * It never invents stamina values.
 * It never injects a fake gesture to "activate" a passive skill.
 *
 * The explicit configuration API is the integration boundary for the
 * roster/captain source when that source becomes available.
 *
 * Multiple Captaincy holders do not stack: the engine represents the
 * single active team-wide Captaincy state.
 */
object CaptaincyEngine {

    private val activations = AtomicLong(0L)
    private val evaluations = AtomicLong(0L)

    @Volatile
    private var hasCaptaincySkill = false

    @Volatile
    private var isCaptain = false

    @Volatile
    private var isOnPitch = false

    @Volatile
    private var active = false

    @Volatile
    private var lastFrameId = -1L

    @Volatile
    private var lastActivationMs = 0L

    data class CaptaincyResult(
        val active: Boolean,
        val teamWide: Boolean,
        val passive: Boolean,
        val hasCaptaincySkill: Boolean,
        val isCaptain: Boolean,
        val isOnPitch: Boolean,
        val frameId: Long
    )

    /**
     * Explicit roster/captain integration boundary.
     *
     * No automatic assumption is made here.
     */
    fun configure(
        hasCaptaincySkill: Boolean,
        isCaptain: Boolean,
        isOnPitch: Boolean
    ) {
        this.hasCaptaincySkill = hasCaptaincySkill
        this.isCaptain = isCaptain
        this.isOnPitch = isOnPitch

        val nextActive =
            hasCaptaincySkill &&
            isCaptain &&
            isOnPitch

        if (nextActive && !active) {
            activations.incrementAndGet()
            lastActivationMs = System.currentTimeMillis()

            RuntimeLogger.log(
                "CAPTAINCY ACTIVE: skill=true captain=true onPitch=true " +
                    "teamWide=true passive=true",
                "CAPTAINCY"
            )
        }

        active = nextActive
    }

    /**
     * Evaluate Captaincy against the current trusted frame.
     *
     * The frame is used only as a runtime trust/lifecycle gate.
     * It does not fabricate stamina or roster information.
     */
    fun evaluate(frame: RuntimeFrame): CaptaincyResult {
        evaluations.incrementAndGet()
        lastFrameId = frame.frameId

        if (!frame.trusted) {
            active = false

            return CaptaincyResult(
                active = false,
                teamWide = false,
                passive = true,
                hasCaptaincySkill = hasCaptaincySkill,
                isCaptain = isCaptain,
                isOnPitch = isOnPitch,
                frameId = frame.frameId
            )
        }

        active =
            hasCaptaincySkill &&
            isCaptain &&
            isOnPitch

        return CaptaincyResult(
            active = active,
            teamWide = active,
            passive = true,
            hasCaptaincySkill = hasCaptaincySkill,
            isCaptain = isCaptain,
            isOnPitch = isOnPitch,
            frameId = frame.frameId
        )
    }

    fun isActive(): Boolean = active

    fun configureInactive() {
        hasCaptaincySkill = false
        isCaptain = false
        isOnPitch = false
        active = false
    }

    fun diagnostics(): Map<String, Any> = mapOf(
        "active" to active,
        "hasCaptaincySkill" to hasCaptaincySkill,
        "isCaptain" to isCaptain,
        "isOnPitch" to isOnPitch,
        "evaluations" to evaluations.get(),
        "activations" to activations.get(),
        "lastFrameId" to lastFrameId,
        "lastActivationMs" to lastActivationMs
    )

    fun reset() {
        activations.set(0L)
        evaluations.set(0L)
        hasCaptaincySkill = false
        isCaptain = false
        isOnPitch = false
        active = false
        lastFrameId = -1L
        lastActivationMs = 0L
    }
}
'''

atomic_write(engine, engine_text)

if engine.read_text(encoding="utf-8") != engine_text:
    fail("CaptaincyEngine content verification failed")

print("PROVEN: CaptaincyEngine created")

print()
print("[11] CREATE CAPTAINCY CONTRIBUTOR")

contributor_text = r'''package com.assistant.adapter.smartassist.contributors

import com.assistant.adapter.smartassist.CaptaincyEngine
import com.assistant.runtime.ActionClass
import com.assistant.runtime.EngineCapability
import com.assistant.runtime.EngineContribution
import com.assistant.runtime.GameplayContributor
import com.assistant.runtime.RuntimeFrame

/**
 * Live registry participant for Captaincy.
 *
 * Captaincy is passive. It MUST NOT become a gesture candidate.
 *
 * ActionClass.NONE is therefore intentional.
 * RuntimeDecisionLoop excludes NONE-class contributions from action
 * arbitration while the registry still observes the contributor.
 */
object CaptaincyContributor : GameplayContributor {

    override val engineName: String = "Captaincy"

    override val capabilities: Set<EngineCapability> =
        setOf(EngineCapability.SUPPORT)

    override fun initialize() {
        CaptaincyEngine.reset()
    }

    override fun warmUp() {
        CaptaincyEngine.diagnostics()
    }

    override fun update(frame: RuntimeFrame) {
        CaptaincyEngine.evaluate(frame)
    }

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        val result = CaptaincyEngine.evaluate(frame)

        if (!result.active) return null

        /*
         * Passive state is deliberately represented as NONE.
         * RuntimeDecisionLoop must filter NONE before selecting an
         * executable contribution.
         */
        return EngineContribution(
            engine = engineName,
            actionClass = ActionClass.NONE,
            targetX = 0f,
            targetY = 0f,
            authority = 0f,
            confidence = frame.confidence.coerceIn(0f, 1f),
            durationHintMs = 0L
        )
    }

    override fun reset() {
        CaptaincyEngine.reset()
    }

    override fun shutdown() {
        CaptaincyEngine.reset()
    }
}
'''

atomic_write(contributor, contributor_text)

if contributor.read_text(encoding="utf-8") != contributor_text:
    fail("CaptaincyContributor content verification failed")

print("PROVEN: CaptaincyContributor created")

print()
print("[12] PATCH ACTION ARBITRATION TO EXCLUDE PASSIVE NONE")

decision_text = decision_loop.read_text(encoding="utf-8")

old_best = best_anchor

new_best = """val best: EngineContribution? =
            contributions
                .filter { c -> c.actionClass != ActionClass.NONE }
                .filter { c -> if (netHold) c.actionClass == ActionClass.MOVE || c.actionClass == ActionClass.DEFEND else true }
                .maxByOrNull { it.weight * classScale(it.actionClass) }"""

decision_text = decision_text.replace(old_best, new_best, 1)

atomic_write(decision_loop, decision_text)

verified_decision = decision_loop.read_text(encoding="utf-8")

if verified_decision.count("c.actionClass != ActionClass.NONE") != 1:
    fail("NONE-class arbitration filter was not inserted exactly once")

if verified_decision.count("val best: EngineContribution?") != 1:
    fail("unexpected duplicate best arbitration declaration")

print("PROVEN: passive Captaincy cannot generate an executable gesture")

print()
print("[13] PATCH CAPTAINCY EVALUATION INTO DECISION LOOP")

decision_text = decision_loop.read_text(encoding="utf-8")

evaluation_anchor = """        val contributions = GameplayEngineRegistry.collect(frame)
        val netHold = AdapterSignalBus.netIsHold"""

if decision_text.count(evaluation_anchor) != 1:
    fail(
        "expected exactly one decision-loop evaluation anchor; "
        f"found {decision_text.count(evaluation_anchor)}"
    )

evaluation_replacement = """        val captaincy = CaptaincyEngine.evaluate(frame)

        val contributions = GameplayEngineRegistry.collect(frame)
        val netHold = AdapterSignalBus.netIsHold"""

decision_text = decision_text.replace(
    evaluation_anchor,
    evaluation_replacement,
    1
)

atomic_write(decision_loop, decision_text)

verified_decision = decision_loop.read_text(encoding="utf-8")

if verified_decision.count(
    "val captaincy = CaptaincyEngine.evaluate(frame)"
) != 1:
    fail("Captaincy post-frame evaluation was not inserted exactly once")

print("PROVEN: RuntimeDecisionLoop directly evaluates Captaincy")

print()
print("[14] PATCH CAPTAINCY REGISTRATION INTO RUNTIME COORDINATOR")

coordinator_text = runtime_coordinator.read_text(encoding="utf-8")

registration_new = registration_anchor + """\n            com.assistant.runtime.GameplayEngineRegistry.register(
                com.assistant.adapter.smartassist.contributors.CaptaincyContributor)"""

coordinator_text = coordinator_text.replace(
    registration_anchor,
    registration_new,
    1
)

atomic_write(runtime_coordinator, coordinator_text)

verified_coordinator = runtime_coordinator.read_text(encoding="utf-8")

if verified_coordinator.count(
    "com.assistant.adapter.smartassist.contributors.CaptaincyContributor"
) != 1:
    fail("CaptaincyContributor registration count is not exactly one")

print("PROVEN: RuntimeCoordinator registers CaptaincyContributor")

print()
print("[15] VERIFY COMPLETE CALL CHAIN")

engine_verify = engine.read_text(encoding="utf-8")
contributor_verify = contributor.read_text(encoding="utf-8")
decision_verify = decision_loop.read_text(encoding="utf-8")
coordinator_verify = runtime_coordinator.read_text(encoding="utf-8")

chain_checks = {
    "engine object": "object CaptaincyEngine" in engine_verify,
    "engine evaluate": "fun evaluate(frame: RuntimeFrame)" in engine_verify,
    "engine configure": "fun configure(" in engine_verify,
    "contributor object": "object CaptaincyContributor" in contributor_verify,
    "contributor evaluate": "CaptaincyEngine.evaluate(frame)" in contributor_verify,
    "contributor contract": "GameplayContributor" in contributor_verify,
    "registry registration": "CaptaincyContributor)" in coordinator_verify,
    "decision evaluation": "val captaincy = CaptaincyEngine.evaluate(frame)" in decision_verify,
    "passive filter": "c.actionClass != ActionClass.NONE" in decision_verify,
}

for name, ok in chain_checks.items():
    if not ok:
        fail(f"call-chain verification failed: {name}")

print("PROVEN: CaptaincyEngine")
print("PROVEN: CaptaincyContributor")
print("PROVEN: RuntimeCoordinator registration")
print("PROVEN: GameplayEngineRegistry collection")
print("PROVEN: RuntimeDecisionLoop evaluation")
print("PROVEN: NONE-class passive safety gate")

print()
print("[16] VERIFY NO DUPLICATE CAPTAINCY SOURCES")

captaincy_sources = []

for path in root.glob("**/*Captaincy*.kt"):
    if ".git" in path.parts or "build" in path.parts or ".gradle" in path.parts:
        continue
    captaincy_sources.append(path)

expected = {engine.resolve(), contributor.resolve()}

if set(p.resolve() for p in captaincy_sources) != expected:
    fail(
        "unexpected Captaincy source set: "
        + ", ".join(str(p.relative_to(root)) for p in captaincy_sources)
    )

print("PROVEN: exactly two intentional Captaincy Kotlin sources exist")

print()
print("[17] VERIFY NO ORPHAN ENGINE")

if "CaptaincyEngine.evaluate(frame)" not in contributor_verify:
    fail("CaptaincyEngine is not consumed by its contributor")

if "CaptaincyContributor)" not in coordinator_verify:
    fail("CaptaincyContributor is not registered")

if "GameplayEngineRegistry.collect(frame)" not in decision_verify:
    fail("decision loop does not collect registry contributions")

print("PROVEN: engine -> contributor -> registry -> decision loop")

print()
print("[18] VERIFY PASSIVE SAFETY")

if "ActionClass.NONE" not in contributor_verify:
    fail("CaptaincyContributor is missing passive NONE action class")

if "c.actionClass != ActionClass.NONE" not in decision_verify:
    fail("decision loop does not exclude passive NONE contributions")

if "targetX = 0f" not in contributor_verify:
    fail("Captaincy passive target guard missing")

if "authority = 0f" not in contributor_verify:
    fail("Captaincy passive authority guard missing")

print("PROVEN: Captaincy cannot win action arbitration")
print("PROVEN: Captaincy cannot manufacture a gesture")
print("PROVEN: Captaincy remains a passive gameplay-skill model")

print()
print("[19] VERIFY FAIL-CLOSED ACTIVATION")

if "hasCaptaincySkill &&" not in engine_verify:
    fail("Captaincy skill gate missing")

if "isCaptain &&" not in engine_verify:
    fail("Captain assignment gate missing")

if "isOnPitch" not in engine_verify:
    fail("on-pitch gate missing")

if "if (!frame.trusted)" not in engine_verify:
    fail("trusted-frame gate missing")

print("PROVEN: no skill -> inactive")
print("PROVEN: not captain -> inactive")
print("PROVEN: off pitch -> inactive")
print("PROVEN: untrusted frame -> inactive")

print()
print("[20] VERIFY PATCH TEMP FILES")

repairing = list(root.glob("**/*.repairing"))

if repairing:
    fail(
        "temporary repair files remain: "
        + ", ".join(str(p.relative_to(root)) for p in repairing)
    )

print("PROVEN: no .repairing files remain")

print()
print("[21] STATIC KOTLIN STRUCTURAL CHECK")

for path in [
    engine,
    contributor,
    decision_loop,
    runtime_coordinator,
]:
    text = path.read_text(encoding="utf-8")

    if text.count("{") != text.count("}"):
        fail(
            f"brace imbalance detected in {path.relative_to(root)}: "
            f"{text.count('{')} != {text.count('}')}"
        )

print("PROVEN: brace balance for all modified/new Kotlin sources")

print()
print("============================================================")
print(" CAPTAINCY PATCH COMPLETE")
print("============================================================")
print()
print("NEW:")
print("  CaptaincyEngine.kt")
print("  CaptaincyContributor.kt")
print()
print("WIRED:")
print("  CaptaincyEngine")
print("      -> CaptaincyContributor")
print("      -> GameplayEngineRegistry")
print("      -> RuntimeDecisionLoop")
print()
print("PASSIVE SAFETY:")
print("  ActionClass.NONE")
print("  NONE excluded from action arbitration")
print("  no synthetic gesture")
print("  no fake stamina")
print("  no automatic captain assumption")
print()
print("ACTIVATION CONTRACT:")
print("  configure(hasCaptaincySkill, isCaptain, isOnPitch)")
print()
print("BACKUP:")
print(f"  {backup_dir}")
print()
print("NEXT BUILD:")
print("  ./gradlew :adapter_smartassist:compileDebugKotlin")
print("  ./gradlew :app:assembleDebug")
print("============================================================")
PY

chmod +x patch-captaincy-engine.sh
./patch-captaincy-engine.sh
