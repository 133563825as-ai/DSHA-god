"""Run the maintained full unit-test contract using only same-job inputs."""
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess as sp
import sys

p, gradle, commit, tree, *opts = sys.argv[1:]
p = Path(p)
repo = p/'receiver'
assert __debug__ and os.getuid() != 0 and socket.if_nameindex() == [(1, 'lo')]
assert 'NoNewPrivs:\t1\n' in Path('/proc/self/status').read_text()
assert p.resolve() == p and not (repo/'app/build').exists()

def digest(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def git(*args):
    return sp.check_output(['git','--no-replace-objects','--no-optional-locks',*args],cwd=repo,timeout=25)


assert git('rev-parse','HEAD','HEAD^{tree}').decode().split() == [commit,tree]
assert not git('status','--porcelain=v1','--untracked-files=all') and not git('remote')

def phase(name, args):
    log=p/('full-'+name+'.log')
    with log.open('xb') as output:
        run=sp.run(args,cwd=repo,stdout=output,stderr=sp.STDOUT,timeout=400)
    print(f'B368_FULL_JVM_PHASE={name}:{run.returncode}:{digest(log)}',flush=True)
    if run.returncode:
        b=log.read_bytes()
        codes=sorted(set(re.findall(rb'UNIT_[A-Z0-9_]{3,65}',b)))
        for code in codes[:8]:
            print('B368_FULL_JVM_CONTRACT_CODE='+code.decode('ascii'),flush=True)
        raise SystemExit(run.returncode)


py=[sys.executable,'-sB']
phase('robo-check',py+['tools/prepare-robolectric-sdks.py','--check','--dir',p/'robo'])
for name in ('rustc','cargo','rust-std'):
    prefix=f'{name}-1.100.0-x86_64-unknown-linux-gnu'
    phase('unpack-'+name,['tar','-xJf',p/'components'/(prefix+'.tar.xz'),'-C',p/'components'])
    phase('install-'+name,['bash',p/'components'/prefix/'install.sh','--prefix='+str(p/'rust-prefix'),'--disable-ldconfig'])
lib=p/'native/libdsha_rs.so'
receipt=p/'host-rust.json'
phase('rust-build',py+['tools/rust/build.py','--host','--expect-lock','--commit',commit,'--jobs','1','--out',lib.parent,'--receipt',receipt])
locked=json.loads(receipt.read_bytes()); pin=digest(lib)
assert (locked['commit'],locked['runId'],locked['runAttempt'])==(commit,os.environ['GITHUB_RUN_ID'],os.environ['GITHUB_RUN_ATTEMPT'])
assert locked['expectation']==dict(status='PASS',target='x86_64-unknown-linux-gnu',size=369128,sha256=pin)
assert lib.stat().st_size==369128 and pin=='10c11bae9ab38b0b8ac4655c0d1c4f6e87cfdc78b1fd7349ebef25718b2adfeb'
phase('rust-abi',py+['tools/check-rust-boundaries.py','--artifact',lib])
plan=p/'unit-plan.json'; state=p/'unit-state'; aggregate=p/'jvm-aggregate.json'
contract=py+['tools/unit_test_contract.py']
origin=['--root',repo,'--state',state,'--run-id',os.environ['GITHUB_RUN_ID'],'--run-attempt',os.environ['GITHUB_RUN_ATTEMPT']]
phase('plan',contract+['plan','--root',repo,'--output',plan])
assert digest(plan)=='b8e143f4eff1df62387b5c5720d7c119386572f14a9fa83366b326a18fc65157'
phase('begin',contract+['begin',*origin,'--plan',plan,'--rust-library',lib,'--rust-receipt',receipt])
os.environ['DSHA_UNIT_TEST_STATE']=str(state)
os.environ['DSHA_ROBOLECTRIC_DEPENDENCY_DIR']=str(p/'robo')
assert not any(x in opts for x in ('-x','--tests','--dry-run','--continue'))
cmd=py+[p/'online_b368_compile_monitor.py',p,gradle,*opts,'--offline','--rerun-tasks','--project-cache-dir',p/'full-cache',
        '-Pdsha.sourceOnlyTests=false','-Pdsha.rust.required=true','-Pdsha.rust.hostLib='+str(lib),
        'verifyDshaBuildConventions',':app:testStandardDebugUnitTest']
rc=sp.run(cmd,cwd=repo).returncode
print(f'B368_FULL_JVM_GRADLE_EXIT={rc}',flush=True)
if rc != 0:
    raise SystemExit(rc)
phase('verify',contract+['verify',*origin,'--baseline','ci/unit-test-baseline.json','--output',aggregate])
a=json.loads(aggregate.read_bytes())
assert a['kind']=='dsha-unit-aggregate' and a['mode']=='full' and a['commit']==commit and a['freshness']=='live-task-evidence'
assert a['tests']>0 and a['failures']==a['errors']==a['skipped']==0 and a['rustRequired'] is True
assert len(a['tasks'])==1 and a['tasks'][0]['task']==':app:testStandardDebugUnitTest'
assert not any(x.suffix in {'.apk','.aab','.apks','.dex'} for x in (repo/'app/build').rglob('*'))
assert not git('status','--porcelain=v1','--untracked-files=all')
assert digest(lib)==pin
print(f'B368_FULL_JVM_RESULT={a["tests"]}:{a["requiredFixtureTestsPassed"]}:{len(a["tasks"][0]["reports"])}:{digest(aggregate)}',flush=True)
print('B368_FULL_JVM=PASS',flush=True)
