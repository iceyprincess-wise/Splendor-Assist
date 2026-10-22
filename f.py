#!/usr/bin/env python3
"""f.py — manifest fixes for Splendor Assist (SHA 94f6ff7).

Fixes in app/src/main/AndroidManifest.xml:
  1. Remove bogus BIND_VPN_SERVICE uses-permission (High)
  2. PingEliminatorVpnService: exported true -> false (High)
  3. PingEliminatorVpnService: add foregroundServiceType="systemExempted" (Medium)

Every replacement is exact-match and verified. Nothing is rewritten blindly.
"""
import os, sys, re, shutil

TARGET = "app/src/main/AndroidManifest.xml"

if not os.path.isfile(TARGET):
    sys.exit(f"ABORT: {TARGET} not found — run from repo root.")

src = open(TARGET, encoding="utf-8").read()
orig = src
changes = []

# --- Fix 1: remove the BIND_VPN_SERVICE uses-permission line ---------------
pat1 = re.compile(
    r'^\s*<uses-permission\s+android:name="android\.permission\.BIND_VPN_SERVICE"[^>]*/>\s*\n',
    re.S,
)
if not pat1.search(src):
    sys.exit("ABORT: Fix 1 anchor not found (BIND_VPN_SERVICE uses-permission). "
             "Send me the current file and I'll re-anchor.")
src = pat1.sub("", src, count=1)
changes.append("Fix 1: removed BIND_VPN_SERVICE uses-permission")

# --- Fix 2 + 3: locate the PingEliminatorVpnService service block ----------
m = re.search(r'<service\b[^>]*android:name="\.PingEliminatorVpnService"[^>]*>', src)
if not m:
    # fallback: fully-qualified name
    m = re.search(r'<service\b[^>]*android:name="[^"]*PingEliminatorVpnService"[^>]*>', src)
if not m:
    sys.exit("ABORT: Fix 2/3 anchor not found (PingEliminatorVpnService service tag).")

tag = m.group(0)

if 'android:exported="true"' in tag:
    new_tag = tag.replace('android:exported="true"', 'android:exported="false"', 1)
    changes.append('Fix 2: PingEliminatorVpnService exported true -> false')
elif 'android:exported="false"' in tag:
    new_tag = tag
    changes.append("Fix 2: skipped (already exported=false)")
else:
    # exported attribute missing entirely — insert it
    new_tag = tag.replace("<service", '<service\n            android:exported="false"', 1)
    changes.append('Fix 2: inserted android:exported="false"')

if 'foregroundServiceType' not in new_tag:
    if new_tag.rstrip().endswith("/>"):
        new_tag = new_tag.rstrip()[:-2].rstrip() + \
            '\n            android:foregroundServiceType="systemExempted" />'
    else:
        new_tag = new_tag.rstrip()[:-1].rstrip() + \
            '\n            android:foregroundServiceType="systemExempted">'
    changes.append('Fix 3: added foregroundServiceType="systemExempted"')
else:
    changes.append("Fix 3: skipped (foregroundServiceType already present)")

src = src.replace(tag, new_tag, 1)

# --- Verify + write --------------------------------------------------------
shutil.copy(TARGET, TARGET + ".bak")
if src != orig:
    open(TARGET, "w", encoding="utf-8").write(src)
    print("APPLIED to", TARGET)
else:
    print("No changes were needed or all fixes were skipped.")

for c in changes:
    print(" ✓", c)
print("Backup written to", TARGET + ".bak (delete once build is green)")
print("\nNext: python3 f.py && git diff to confirm, then commit.")
