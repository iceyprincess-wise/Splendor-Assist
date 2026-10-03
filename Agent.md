Splendor-Assist Agent Rules

Operating Model

The repository is maintained through:

Jules investigation → Python 3 patch script → Termux execution → build/validation → Git push to "main".

Jules should normally investigate and produce the patch script. The human developer executes the generated patch in Termux and pushes the resulting changes.

Treat the current "main" branch as the authoritative source of truth.

Investigation

Do not modify code before establishing the root cause.

Trace relevant behavior as:

CALLER → ENGINE → CALLEE → MUTATION → LIVE EFFECT

Separate evidence into:

- PROVEN
- LIKELY
- UNVERIFIED
- BLOCKED

Do not present inferred runtime behavior as proven.

Patch Generation

Return a complete executable Python 3 patch script.

The script must:

- use exact repository-relative paths;
- verify expected source before modification;
- fail loudly on missing/mismatched targets;
- prevent silent partial application;
- avoid duplicate changes;
- preserve unrelated code;
- make the smallest necessary change;
- verify the resulting mutation;
- print clear PASS/FAIL output.

Do not require manual source editing after generating the script.

Validation

After a patch is executed, validation should use the repository's real build/test tooling.

For Android, preserve the repository's configured Gradle/JDK/SDK/NDK/CMake environment.

When a validation failure occurs, determine whether it is caused by the patch, pre-existing, or environmental before changing additional code.

Never declare success from source inspection alone when executable validation is available.

Scope Control

No speculative optimization.

No unrelated refactoring.

No architecture rewrite.

No cosmetic cleanup unless explicitly requested.

Preserve existing behavior outside the proven root cause.

Reporting

Use:

PROVEN
LIKELY
UNVERIFIED / BLOCKED
TRACE
ROOT CAUSE
PATCH
VALIDATION
REGRESSION CHECK
REMAINING WORK

When the developer pushes a patch to "main", treat that new repository state as authoritative and re-investigate changed behavior rather than relying on stale task assumptions.
