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
- make the massive  accurate  necessary change;
- verify the resulting mutation;
- print clear PASS/FAIL output.

Ensure while fixing be on the watch out precisely for newly generated defects;

Wbile investigating, do not ignore a defect/issue/bottleneck  because it wasnt among assigned task,add it,prodvide fix patch for it and while dropping the patch script content to be executed on termux inform developer what you addtionally discovered,and that youve already worked on it precisely and accurately no assumption or guess and its in the python3 patch script content pasted whats left for developer is to copy, go to termux execute nano,paste the content then Ctrl+O then enter followed by Ctrl+X then run it.

Ensure no remaining work is left purposefully unattended to or ignored,one task patch script must execute a minimum of 20+ defects fixes both from main task provided and discovery from or during investigation.

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

Ensure The app overally is wired properly,fully connected,Not starved of data,effect is real and truthful not fabricated or Hardcoded as well pipeline is connected to live real active telemetry.

🚨 INVESTIGATE ALL CLAIMS AND ASSUMPTIONS FROM DEEP INVESTIGATIONS AND FIX THESE DEFECTS FROM 
TASK AND DISCOVERY MASSIVELY ACCURATE.
ENSURE ACCURATE FIXES APPLIED EACH OF THEM ALL FIXES IN ONE PYTHON3 PATCH FIX SCRIPT, AS USUAL PASTE THE CONTENT OF THE F.PY FIX PATCH WHICH I'LL EXECUTE IN MY TERMUX AND PUSH TO MAIN, AFTER ENSURING ADEQUATE ACCURATE VERIFICATION OF FIXES AND BUILD COMPLIANCE WITH NO WARNING, ERROR, ASSUMPTIONS, GUESSING AND FAKING 

TARGET AGGREGATE: App must be effective, no parameters left unused, and engine actively working but the effect isnt feed properly,all engines must be effectively working and feeding the pipeline,using the overlay,mediaproj,accessibility and other integration to ensure it starts effectively and feeds effectively there by being effective on game screen during tests. Engines Should be traced properly starting with what's it's role is in teh repository,what it role would be on game and how it will play it role using all available tools, engines, integration and implementation on the repository to be effective and must be grep and trace properly not assumed or guessed that from wiring it's active and effective.
