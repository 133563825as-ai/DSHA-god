"""Original JVM plan and Robolectric material validation; no application JVM execution."""
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import socket
import subprocess as sp
import sys

p, commit, tree = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
repo = p/'src'
assert __debug__ and os.getuid() != 0 and socket.if_nameindex() == [(1, 'lo')]
assert 'NoNewPrivs:\t1\n' in Path('/proc/self/status').read_text()
git = lambda *args: sp.check_output(['git','--no-optional-locks','--no-replace-objects',*args],cwd=repo,timeout=20)
assert git('rev-parse','HEAD','HEAD^{tree}').decode().split() == [commit,tree]
assert not git('status','--porcelain=v1','--untracked-files=all') and not git('remote')


def checked(label, script, *args):
    log=p/(label+'.log')
    with log.open('xb') as out:
        run=sp.run([sys.executable,'-sB',script,*args],cwd=repo,stdout=out,stderr=sp.STDOUT,timeout=180)
    raw=log.read_bytes()
    print(f'B368_JVM_PHASE={label}:{run.returncode}:{hashlib.sha256(raw).hexdigest()}',flush=True)
    run.check_returncode()
    return raw


for label, script in (('robo-helper','tools/test-robolectric-sdks.py'),('unit-contract','tools/test-unit-test-contract.py')):
    raw=checked(label,script)
    n,=re.findall(rb'Ran ([1-9][0-9]*) tests? in ',raw)
    assert re.search(rb'(?m)^OK\s*$',raw)
    print(f'B368_JVM_CONTRACT_CHECKS={label}:{int(n)}',flush=True)
output=p/'unit-plan.json'
checked('unit-plan','tools/unit_test_contract.py','plan','--root',repo,'--output',output)
plan=json.loads(output.read_bytes()); assert plan['kind']=='dsha-unit-plan' and plan['mode']=='full'
assert plan['tasks']==[dict(module=':app',task=':app:testStandardDebugUnitTest',reports='app/build/test-results/testStandardDebugUnitTest',sourceSets=['test'])]
methods=sum(len(row['tests']) for row in plan['sources'])
assert methods>0
print('B368_JVM_PLAN_COUNTS='+':'.join(map(str,[len(plan['tasks']),len(plan['sources']),methods,len(plan['inputSources']),len(plan['resourceInputs'])])),flush=True)
print('B368_JVM_PLAN_SHA256='+hashlib.sha256(output.read_bytes()).hexdigest(),flush=True)
checked('robo-readback','tools/prepare-robolectric-sdks.py','--check','--dir',p/'robo')
helper=runpy.run_path(str(repo/'tools/prepare-robolectric-sdks.py')); lock=helper['load_lock']()
files=sorted(x.name for x in (p/'robo').iterdir())
assert files==sorted(helper['file_name'](lock,j) for j in lock['jars']) and len(files)==5
print(f'B368_JVM_SDKS=5:{sum(x["size"] for x in lock["jars"])}',flush=True)
assert not (repo/'app/build').exists()
assert not git('status','--porcelain=v1','--untracked-files=all')
print('B368_JVM_PREFLIGHT=PASS',flush=True)
