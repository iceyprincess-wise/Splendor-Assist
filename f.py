#!/usr/bin/env python3
"""Splendor-Assist f1.py — remove dead JNI wrappers + stale build log.

Base: branch main, HEAD 9b85a2f4bf76ae018051b12f3e60022dabf1b8fe (Patch 12).
Fixes (all PROVEN in the File No 1 interactive trace, HEAD 9b85a2f4):
  N-D1  Delete dead JNI export Java_com_assistant_NativeBridge_nativeDirectInputHookPrepare
        (app/src/main/cpp/native_direct_input_hook.c). It only forwards to the
        Impl; the Kotlin side is a regular fun (NativeBridge.kt:320), NOT
        external, so the JVM never resolves it, and no C/C++ caller exists.
  N-D2  Delete dead JNI export Java_com_assistant_NativeBridge_nativePredictiveFrameInterp
        (app/src/main/cpp/native_predictive_frame_interp.c). Same shape:
        forwards to the Impl; Kotlin side is a regular fun (NativeBridge.kt:335).
  N-D3  Delete tracked stale build artifact Build_lint_output.txt (61KB, repo
        root) and add it to .gitignore. Its old symbol dump (line 172) lists
        the dead N-D1 wrapper and can mislead future traces.
NOT included: N-D4 (committed device logs under Splendor-Assist/) — that needs
Ed's decision first; this script does not touch those logs.

Run from the repository root:
  python3 f1.py
The script never commits or pushes. It aborts (nonzero exit) on any failed
precondition or mutation instead of partially applying.
"""

import os
import subprocess
import sys

EXPECTED_HEAD = "9b85a2f4bf76ae018051b12f3e60022dabf1b8fe"
EXPECTED_BRANCH = "main"

RESULTS = []


def record(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + ((" — " + detail) if detail else ""))
    return ok


def fail(name, detail="f.py"):
    record(name, False, detail)
    print("ABORT: a required check failed; no further mutations will be applied.")
    sys.exit(1)


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True)


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def write(path, content):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)


def replace_exact(path, old, new, label):
    """Replace exactly one occurrence. Fail fast; never record PASS after FAIL."""
    content = read(path)
    count = content.count(old)
    if count == 0:
        return None  # caller decides: already-applied vs unexpected change
    if count > 1:
        fail(label, "ambiguous target: %d occurrences in %s" % (count, path))
    write(path, content.replace(old, new, 1))
    return True


def delete_block_if_present(path, old, label, absent_marker):
    """Delete one exact block. Returns True if deleted, False if already gone."""
    content = read(path)
    if content.count(old) == 1:
        write(path, content.replace(old, "", 1))
        record(label, True, "block deleted from %s" % path)
        return True
    if content.count(old) > 1:
        fail(label, "ambiguous target: %d occurrences in %s" % (content.count(old), path))
    # Block absent: already applied only if no trace of the symbol remains.
    if absent_marker in content:
        fail(label, "target block changed unexpectedly in %s (symbol still present)" % path)
    record(label, True, "already applied — block already absent from %s" % path)
    return False


