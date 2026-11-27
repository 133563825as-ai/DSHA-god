"""Create the second, fresh joint-run receiver from the exact verified handoff."""
import json
from pathlib import Path
import subprocess as sp
import sys

from runpy import run_path


def main():
    assert __debug__ and len(sys.argv)==2
    p=Path(sys.argv[1]); assert p.is_absolute() and p.resolve()==p and p.is_dir()
    inputs=run_path(str(Path(__file__).with_name('online_b368_inputs.py')))
    c,t,h=(inputs[k] for k in ('C','T','TAR')); digest=inputs['digest']; git=inputs['git']
    src=p/'src'; base=p/'receiver'; recv=p/'lint-lane'/'receiver'; hd=p/'handoff'
    assert not recv.exists() and not (base/'app/build').exists()
    assert git(base,'rev-parse','HEAD','HEAD^{tree}').decode().split()==[c,t]
    assert digest(hd/'handoff.tar')==h
    git(p,'clone','--no-hardlinks','--no-checkout',src,recv)
    git(recv,'remote','remove','origin'); git(recv,'checkout','--detach',c)
    r=json.loads(sp.check_output([sys.executable,'-IB','tools/ci-handoff.py','unpack','--commit',c,'--dir',hd],cwd=recv,timeout=120))
    assert (r['status'],r['files'],r['tarSha256'])==('PASS_HANDOFF_UNPACK',14,h)
    m=json.loads((hd/'handoff.json').read_bytes()); assert m['commit']==c and len(m['files'])==14
    for x in m['files']:
        f=recv/x['path']; assert f.is_file() and f.resolve()==f and f.is_relative_to(recv)
        assert (f.stat().st_size,digest(f),bool(f.stat().st_mode&0o111))==(x['bytes'],x['sha256'],x['executable'])
    sp.check_call([sys.executable,'-IB','tools/verify-runtime-descriptor-source.py'],cwd=recv,timeout=90,stdout=sp.DEVNULL)
    assert git(recv,'rev-parse','HEAD','HEAD^{tree}').decode().split()==[c,t]
    assert not (recv/'app/build').exists() and not git(recv,'status','--porcelain=v1','--untracked-files=all') and not git(recv,'remote')


if __name__=='__main__':main()
