#!/usr/bin/env python3
from pathlib import Path
import subprocess,os,tempfile
r=Path(__file__).resolve().parents[1];d=Path(tempfile.mkdtemp(prefix='aapt-proof-'));(d/'bin').mkdir();a=d/'bin/aapt2';a.write_text('#!/bin/sh\nexit 0\n');a.chmod(0o755);space=d/'binary with space';space.write_bytes(a.read_bytes());space.chmod(0o755)
base={k:v for k,v in os.environ.items() if k not in ['PREFIX','TERMUX_VERSION','SPLENDOR_AAPT2']};base['HOME']=str(d);base['APP_HOME']=str(r)
cmd=['sh','-c','. "$APP_HOME/tools/termux-aapt2.sh"; printf "%s\\000" "$@"','proof','build','argument with space']
for env,extra,expected in [(base,[],['build','argument with space']),({**base,'TERMUX_VERSION':'proof','PREFIX':str(d)},[],['-Pandroid.aapt2FromMavenOverride='+str(a),'build','argument with space']),({**base,'TERMUX_VERSION':'proof','SPLENDOR_AAPT2':str(space)},[],['-Pandroid.aapt2FromMavenOverride='+str(space),'build','argument with space']),({**base,'TERMUX_VERSION':'proof'},['-Pandroid.aapt2FromMavenOverride='+str(space)],['build','argument with space','-Pandroid.aapt2FromMavenOverride='+str(space)])]:
 p=subprocess.run(cmd+extra,env=env,capture_output=True);assert p.returncode==0,p.stderr;assert p.stdout.decode().rstrip('\0').split('\0')==expected
for name,value in [('missing',str(d/'missing')),('directory',str(d)),('nonexecutable',str(d/'not-executable'))]:
 (d/'not-executable').write_text('invalid');p=subprocess.run(cmd,env={**base,'TERMUX_VERSION':'proof','SPLENDOR_AAPT2':value},capture_output=True);assert p.returncode==2 and b'FAIL:' in p.stderr
print('PASS wrapper 7 cases: Linux, Termux fallback, spaces, explicit override, missing/directory/non-executable')
