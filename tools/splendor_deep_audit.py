#!/usr/bin/env python3

from pathlib import Path
from collections import defaultdict
import re
import os
import sys
import hashlib
import subprocess
from datetime import datetime

ROOT = Path.cwd()
REPORT_DIR = ROOT / "architecture-audit"
REPORT_TXT = REPORT_DIR / "splendor-deep-audit.txt"
REPORT_MD = REPORT_DIR / "splendor-deep-audit.md"

SKIP_DIRS = {
    ".git", ".gradle", "build", ".idea", ".cxx",
    "node_modules", "dist", "captures", ".externalNativeBuild"
}

EXTENSIONS = {
    ".kt", ".kts", ".java", ".xml", ".gradle",
    ".properties", ".json", ".toml", ".pro", ".xml"
}

findings = []
stats = defaultdict(int)
class_map = defaultdict(list)
function_map = defaultdict(list)
resource_ids = defaultdict(list)

def add(severity, category, path, line, message):
    item = {
        "severity": severity,
        "category": category,
        "path": str(path.relative_to(ROOT)),
        "line": line,
        "message": message
    }
    findings.append(item)

def rel(path):
    try:
        return str(path.relative_to(ROOT))
    except Exception:
        return str(path)

def read_text(path):
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except Exception as e:
        add("ERROR", "READ", path, 0, f"Cannot read file: {e}")
        return None

def line_no(text, pos):
    return text.count("\n", 0, pos) + 1

def scan_kotlin_java(path, text):
    ext = path.suffix

    # Suspicious swallowed exceptions.
    for m in re.finditer(r'catch\s*\([^)]*\)\s*\{\s*\}', text):
        add("HIGH", "SILENT_FAILURE", path, line_no(text, m.start()),
            "Empty catch block swallows an exception.")

    for m in re.finditer(
        r'catch\s*\([^)]*\)\s*\{[^{}]{0,300}'
        r'(?:Log\.[a-zA-Z]+\s*\([^)]*\))?'
        r'\s*\}',
        text
    ):
        block = m.group(0)
        if "throw" not in block and "return" not in block and len(block) < 350:
            add("MEDIUM", "EXCEPTION_HANDLING", path, line_no(text, m.start()),
                "Exception appears to be caught without clear propagation/recovery.")

    # TODO/FIXME/HACK in executable source.
    for i, line in enumerate(text.splitlines(), 1):
        if re.search(r'\b(TODO|FIXME|XXX|HACK)\b', line, re.I):
            add("LOW", "DEFERRED_WORK", path, i,
                "Deferred-work marker found.")

    # Force unwraps.
    for i, line in enumerate(text.splitlines(), 1):
        if "!!" in line and not line.strip().startswith("//"):
            add("MEDIUM", "NULLABILITY", path, i,
                "Kotlin force-unwrap (!!) found; possible runtime crash boundary.")

    # Global mutable state.
    for i, line in enumerate(text.splitlines(), 1):
        if re.search(r'\b(?:object|companion\s+object)\b', line):
            add("LOW", "GLOBAL_STATE", path, i,
                "Singleton/global-state declaration found; inspect lifecycle ownership.")

    # Coroutine/global scope hazards.
    for i, line in enumerate(text.splitlines(), 1):
        if re.search(r'\bGlobalScope\b', line):
            add("HIGH", "LIFECYCLE", path, i,
                "GlobalScope detected; coroutine may outlive owning component.")

    # Thread/executor lifecycle.
    for i, line in enumerate(text.splitlines(), 1):
        if re.search(r'\b(?:Thread|ExecutorService|ScheduledExecutorService)\b', line):
            add("MEDIUM", "LIFECYCLE", path, i,
                "Thread/executor usage found; verify shutdown/reset ownership.")

    # Accessibility dispatch owners.
    if "dispatchGesture(" in text:
        for i, line in enumerate(text.splitlines(), 1):
            if "dispatchGesture(" in line:
                add("HIGH", "INPUT_OWNER", path, i,
                    "dispatchGesture() call found; verify this is an intentional single dispatch owner.")

    # Android service declarations/classes.
    service_patterns = [
        r'class\s+\w+\s*:\s*AccessibilityService',
        r'class\s+\w+\s*:\s*Service',
        r'class\s+\w+\s*:\s*BroadcastReceiver',
        r'class\s+\w+\s*:\s*NotificationListenerService'
    ]
    for pattern in service_patterns:
        for m in re.finditer(pattern, text):
            name = re.search(r'class\s+(\w+)', m.group(0))
            if name:
                add("INFO", "COMPONENT", path, line_no(text, m.start()),
                    f"Android component detected: {name.group(1)}")

    # Classes.
    for m in re.finditer(
        r'\b(?:class|object|interface|enum\s+class|data\s+class|sealed\s+class)\s+([A-Za-z_]\w*)',
        text
    ):
        name = m.group(1)
        class_map[name].append(path)

    # Functions.
    for m in re.finditer(
        r'\bfun\s+([A-Za-z_]\w*)\s*\(',
        text
    ):
        function_map[m.group(1)].append(path)

    # Suspicious TODO implementation placeholders.
    for i, line in enumerate(text.splitlines(), 1):
        stripped = line.strip()
        if re.search(r'\breturn\s+(null|false|true|0)\s*(//.*)?$', stripped):
            if not stripped.startswith("//"):
                add("LOW", "RETURN_LITERAL", path, i,
                    "Literal return detected; inspect whether it is an intentional fallback or stub.")

