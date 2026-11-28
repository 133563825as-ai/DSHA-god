"""Host-only Rust evidence. All original build and differential output stays in the runner."""
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess as sp
import sys


def main(p, commit, tree):
    assert __debug__ and os.getuid() != 0 and socket.if_nameindex() == [(1, 'lo')]
    assert 'NoNewPrivs:\t1\n' in Path('/proc/self/status').read_text()
    repo = p/'src'
    rust = repo/'rust'
    git = lambda *a: sp.check_output(['git','--no-optional-locks','--no-replace-objects','-C',repo,*a],timeout=15)
    def identity():
        assert git('rev-parse','HEAD','HEAD^{tree}').decode().split() == [commit,tree]
        assert not git('status','--porcelain=v1','--untracked-files=all') and not git('remote')
    identity()
    tool=p/'rust-prefix/bin'
    jdk=p/'jdk-17.0.21+9'
    def phase(label, command, cwd=repo, timeout=320):
        free=int(next(x for x in Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemAvailable:')).split()[1])//1024
        assert free>=2048
        log=p/(label+'.log')
        with log.open('xb') as f:
            result=sp.run(command,cwd=cwd,stdout=f,stderr=sp.STDOUT,timeout=timeout)
        b=log.read_bytes()
        print(f'B368_RUST_PHASE={label}:{result.returncode}:{hashlib.sha256(b).hexdigest()}',flush=True)
        result.check_returncode()
        return b
    phase('unpack-jdk',['/usr/bin/tar','-xzf',p/'jdk.tgz','-C',p])
    components=sorted((p/'components').glob('*.tar.xz'))
    assert len(components)==5
    for archive in components:
        name=archive.name.removesuffix('.tar.xz')
        kind=name.split('-1.100.0-',1)[0]
        assert kind in {'clippy','cargo','rustc','rustfmt','rust-std'}
        phase('unpack-'+kind,['/usr/bin/tar','-xJf',archive,'-C',archive.parent])
        phase('install-'+kind,['/bin/bash',archive.parent/name/'install.sh','--prefix='+str(tool.parent),'--disable-ldconfig'])
    print('B368_RUST_COMPONENTS=5',flush=True)
    assert b'17.0.21+9' in sp.check_output([jdk/'bin/java','-version'],stderr=sp.STDOUT,timeout=15)
    version=sp.check_output([tool/'rustc','-vV'],timeout=15)
    assert b'commit-hash: 0ef1bc386' in version and b'host: x86_64-unknown-linux-gnu' in version
    assert sp.check_output([tool/'cargo','-V'],timeout=15).startswith(b'cargo 1.100.0 (3acc79c0c ')
    print('B368_RUST_TOOLCHAIN=1.100.0:X64',flush=True)
    for name in ('check-rust-toolchain','check-rust-vendor','check-rust-boundaries','check-rust-native-apk'):
        phase(name,[sys.executable,'-sB','tools/'+name+'.py'])
    phase('format',[tool/'cargo','fmt','--check'],rust)
    phase('clippy',[tool/'cargo','clippy','--locked','--offline','-j','1','--all-targets','--',
                     '-D','warnings','-D','clippy::unwrap_used','-D','clippy::expect_used'],rust)
    tested=phase('unit',[tool/'cargo','test','--locked','--offline','-j','1','--','--test-threads=1'],rust)
    counts=re.findall(rb'(?m)^test result: ok\. ([0-9]+) passed; 0 failed; 0 ignored;',tested)
    assert counts and sum(map(int,counts))>0
    print(f'B368_RUST_UNIT={sum(map(int,counts))}',flush=True)
    phase('host-build',[sys.executable,'-sB','tools/rust/build.py','--host','--expect-lock',
                        '--commit',commit,'--jobs','1','--out',p/'native','--receipt',p/'build.json'])
    lib=p/'native/libdsha_rs.so'; r=json.loads((p/'build.json').read_bytes())
    pin=hashlib.sha256(lib.read_bytes()).hexdigest()
    assert r['commit']==commit and r['target']=='host' and r['toolchain']['targetTriple']=='x86_64-unknown-linux-gnu'
    assert r['build']['locked'] is True and r['build']['offline'] is True and r['artifact']['sha256']==pin
    assert r['expectation']['status'] in {'PASS','PENDING'}
    phase('host-abi',[sys.executable,'-sB','tools/check-rust-boundaries.py','--artifact',lib])
    phase('host-jvm',[sys.executable,'-sB','tools/rust/host-jvm-differential.py','--lib',lib,'--report',p/'diff.json'])
    d=json.loads((p/'diff.json').read_bytes()); assert d['status']=='PASS' and d['library']['sha256']==pin
    tests=d['tests']; assert tests['failures']==tests['skipped']==0 and tests['probeChecks']==len(d['checks'])>0
    assert all(x['status']=='PASS' for x in d['checks']) and hashlib.sha256(lib.read_bytes()).hexdigest()==pin
    identity()
    assert not (repo/'app/build').exists()
    print(f'B368_RUST_LIBRARY={lib.stat().st_size}:{pin}:{r["expectation"]["status"]}',flush=True)
    print(f'B368_RUST_JVM={tests["probeChecks"]}:{d["fixtures"]["planCorpusCases"]}:{d["fixtures"]["archiveCorpusChecks"]}',flush=True)


if __name__=='__main__':
    p,c,t=sys.argv[1:]
    main(Path(p),c,t)
