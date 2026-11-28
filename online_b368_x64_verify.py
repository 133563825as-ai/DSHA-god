"""Verify a fresh x64 fixture and the unchanged registered permission test on the runner."""
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys

from online_b368_inputs import C, T, RUNTIME, LOCK, digest, expected, git


def main(p):
    assert __debug__ and os.getuid() != 0 and socket.if_nameindex() == [(1, 'lo')]
    assert 'NoNewPrivs:\t1\n' in Path('/proc/self/status').read_text()
    repo = p/'receiver'
    assert git(repo,'rev-parse','HEAD','HEAD^{tree}').decode().split() == [C,T]
    store = repo/'app/build/test-runtimes'
    assert not store.exists()
    lock = repo/LOCK
    node = p/'node-v24.19.0-linux-x64/bin/node'
    npm = node.parent.parent/'lib/node_modules/npm/bin/npm-cli.js'
    assert subprocess.check_output([node,npm,'--version'],timeout=10).strip()==b'11.17.0'
    seed = json.loads(subprocess.check_output([node,p/'seed.cjs',lock,p/'x64/sri',p/'x64-npm',npm],timeout=120))
    assert (seed['count'],seed['bytes'],len(seed['missing']),seed['lockSha256'])==(668,153584072,82,digest(lock))
    with (p/'x64-install.log').open('xb') as log:
        subprocess.run([sys.executable,'-sB','tools/prepare-test-runtime.py','--platform','host','--cache',p/'x64-npm','--offline'],
                       cwd=repo,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=360)
    sys.path.insert(0,str(repo/'tools'))
    from runtime_fixture_proof import validate
    pointer = store/'current.json'; ptr = json.loads(pointer.read_bytes()); pin = digest(pointer)
    assert ptr['platform']=={'os':'linux','cpu':'x64'} and ptr['archiveCurrent'] is True
    want = expected(repo,'x64')
    assert len(want)==682
    snapshots={}
    for kind in ('raw','managed'):
        r=Path(ptr[kind]); assert r.resolve()==r and r.is_relative_to(store)
        mark=r/'dsha-test-runtime.json'; proof=json.loads(mark.read_bytes())
        assert proof['kind']==kind and proof['archiveCurrent'] is True and proof['archiveSha256']==RUNTIME
        assert proof['platform']==ptr['platform'] and proof['lockSha256']==digest(lock)==digest(r/'package-lock.json')
        installed=json.loads((r/'node_modules/.package-lock.json').read_bytes())['packages']
        assert installed.keys()==want.keys()
        for n,v in installed.items():
            assert all(v[k]==want[n][k] for k in ('version','integrity','resolved'))
            assert v['version']==json.loads((r/n/'package.json').read_bytes())['version']
        assert validate(r,proof)==proof['contentProof']
        snapshots[kind]=(r,mark,digest(mark),proof['contentProof']['digest'])
    assert {x[0].parent for x in snapshots.values()}=={Path(ptr['raw']).parent}
    for kind, (_,_,_,content) in snapshots.items():
        print(f'B368_X64_FIXTURE={kind}:682:{content}',flush=True)
    paths=('tools/test-language-patch-chain.py', 'tools/test-bridge-routes.py', 'tools/test-web-ui-host-fixtures.mjs', 'tools/test-credential-yaml-projection.mjs', 'tools/test-welcome-notice-patch-upgrade.py', 'tools/test-session-interaction-patch-upgrade.py', 'tools/test-preset-pending-patch-upgrade.py', 'tools/test-plugin-management-patch-chain.py', 'tools/test-web-search-patch-chain.py')
    cases=json.loads((repo/'tools/host-tests.manifest.json').read_bytes())['tests']
    assert [x['path'] for x in cases if x['stage']=='runtime' and x['path'] in paths]==list(paths)
    jdk=p/'jdk-17.0.21+9'
    assert b'17.0.21+9' in subprocess.check_output([jdk/'bin/java','-version'],stderr=subprocess.STDOUT,timeout=12)
    backed=p/'snakeyaml-2.4.jar'; lockrow,=[x for x in json.loads((repo/'tools/backup-dependencies.lock.json').read_bytes())['dependencies'] if x['name']=='snakeyaml']; assert digest(backed)==lockrow['sha256']
    dep=repo/'app/build/backup-dependencies/snakeyaml-2.4.jar'; dep.parent.mkdir(); dep.write_bytes(backed.read_bytes()); assert digest(dep)==digest(backed)
    print('B368_RUNTIME_JDK_SUPPLY=17.0.21+9',flush=True)
    results={}
    for name in paths:
        label=Path(name).name
        output=p/('runtime-'+label)
        mem=int(next(row for row in Path('/proc/meminfo').read_text().splitlines() if row.startswith('MemAvailable:')).split()[1])//1024
        assert mem>=2048
        env=dict(os.environ,JAVA_HOME=str(jdk)); env.pop('GIT_ALLOW_PROTOCOL',None); env.pop('GIT_CONFIG_NOSYSTEM',None); env.pop('GIT_CONFIG_GLOBAL',None)
        with (p/(label+'.runner.log')).open('xb') as log:
            rc=subprocess.run([sys.executable,'-sB','tools/run-host-tests.py','--stage','runtime','--only',label,'--node',node,
                               '--jdk',jdk,'--raw',ptr['raw'],'--managed',ptr['managed'],'--output',output],cwd=repo,env=env,
                              stdout=log,stderr=subprocess.STDOUT,timeout=240).returncode
        manifest=output/'manifest.json'; m=json.loads(manifest.read_bytes()); row,=m['tests']; log=output/(label+'.log')
        assert m['requestedStage']=='runtime' and row['path']==name and row['logSha256']==digest(log)
        assert (rc==0)==(m['status']=='PASS_FOR_SELECTED_STAGES' and row['status']=='PASS' and row.get('exitCode')==0)
        assert row['status'] in {'PASS','FAILED','TIMEOUT'}
        results[name]=(rc,row['status'],digest(log),digest(manifest))
    assert digest(dep)==digest(backed)==lockrow['sha256']
    assert digest(pointer)==pin and not git(repo,'status','--porcelain=v1','--untracked-files=all') and not git(repo,'remote')
    assert git(repo,'rev-parse','HEAD','HEAD^{tree}').decode().split()==[C,T]
    for r,mark,mark_hash,content in snapshots.values():
        assert digest(mark)==mark_hash and validate(r,json.loads(mark.read_bytes()))['digest']==content
    handoff=json.loads((p/'handoff/handoff.json').read_bytes()); assert handoff['commit']==C and len(handoff['files'])==14
    for x in handoff['files']:
        f=repo/x['path']; assert f.resolve()==f and f.stat().st_size==x['bytes'] and digest(f)==x['sha256'] and bool(f.stat().st_mode&0o111)==x['executable']
    assert not any(f.suffix in {'.apk','.dex','.aab','.apks'} for f in (repo/'app/build').rglob('*'))
    for name,(rc,status,log,manifest) in results.items():
        print(f'B368_RUNTIME_CASE={name}:{status}:{rc}:{log}:{manifest}',flush=True)
    print('B368_RUNTIME_RESEALED=9',flush=True)
    return int(any(x[0] for x in results.values()))


if __name__=='__main__':
    raise SystemExit(main(Path(sys.argv[1])))
