"""Emit a bounded derived source patch, never formatter output or runner paths."""
import base64
import hashlib
import os
from pathlib import Path
import re
import socket
import subprocess as sp
import sys


def main(p, commit, tree):
    assert __debug__ and os.getuid() != 0 and socket.if_nameindex() == [(1, 'lo')]
    assert 'NoNewPrivs:\t1\n' in Path('/proc/self/status').read_text()
    repo=p/'src'; rust=repo/'rust'; tool=p/'rust-prefix/bin'
    git=lambda *a: sp.check_output(['/usr/bin/git','--no-replace-objects','--no-optional-locks','-C',repo,*a],timeout=20)
    assert git('rev-parse','HEAD','HEAD^{tree}').decode().split()==[commit,tree] and not git('status','--porcelain=v1','--untracked-files=all')
    assert not git('remote')

    def run(label, args, cwd=repo):
        log=p/(label+'.log')
        with log.open('xb') as f:
            q=sp.run(args,cwd=cwd,stdout=f,stderr=sp.STDOUT,timeout=100)
        print(f'B368_FMT_PHASE={label}:{q.returncode}:{hashlib.sha256(log.read_bytes()).hexdigest()}',flush=True)
        return q.returncode

    components=sorted((p/'components').glob('*.tar.xz'))
    assert len(components)==5
    for archive in components:
        name=archive.name.removesuffix('.tar.xz'); kind=name.split('-1.100.0-',1)[0]
        assert kind in {'clippy','cargo','rustc','rustfmt','rust-std'}
        assert run('unpack-'+kind,['/usr/bin/tar','-xJf',archive,'-C',archive.parent])==0
        assert run('install-'+kind,['/bin/bash',archive.parent/name/'install.sh','--prefix='+str(tool.parent),'--disable-ldconfig'])==0
    assert b'commit-hash: 0ef1bc386' in sp.check_output([tool/'rustc','-vV'],timeout=15)
    assert sp.check_output([tool/'cargo','-V'],timeout=15).startswith(b'cargo 1.100.0 (3acc79c0c ')
    assert run('before',[tool/'cargo','fmt','--check'],rust)==1
    assert run('formatter',[tool/'cargo','fmt'],rust)==0
    assert run('after',[tool/'cargo','fmt','--check'],rust)==0
    paths=git('diff','--name-only').decode().splitlines()
    assert len(paths)==2 and all(re.fullmatch(r'rust/crates/dsha-core/src/[a-z_]+\.rs',f) for f in paths)
    assert git('diff','--name-status').decode().splitlines()==['M\t'+f for f in paths]
    assert not git('ls-files','--others','--exclude-standard')
    assert not git('diff','--check')
    for f in paths:
        before=git('cat-file','blob',commit+':'+f); after=(repo/f).read_bytes()
        assert re.sub(rb'\s+',b'',before)==re.sub(rb'\s+',b'',after), f
    patch=git('-c','core.quotePath=false','diff','--no-ext-diff','--no-textconv','--unified=0','--',*paths)
    assert 0 < len(patch)<8192 and b'\x00' not in patch
    pin=hashlib.sha256(patch).hexdigest()
    print('B368_FMT_PATCH_SHA256='+pin,flush=True)
    for i in range(0,len(patch),480):
        print('B368_FMT_PATCH_CHUNK='+str(i//480)+':'+base64.b64encode(patch[i:i+480]).decode('ascii'),flush=True)
    print('B368_FMT=PASS',flush=True)


if __name__=='__main__':
    p,c,t=sys.argv[1:]
    main(Path(p),c,t)