def scan_xml(path, text):
    # Duplicate android:id values within one XML file.
    ids = defaultdict(list)
    for m in re.finditer(r'android:id\s*=\s*["\']@\+id/([^"\']+)', text):
        ids[m.group(1)].append(line_no(text, m.start()))

    for name, lines in ids.items():
        if len(lines) > 1:
            add("HIGH", "XML_DUPLICATE_ID", path, lines[0],
                f"Duplicate @+id/{name} declarations in same XML file.")

    # Missing closing angle brackets / obviously broken XML fragments.
    if text.count("<") != text.count(">"):
        add("HIGH", "XML_STRUCTURE", path, 0,
            "XML angle-bracket count is unbalanced.")

def scan_gradle(path, text):
    for i, line in enumerate(text.splitlines(), 1):
        if "jcenter()" in line:
            add("MEDIUM", "BUILD", path, i,
                "jcenter() dependency repository detected.")

        if re.search(r'compile\s+["\']', line):
            add("HIGH", "BUILD", path, i,
                "Legacy compile configuration detected.")

        if "allowBackup = true" in line or "android:allowBackup=\"true\"" in line:
            add("LOW", "CONFIG", path, i,
                "Backup enabled; verify this is intentional.")

def scan_manifest(path, text):
    components = defaultdict(list)

    for m in re.finditer(
        r'<(activity|service|receiver|provider)\b[^>]*android:name\s*=\s*["\']([^"\']+)',
        text
    ):
        kind, name = m.groups()
        components[(kind, name)].append(line_no(text, m.start()))

    for (kind, name), lines in components.items():
        if len(lines) > 1:
            add("HIGH", "MANIFEST_DUPLICATE", path, lines[0],
                f"Duplicate manifest {kind}: {name}")

    if "android:exported" not in text and "<activity" in text:
        add("MEDIUM", "MANIFEST", path, 0,
            "Manifest contains activity declarations but no android:exported attribute was detected; inspect component declarations.")

def inspect_file(path):
    stats["files"] += 1
    text = read_text(path)

    if text is None:
        stats["errors"] += 1
        return

    stats["bytes"] += len(text.encode("utf-8", errors="ignore"))

    if not text.strip():
        add("LOW", "EMPTY_FILE", path, 0, "File is empty.")
        return

    ext = path.suffix.lower()

    if ext in {".kt", ".java"}:
        stats["source"] += 1
        scan_kotlin_java(path, text)
    elif ext == ".xml":
        stats["xml"] += 1
        scan_xml(path, text)
        if path.name == "AndroidManifest.xml":
            scan_manifest(path, text)
    elif ext in {".gradle", ".kts"}:
        stats["build"] += 1
        scan_gradle(path, text)

def discover():
    files = []
    for root, dirs, names in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in names:
            path = Path(root) / name
            if path.suffix.lower() in EXTENSIONS:
                files.append(path)
    return sorted(files)

def duplicate_class_check():
    for name, paths in sorted(class_map.items()):
        if len(paths) > 1:
            unique = sorted(set(rel(p) for p in paths))
            add(
                "HIGH",
                "DUPLICATE_DECLARATION",
                paths[0],
                0,
                f"Class/object/interface name '{name}' appears in {len(unique)} files: "
                + " | ".join(unique)
            )

def suspicious_duplicate_function_check():
    common = {
        "toString", "hashCode", "equals",
        "onCreate", "onStart", "onResume", "onPause",
        "onDestroy", "onBind", "onServiceConnected"
    }

    for name, paths in function_map.items():
        unique = sorted(set(rel(p) for p in paths))
        if name not in common and len(unique) > 4:
            add(
                "LOW",
                "FUNCTION_DISTRIBUTION",
                paths[0],
                0,
                f"Function '{name}' exists across {len(unique)} files; inspect ownership/call routing."
            )

def git_state():
    try:
        out = subprocess.run(
            ["git", "status", "--short", "--branch"],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=10
        )
        return out.stdout.strip()
    except Exception as e:
        return f"git status unavailable: {e}"

def severity_rank(x):
    return {"ERROR": 0, "HIGH": 1, "MEDIUM": 2, "LOW": 3, "INFO": 4}.get(x, 9)

