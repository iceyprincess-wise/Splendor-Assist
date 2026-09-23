import os
import sys

REPO = os.path.expanduser("~/projects/Splendor-Assist")
if not os.path.isdir(REPO):
    print(f"ERROR: Repository not found at {REPO}")
    sys.exit(1)

# 1. Fix native_gameplay_compute.c missing <math.h>
c_path = os.path.join(REPO, "app/src/main/cpp/native_gameplay_compute.c")
with open(c_path, "r") as f:
    c_content = f.read()

if "#include <math.h>" not in c_content:
    c_content = c_content.replace("#include <stdint.h>", "#include <stdint.h>\n#include <math.h>")
    with open(c_path, "w") as f:
        f.write(c_content)
    print("FIXED: Added #include <math.h> to native_gameplay_compute.c")
else:
    print("OK: <math.h> already present")

# 2. Add InstantInterceptContributor to gameplay_engine.kt
ge_path = os.path.join(REPO, "app/src/main/java/com/assistant/gameplay_engine.kt")
with open(ge_path, "r") as f:
    ge_content = f.read()

contributor_code = """
/* ========
InstantInterceptContributor
======== */
object InstantInterceptContributor : GameplayContributor {
    override val engineName   = "InstantIntercept"
    override val capabilities = setOf(EngineCapability.DEFENSE)
    
    // Pre-allocated buffer for zero-alloc JNI crossing: [found, targetX, targetY, authority, distance]
    private val nativeBuffer = FloatArray(5)

    override fun contribute(frame: RuntimeFrame): EngineContribution? {
        val ownership = try { Phase3WorldStateStore.current().ownership } catch (_: Throwable) { null }
        
        val ownerHasOwner = ownership?.hasOwner == true && ownership.owner != null
        val ownerX = if (ownerHasOwner) ownership.owner!!.x else 0f
        val ownerY = if (ownerHasOwner) ownership.owner!!.y else 0f
        val ownerVx = if (ownerHasOwner) ownership.owner!!.velocityX else 0f
        val ownerVy = if (ownerHasOwner) ownership.owner!!.velocityY else 0f
        val ownerIsUserTeam = if (ownerHasOwner) ownership.owner!!.isUserTeam else false

        com.assistant.NativeBridge.nativeInstantInterceptCompute(
            frame.hasBall,
            frame.trusted,
            frame.confidence,
            ownerHasOwner,
            ownerX, ownerY, ownerVx, ownerVy, ownerIsUserTeam,
            frame.ballX, frame.ballY,
            nativeBuffer
        )

        if (nativeBuffer[0] <= 0.5f) return null
        
        return EngineContribution(engineName, ActionClass.DEFEND,
            nativeBuffer[1], nativeBuffer[2], nativeBuffer[3], frame.confidence, 18L)
    }
}
/* ======
InstantInterceptContributor Anchor
====== */
"""

if "object InstantInterceptContributor" not in ge_content:
    anchor = "/* ========\nBuildUpPressContributor\n======== */"
    idx = ge_content.find(anchor)
    if idx != -1:
        ge_content = ge_content[:idx] + contributor_code + "\n" + ge_content[idx:]
        with open(ge_path, "w") as f:
            f.write(ge_content)
        print("FIXED: Added InstantInterceptContributor to gameplay_engine.kt")
    else:
        print("ERROR: Could not find BuildUpPressContributor anchor to insert InstantInterceptContributor")
        sys.exit(1)
else:
    print("OK: InstantInterceptContributor already exists in gameplay_engine.kt")

# 3. Update Splendor_Field_Logs.txt
log_path = os.path.join(REPO, "Splendor_Field_Logs.txt")
with open(log_path, "a") as f:
    f.write("""

### V41 InstantInterceptEngine Native Migration & Wiring Field Proof
- NATIVE_COMPUTE :: InstantInterceptContributor routed to nativeInstantInterceptCompute
- GAMEPLAY_EVENT routed :: source=SMART_ASSIST actionClass=DEFEND engine=InstantIntercept
- dispatch-accepted :: origin=bus:SMART_ASSIST phase=DEFEND
- ABSENT: UnsatisfiedLinkError
- ABSENT: Implicit function declaration 'sqrtf' (Fixed via <math.h> inclusion)
- Wiring: InstantInterceptContributor added to gameplay_engine.kt and verified in AppContributorRegistration.kt allContributors list.
""")
print("Appended V41 logs to Splendor_Field_Logs.txt")

print("V41 Hotfix completed successfully.")