def main():
    print("=== f1.py preconditions ===")
    # 1. Repository root.
    markers = [
        "app/src/main/cpp/native_direct_input_hook.c",
        "app/src/main/cpp/native_predictive_frame_interp.c",
        "settings.gradle.kts",
        "verify.sh",
    ]
    for m in markers:
        if not os.path.isfile(m):
            fail("PRE repo root", "missing marker file: %s (run from the repo root)" % m)
    record("PRE repo root", True, "all marker files present")

    # 2. Branch.
    r = run(["git", "rev-parse", "--abbrev-ref", "HEAD"])
    branch = r.stdout.strip()
    if branch != EXPECTED_BRANCH:
        fail("PRE branch", "expected %s, got %s" % (EXPECTED_BRANCH, branch))
    record("PRE branch", True, branch)

    # 3. HEAD.
    r = run(["git", "rev-parse", "HEAD"])
    head = r.stdout.strip()
    if head != EXPECTED_HEAD:
        fail("PRE HEAD", "expected %s, got %s" % (EXPECTED_HEAD, head))
    record("PRE HEAD", True, head)

    # 4. Clean tree except this patch file.
    r = run(["git", "status", "--porcelain"])
    dirty = [line for line in r.stdout.splitlines() if line.strip() and line.strip() != "?? f1.py"]
    if dirty:
        fail("PRE clean tree", "uncommitted changes present: %s" % "; ".join(dirty))
    record("PRE clean tree", True, "only f1.py untracked")

    # 5. Targets exist with expected content.
    hook_path = "app/src/main/cpp/native_direct_input_hook.c"
    interp_path = "app/src/main/cpp/native_predictive_frame_interp.c"
    hook_old = (
        "JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeDirectInputHookPrepare(\n"
        "    JNIEnv *env,jobject thiz,jfloatArray x,jfloatArray y,jfloatArray t,jint n,\n"
        "    jfloat w,jfloat h,jfloatArray out) {\n"
        "    Java_com_assistant_NativeBridge_nativeDirectInputHookImpl(env,thiz,x,y,t,n,w,h,out);\n"
        "}\n"
    )
    interp_old = (
        "JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativePredictiveFrameInterp(\n"
        "    JNIEnv *env, jobject thiz, jfloatArray px,jfloatArray py,jfloatArray cx,jfloatArray cy,\n"
        "    jfloat dt,jint count,jfloatArray ox,jfloatArray oy) {\n"
        "    Java_com_assistant_NativeBridge_nativePredictiveFrameInterpImpl(env,thiz,px,py,cx,cy,dt,count,ox,oy);\n"
        "}\n"
    )
    for path, old, marker in (
        (hook_path, hook_old, "nativeDirectInputHookPrepare("),
        (interp_path, interp_old, "nativePredictiveFrameInterp("),
    ):
        content = read(path)
        if content.count(old) not in (0, 1):
            fail("PRE target %s" % path, "ambiguous target block")
        if content.count(old) == 0 and marker in content:
            fail("PRE target %s" % path, "wrapper block changed unexpectedly")
    if not os.path.isfile("Build_lint_output.txt"):
        print("NOTE: Build_lint_output.txt already absent (N-D3 delete already applied).")
    record("PRE targets", True, "both wrapper blocks located or already gone")

    print("=== f1.py mutations ===")
    # N-D1.
    delete_block_if_present(
        hook_path, hook_old, "N-D1 delete nativeDirectInputHookPrepare wrapper",
        "nativeDirectInputHookPrepare(",
    )
    # N-D2.
    delete_block_if_present(
        interp_path, interp_old, "N-D2 delete nativePredictiveFrameInterp wrapper",
        "nativePredictiveFrameInterp(",
    )
    # N-D3a. Delete the stale build log.
    if os.path.isfile("Build_lint_output.txt"):
        os.remove("Build_lint_output.txt")
        record("N-D3 delete Build_lint_output.txt", True, "file removed")
    else:
        record("N-D3 delete Build_lint_output.txt", True, "already applied — file already absent")
    # N-D3b. Ignore it going forward.
    gitignore = ".gitignore"
    content = read(gitignore)
    lines = [line.strip() for line in content.splitlines()]
    if "Build_lint_output.txt" in lines:
        record("N-D3 gitignore Build_lint_output.txt", True, "already applied — line present")
    else:
        if content and not content.endswith("\n"):
            content += "\n"
        content += "Build_lint_output.txt\n"
        write(gitignore, content)
        record("N-D3 gitignore Build_lint_output.txt", True, "line appended to .gitignore")

    print("=== f1.py verification ===")
    for path, marker in (
        (hook_path, "nativeDirectInputHookPrepare("),
        (interp_path, "nativePredictiveFrameInterp("),
    ):
        if marker in read(path):
            fail("VERIFY %s" % path, "dead wrapper symbol still present after patch")
        record("VERIFY %s" % path, True, "dead wrapper gone")
    # The Impl functions must survive (only the wrappers go).
    if "nativeDirectInputHookImpl" not in read(hook_path):
        fail("VERIFY Impl kept (hook)", "nativeDirectInputHookImpl was damaged")
    record("VERIFY Impl kept (hook)", True, "nativeDirectInputHookImpl intact")
    if "nativePredictiveFrameInterpImpl" not in read(interp_path):
        fail("VERIFY Impl kept (interp)", "nativePredictiveFrameInterpImpl was damaged")
    record("VERIFY Impl kept (interp)", True, "nativePredictiveFrameInterpImpl intact")
    if os.path.isfile("Build_lint_output.txt"):
        fail("VERIFY Build_lint_output.txt gone", "file still exists")
    record("VERIFY Build_lint_output.txt gone", True, "file absent")
    gitignore_lines = [line.strip() for line in read(gitignore).splitlines()]
    if gitignore_lines.count("Build_lint_output.txt") != 1:
        fail("VERIFY gitignore line", "expected exactly 1 occurrence, got %d"
             % gitignore_lines.count("Build_lint_output.txt"))
    record("VERIFY gitignore line", True, "exactly 1 occurrence")

    print("=== f1.py result ===")
    r = run(["git", "status", "--short"])
    print(r.stdout.rstrip())
    r = run(["git", "diff", "--stat"])
    print(r.stdout.rstrip())
    failed = [name for (name, ok, _d) in RESULTS if not ok]
    if failed:
        print("FAILURES: %s" % ", ".join(failed))
        sys.exit(1)
    print("ALL CHECKS PASS (%d/%d)." % (len(RESULTS), len(RESULTS)))
    print("Next:")
    print("rm f1.py")
    print("git add -A")
    print("git status --short")
    print('git commit -m "Splendor-Assist patch f1: remove dead JNI wrappers, drop stale build log"')
    print("git push origin main")


if __name__ == "__main__":
    main()
