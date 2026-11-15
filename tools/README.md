# tools/

Helper scripts and proofs for the Splendor-Assist build. Nothing here ships
in the APK.

## Proofs (run them before pushing)

```sh
sh tools/run-proofs.sh
```

That runs both proofs and fails loudly if either fails. Or run them directly:

```sh
python3 tools/verify-termux-aapt2.py
JAVA_HOME=~/jdk17 python3 tools/verify-native-contracts.py
```

| File | What it proves |
| --- | --- |
| `verify-termux-aapt2.py` | `termux-aapt2.sh` picks the right aapt2 on Linux and on Termux (10 cases: Linux passthrough, Termux fallback, spaces, explicit override, missing/directory/non-executable, PREFIX-gate, HOME candidate, config candidate). |
| `verify-native-contracts.py` | The NativeBridge JNI contracts: all 26 methods (20 runtime-tested, 5 valid-input-only, 1 static-only) plus the 2-thread race test and 3 static contracts. |

## The wrapper

`termux-aapt2.sh` is sourced by `gradlew` (line 90) before Gradle starts. On
Termux it prepends `-Pandroid.aapt2FromMavenOverride=<ARM-host aapt2>` so AGP
uses the device's own aapt2 instead of the x86_64 one from Maven. Precedence:
explicit `-P` property > `SPLENDOR_AAPT2` > `splendor.termuxAapt2` in
`gradle.properties` > `$PREFIX/bin/aapt2` > `$HOME/bin/aapt2`. On any other
host it does nothing.

## Known limits (not bugs in the proofs)

- `nativeBreadcrumbDump` is static-only: `native_breadcrumb.c` includes
  `<android/log.h>` and cannot compile on a Linux host. Its C signature is
  checked against the Kotlin declaration instead of being called.
- `nativeInjectTap` / `nativeInjectSwipe` are static-only: `native_input.cpp`
  needs NDK headers and the methods delegate to AccessibilityService on
  device. Their signatures are checked statically.
- The 5 valid-input-only methods (`nativeAuthorityArbitrate`,
  `nativeBallRetentionShieldCompute`, `nativeInstantInterceptCompute`,
  `nativeBuildUpPressCompute`, `nativeExtractBlobs`) return silently / `-1`
  on bad input instead of throwing `IllegalArgumentException` like the
  `splendor_contract.h` methods do. That is a C-side gap (Task 5), so the
  proof tests them with valid inputs only.
