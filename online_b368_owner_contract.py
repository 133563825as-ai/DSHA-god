"""Linux source checks only; deliberately does not run Gradle or acquire input JARs."""
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess as sp
import sys

p,c,t=Path(sys.argv[1]),sys.argv[2],sys.argv[3]
repo=p/'src'
assert __debug__ and os.getuid()!=0 and socket.if_nameindex()==[(1,'lo')]
assert 'NoNewPrivs:\t1\n' in Path('/proc/self/status').read_text()
git=lambda *a:sp.check_output(['/usr/bin/git','--no-optional-locks','--no-replace-objects',*a],cwd=repo,timeout=20)
assert git('rev-parse','HEAD','HEAD^{tree}').decode().split()==[c,t]
assert not git('status','--porcelain=v1','--untracked-files=all') and not git('remote')
checks={'identity':('tools/test-release-identity.py',10), 'unit-contract':('tools/test-unit-test-contract.py',68),
        'robo-supplier':('tools/test-robolectric-sdks.py',10)}
for label,(path,count) in checks.items():
    f=p/(label+'.log')
    with f.open('xb') as out:
        r=sp.run([sys.executable,'-sB',path],cwd=repo,stdout=out,stderr=sp.STDOUT,timeout=180)
    b=f.read_bytes()
    print(f'B368_OWNER_CHECK={label}:{r.returncode}:{hashlib.sha256(b).hexdigest()}',flush=True)
    r.check_returncode()
    assert re.search(rb'(?m)^Ran '+str(count).encode()+rb' tests? in ',b) and re.search(rb'(?m)^OK\s*$',b)
    print(f'B368_OWNER_SELFTESTS={label}:{count}',flush=True)
plan=p/'plan.json'
q=sp.run([sys.executable,'-sB','tools/unit_test_contract.py','plan','--root',repo,'--output',plan],cwd=repo,
         stdout=sp.PIPE,stderr=sp.STDOUT,check=True,timeout=90)
a=json.loads(plan.read_bytes()); assert a['mode']=='full' and a['kind']=='dsha-unit-plan'
assert [x['task'] for x in a['tasks']]==[':app:testStandardDebugUnitTest']
assert len(a['sources'])==823 and sum(len(x['tests']) for x in a['sources'])==5507
pin=hashlib.sha256(json.dumps(a,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
assert pin=='5c07d653f31f28e1ab135092e1521a96506211b0a17a65de26a4196da5f432f0'
print('B368_OWNER_PLAN_LF_SHA256='+hashlib.sha256(plan.read_bytes()).hexdigest(),flush=True)
print('B368_OWNER_PLAN_CANONICAL_SHA256='+pin,flush=True)
assert not (repo/'app/build').exists() and not git('status','--porcelain=v1','--untracked-files=all')
print('B368_OWNER_CONTRACT=PASS',flush=True)
