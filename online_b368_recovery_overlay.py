"""Generate the real recovery assets and run the unchanged registered host test."""
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess as sp
import sys

from online_b368_inputs import C, T, digest, git


def checked(p):
    assert os.getuid() != 0 and socket.if_nameindex() == [(1, 'lo')]
    assert 'NoNewPrivs:\t1\n' in Path('/proc/self/status').read_text()
    repo = p/'receiver'
    build = repo/'app/build'
    source = repo/'app/src/main/assets'
    standard = build/'generated/standardAssets'
    recovery = build/'generated/recoveryAssets'
    assert not build.exists()
    def identity():
        assert git(repo,'rev-parse','HEAD','HEAD^{tree}').decode().split() == [C,T]
        assert not git(repo,'status','--porcelain=v1','--untracked-files=all') and not git(repo,'remote')
    identity()
    def generate(name, args):
        log = p/(name+'.log')
        with log.open('xb') as out:
            rc = sp.run([sys.executable,'-sB','tools/'+name+'.py',*args],cwd=repo,
                        stdout=out,stderr=sp.STDOUT,timeout=450).returncode
        print(f'B368_RECOVERY_GENERATE={name}:{rc}:{digest(log)}',flush=True)
        if rc:
            raise SystemExit(rc)
    generate('prepare-standard-assets',['--source',source,'--output',standard])
    generate('prepare-recovery-assets',['--output',recovery,'--shared-assets',standard])
    descriptor=recovery/'recovery-runtime.json'
    locations=recovery/'recovery-asset-locations.json'
    doc=json.loads(descriptor.read_bytes()); loc=json.loads(locations.read_bytes())
    contract={k:v for k,v in doc.items() if k!='id'}
    assert doc['id']==loc['runtimeId']==hashlib.sha256(json.dumps(contract,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    assert len(doc['overlays'])==4 and len(doc['archives'])==len(loc['archives'])==2
    assert {x['asset']:x['sha256'] for x in doc['archives']} == {x['asset']:x['sha256'] for x in loc['archives']}
    def seal():
        pins={descriptor:digest(descriptor),locations:digest(locations)}
        for row in doc['overlays']:
            f=recovery/row['asset']; assert f.resolve()==f and f.is_relative_to(recovery) and f.stat().st_size==row['bytes']
            pins[f]=digest(f); assert pins[f]==row['sha256']
        for row in loc['archives']:
            f=(recovery if row['asset']==row['source'] else standard)/row['source']
            assert f.resolve()==f and f.is_relative_to(build)
            pins[f]=digest(f); assert pins[f]==row['sha256']
        assert len(pins)==8
        return pins
    pins=seal()
    output=p/'recovery-test'; test='test-recovery-browser-overlay.mjs'
    node=p/'node-v24.19.0-linux-x64/bin/node'
    with (p/'recovery-runner.log').open('xb') as log:
        rc=sp.run([sys.executable,'-sB','tools/run-host-tests.py','--stage','runtime','--only',test,
                   '--node',node,'--output',output],cwd=repo,stdout=log,stderr=sp.STDOUT,timeout=180).returncode
    manifest=output/'manifest.json'; m=json.loads(manifest.read_bytes()); row,=m['tests']; log=output/(test+'.log')
    assert m['requestedStage']=='runtime' and row['path']=='tools/'+test and row['logSha256']==digest(log)
    assert type(row.get('exitCode')) is int and row['status'] in {'PASS','FAILED'}
    assert (rc==0)==(row['status']=='PASS' and row['exitCode']==0 and m['status']=='PASS_FOR_SELECTED_STAGES' and not m['failures'])
    assert pins==seal()
    identity()
    assert not any(f.suffix in {'.apk','.dex','.aab','.apks'} for f in build.rglob('*'))
    handoff=json.loads((p/'handoff/handoff.json').read_bytes()); assert handoff['commit']==C and len(handoff['files'])==14
    for item in handoff['files']:
        f=repo/item['path']; assert f.resolve()==f and f.stat().st_size==item['bytes'] and digest(f)==item['sha256'] and bool(f.stat().st_mode&0o111)==item['executable']
    print(f'B368_RECOVERY_READBACK={doc["id"]}:4:{digest(descriptor)}:{digest(locations)}',flush=True)
    print(f'B368_RECOVERY_CASE={row["status"]}:{row["exitCode"]}:{digest(log)}:{digest(manifest)}',flush=True)
    return rc


if __name__=='__main__':
    raise SystemExit(checked(Path(sys.argv[1])))