def generate():
    findings.sort(key=lambda x: (severity_rank(x["severity"]), x["path"], x["line"]))

    counts = defaultdict(int)
    for f in findings:
        counts[f["severity"]] += 1

    now = datetime.now().isoformat(timespec="seconds")

    lines = []
    lines.append("SPLENDOR-ASSIST DEEP STATIC AUDIT")
    lines.append("=" * 40)
    lines.append(f"Time: {now}")
    lines.append(f"Root: {ROOT}")
    lines.append("")
    lines.append("AUDIT IS READ-ONLY")
    lines.append("")
    lines.append("FILES")
    lines.append(f"  Total inspected: {stats['files']}")
    lines.append(f"  Kotlin/Java:      {stats['source']}")
    lines.append(f"  XML:              {stats['xml']}")
    lines.append(f"  Gradle/KTS:       {stats['build']}")
    lines.append(f"  Read errors:      {stats['errors']}")
    lines.append("")
    lines.append("FINDINGS")
    lines.append(f"  HIGH:   {counts['HIGH']}")
    lines.append(f"  MEDIUM: {counts['MEDIUM']}")
    lines.append(f"  LOW:    {counts['LOW']}")
    lines.append(f"  INFO:   {counts['INFO']}")
    lines.append("")

    if findings:
        lines.append("IMPORTANT FINDINGS")
        lines.append("-" * 40)

        # Terminal/report cap: don't flood the output.
        for f in findings[:120]:
            lines.append(
                f"[{f['severity']}] {f['category']} | "
                f"{f['path']}:{f['line']} | {f['message']}"
            )

        if len(findings) > 120:
            lines.append("")
            lines.append(
                f"... {len(findings) - 120} additional findings are in the full report."
            )
    else:
        lines.append("NO STATIC FINDINGS DETECTED.")

    lines.append("")
    lines.append("GIT STATE")
    lines.append(git_state())
    lines.append("")
    lines.append("VERDICT")
    if counts["HIGH"]:
        lines.append("BLOCKED: HIGH-SEVERITY findings require targeted investigation.")
    elif counts["MEDIUM"]:
        lines.append("CAUTION: no HIGH findings; MEDIUM findings require review.")
    else:
        lines.append("STATIC AUDIT CLEAN: no HIGH/MEDIUM findings detected by this audit.")

    report = "\n".join(lines) + "\n"

    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    REPORT_TXT.write_text(report, encoding="utf-8")

    md = [
        "# Splendor-Assist Deep Static Audit",
        "",
        f"- Time: `{now}`",
        f"- Root: `{ROOT}`",
        "- Mode: **READ-ONLY**",
        "",
        "## Summary",
        f"- Files inspected: **{stats['files']}**",
        f"- Kotlin/Java: **{stats['source']}**",
        f"- XML: **{stats['xml']}**",
        f"- Gradle/KTS: **{stats['build']}**",
        f"- Read errors: **{stats['errors']}**",
        "",
        "## Findings",
        ""
    ]

    for f in findings:
        md.append(
            f"- **{f['severity']}** `{f['category']}` — "
            f"`{f['path']}:{f['line']}` — {f['message']}"
        )

    md.append("")
    md.append("## Git")
    md.append("")
    md.append("```text")
    md.append(git_state())
    md.append("```")

    REPORT_MD.write_text("\n".join(md) + "\n", encoding="utf-8")

    return report

def main():
    print("=== SPLENDOR-ASSIST DEEP AUDIT ===")
    print("READ-ONLY | FILE-BY-FILE | STREAMING FINDINGS")
    print(f"ROOT: {ROOT}")
    print("")

    files = discover()

    print(f"FILES DISCOVERED: {len(files)}")
    print("Scanning...")

    for index, path in enumerate(files, 1):
        inspect_file(path)

        # Direct progress, without printing source contents.
        if index == 1 or index % 25 == 0 or index == len(files):
            print(f"  scanned {index}/{len(files)}")

    duplicate_class_check()
    suspicious_duplicate_function_check()

    report = generate()

    print("")
    print(report)

    print("REPORTS:")
    print(f"  {REPORT_TXT}")
    print(f"  {REPORT_MD}")

    shared = Path.home() / "storage" / "shared"
    if shared.exists() and os.access(shared, os.W_OK):
        shared_txt = shared / "splendor-deep-audit.txt"
        shared_md = shared / "splendor-deep-audit.md"
        shared_txt.write_text(REPORT_TXT.read_text(encoding="utf-8"), encoding="utf-8")
        shared_md.write_text(REPORT_MD.read_text(encoding="utf-8"), encoding="utf-8")
        print("PHONE STORAGE EXPORT: OK")
        print(f"  {shared_txt}")
        print(f"  {shared_md}")
    else:
        print("PHONE STORAGE EXPORT: NOT AVAILABLE")
        print("Run 'termux-setup-storage' separately if storage access has not been granted.")

if __name__ == "__main__":
    main()
