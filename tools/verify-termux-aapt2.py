#!/usr/bin/env python3
"""Proof for tools/termux-aapt2.sh: the gradlew aapt2 wrapper.

10 cases. Run: python3 tools/verify-termux-aapt2.py
Cases 1-4: Linux passthrough, Termux fallback (PREFIX), spaces in path,
explicit override kept verbatim. Cases 5-7: missing / directory /
non-executable SPLENDOR_AAPT2 -> exit 2 + FAIL:. Cases 8-10 (new):
PREFIX-gate fires without TERMUX_VERSION, $HOME/bin/aapt2 decides,
gradle.properties config decides. The temp dir is auto-removed.
"""
from pathlib import Path
import subprocess, os, tempfile, shutil

r = Path(__file__).resolve().parents[1]

with tempfile.TemporaryDirectory(prefix='aapt-proof-') as td:
    d = Path(td)
    (d / 'bin').mkdir()
    a = d / 'bin' / 'aapt2'
    a.write_text('#!/bin/sh\nexit 0\n')
    a.chmod(0o755)
    space = d / 'binary with space'
    space.write_bytes(a.read_bytes())
    space.chmod(0o755)
    # Candidates used by the new branch cases.
    (d / 'usr' / 'bin').mkdir(parents=True)
    (d / 'home' / 'bin').mkdir(parents=True)
    home_fake = d / 'home' / 'bin' / 'aapt2'
    home_fake.write_bytes(a.read_bytes())
    home_fake.chmod(0o755)
    empty = d / 'empty'
    empty.mkdir()
    # Sandboxed repo root for the config-candidate case: its own
    # tools/termux-aapt2.sh copy plus a gradle.properties that points
    # at the fake binary. The real repo config points at a Termux path
    # that does not exist on a Linux host, so it can never decide here.
    repo2 = d / 'repo2'
    (repo2 / 'tools').mkdir(parents=True)
    shutil.copy2(r / 'tools' / 'termux-aapt2.sh', repo2 / 'tools' / 'termux-aapt2.sh')
    (repo2 / 'gradle.properties').write_text(
        'splendor.termuxAapt2=' + str(space) + '\n')

    base = {k: v for k, v in os.environ.items()
            if k not in ['PREFIX', 'TERMUX_VERSION', 'SPLENDOR_AAPT2']}
    base['HOME'] = str(d)
    base['APP_HOME'] = str(r)
    cmd = ['sh', '-c', '. "$APP_HOME/tools/termux-aapt2.sh"; printf "%s\\000" "$@"',
           'proof', 'build', 'argument with space']
    override_space = '-Pandroid.aapt2FromMavenOverride=' + str(space)
    override_a = '-Pandroid.aapt2FromMavenOverride=' + str(a)

    def run(env, extra=None, app_home=None):
        e = dict(env)
        if app_home is not None:
            e['APP_HOME'] = app_home
        p = subprocess.run(cmd + (extra or []), env=e, capture_output=True)
        return p

    def expect_args(p, expected):
        assert p.returncode == 0, p.stderr
        assert p.stdout.decode().rstrip('\0').split('\0') == expected, p.stdout

    # 1. Linux: wrapper does nothing, args pass through verbatim.
    expect_args(run(base), ['build', 'argument with space'])
    # 2. Termux fallback: PREFIX/bin/aapt2 decides (TERMUX_VERSION set).
    expect_args(run({**base, 'TERMUX_VERSION': 'proof', 'PREFIX': str(d)}),
                [override_a, 'build', 'argument with space'])
    # 3. SPLENDOR_AAPT2 with a space in the path is passed through whole.
    expect_args(run({**base, 'TERMUX_VERSION': 'proof',
                     'SPLENDOR_AAPT2': str(space)}),
                [override_space, 'build', 'argument with space'])
    # 4. An explicit override is kept verbatim and never duplicated.
    expect_args(run({**base, 'TERMUX_VERSION': 'proof'}, [override_space]),
                ['build', 'argument with space', override_space])
    # 5-7. Unusable SPLENDOR_AAPT2: missing, directory, non-executable.
    for name, value in [('missing', str(d / 'missing')),
                        ('directory', str(d)),
                        ('nonexecutable', str(d / 'not-executable'))]:
        (d / 'not-executable').write_text('invalid')
        p = run({**base, 'TERMUX_VERSION': 'proof', 'SPLENDOR_AAPT2': value})
        assert p.returncode == 2 and b'FAIL:' in p.stderr, (name, p.returncode, p.stderr)
    # 8. PREFIX-gate fires without TERMUX_VERSION: the Termux PREFIX alone
    # triggers the wrapper, and with no usable aapt2 anywhere it FAILs.
    p = run({**base, 'PREFIX': '/data/data/com.termux/files/usr',
             'HOME': str(empty)})
    assert p.returncode == 2 and b'FAIL:' in p.stderr, p.stderr
    # 9. $HOME/bin/aapt2 decides when PREFIX has no aapt2.
    expect_args(run({**base, 'TERMUX_VERSION': 'proof',
                     'PREFIX': str(empty), 'HOME': str(d / 'home')}),
                ['-Pandroid.aapt2FromMavenOverride=' + str(home_fake),
                 'build', 'argument with space'])
    # 10. gradle.properties config decides when PREFIX/HOME have no aapt2.
    expect_args(run({**base, 'TERMUX_VERSION': 'proof',
                     'PREFIX': str(empty), 'HOME': str(empty)},
                app_home=str(repo2)),
                [override_space, 'build', 'argument with space'])

print('PASS wrapper 10 cases: Linux, Termux fallback, spaces, explicit override, '
      'missing/directory/non-executable, PREFIX-gate, HOME candidate, config candidate')
