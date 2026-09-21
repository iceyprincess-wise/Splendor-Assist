#!/usr/bin/env python3
import re
import subprocess
from pathlib import Path

ROOT = Path.cwd().resolve()
SOURCE_DIRS = [
    ROOT / "app/src/main/java",
    ROOT / "core/src/main/java",
]

CONTRIBUTORS = [
    "BallRetentionShieldContributor",
    "BuildUpPressContributor",
    "CrossClaimContributor",
    "InstantInterceptContributor",
    "KeeperBiasContributor",
    "PanicSaveContributor",
    "ThreatPriorityContributor",
    "TrueShotContributor",
    "DefenseAuthorityContributor",
    "ForwardRunContributor",
    "ReceiverEngagementContributor",
    "TrueCrossContributor",
]

ENGINES = [
    "BallRetentionShieldEngine",
    "BuildUpPressEngine",
    "CrossClaimEngine",
    "CollisionAvoidanceEngine",
    "InstantInterceptEngine",
    "KeeperPositionBiasEngine",
    "OneVsOnePanicEngine",
    "OwnGoalAvoidanceEngine",
    "ThreatPriorityEngine",
    "TrueShotEngine",
    "DefenseAuthorityEngine",
    "ForwardRunOpportunityEngine",
    "ReceiverEngagementEngine",
    "TrueCrossEngine",
]

DECL_RE_TEMPLATE = (
    r"^\s*"
    r"(?:@[\w.]+(?:\([^)]*\))?\s+)*"
    r"(?:(?:private|public|internal|protected|final|open|abstract|sealed|data|enum|annotation)\s+)*"
    r"(?:class|object|interface)\s+"
    r"{name}"
    r"\b"
)

def iter_kotlin_files():
    for d in SOURCE_DIRS:
        if not d.is_dir():
            continue
        for p in d.rglob("*.kt"):
            if p.is_file():
                yield p

def read_lines(p: Path):
    return p.read_text(encoding="utf-8", errors="ignore").splitlines()

def find_anchor_end(lines, start_idx, name):
    for i in range(start_idx + 1, min(len(lines), start_idx + 12000)):
        if lines[i].strip() == f"{name} Anchor":
            for k in range(i - 1, max(start_idx, i - 20), -1):
                if lines[k].strip() == "/* ======":
                    return k
            return i - 1
    return min(len(lines), start_idx + 1200)

def extract_symbol(name: str):
    rx = re.compile(DECL_RE_TEMPLATE.format(name=re.escape(name)))
    found = []

    for p in iter_kotlin_files():
        lines = read_lines(p)
        for i, line in enumerate(lines):
            if rx.match(line):
                end = find_anchor_end(lines, i, name)
                body = lines[i:end]
                found.append((p, i + 1, end, body))

    return found

def print_symbol(kind: str, name: str):
    print()
    print("=" * 100)
    print(f"{kind}: {name}")
    print("=" * 100)

    hits = extract_symbol(name)
    if not hits:
        print("STATUS: DECLARATION_NOT_FOUND")
        return

    if len(hits) > 1:
        print(f"STATUS: MULTIPLE_DECLARATIONS count={len(hits)}")

    for p, start, end, body in hits:
        print("FILE:", p.relative_to(ROOT))
        print("START_LINE:", start)
        print("END_LINE:", end)
        print("BODY_LENGTH:", len(body))
        print()
        print("----- EXACT BODY -----")
        for off, line in enumerate(body[:3000]):
            print(f"{start + off:06d}: {line.rstrip()}")
        if len(body) > 3000:
            print("... body truncated at 3000 lines")

        print()
        print("----- CALLERS -----")
        subprocess.run(
            [
                "grep",
                "-RIn",
                name,
                "app/src/main/java",
                "core/src/main/java",
            ],
            check=False,
        )

print("================ V35B TOP CONTRIBUTOR BODIES ================")
print("ROOT:", ROOT)

print()
print("=== GIT HEAD ===")
subprocess.run(["git", "rev-parse", "HEAD"], check=False)

print()
print("=== GIT STATUS ===")
subprocess.run(["git", "status", "--short"], check=False)

print()
print("##################### CONTRIBUTORS #####################")
for c in CONTRIBUTORS:
    print_symbol("CONTRIBUTOR", c)

print()
print("##################### ENGINES #####################")
for e in ENGINES:
    print_symbol("ENGINE", e)

print()
print("##################### RELATED RESULT/DATA CLASSES #####################")
result_rx = re.compile(
    r"^\s*"
    r"(?:@[\w.]+(?:\([^)]*\))?\s+)*"
    r"(?:data\s+class|class|object|interface)\s+"
    r"([A-Za-z_][A-Za-z0-9_]*)"
    r"\b"
)

keywords = [
    "BallRetentionShield",
    "BuildUpPress",
    "CrossClaim",
    "CollisionAvoidance",
    "InstantIntercept",
    "KeeperPositionBias",
    "OneVsOnePanic",
    "OwnGoalAvoidance",
    "ThreatPriority",
    "TrueShot",
    "DefenseAuthority",
    "ForwardRunOpportunity",
    "ReceiverEngagement",
    "TrueCross",
]

seen = set()
for p in iter_kotlin_files():
    lines = read_lines(p)
    for i, line in enumerate(lines):
        m = result_rx.match(line)
        if not m:
            continue
        sym = m.group(1)
        if sym in seen:
            continue
        if any(k in sym for k in keywords):
            seen.add(sym)
            print_symbol("RESULT_OR_CLASS", sym)
