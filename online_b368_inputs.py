"""b368 online input preparation/verification. Only the acquisition subcommand uses the network."""
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess as sp
import sys
import tarfile
import urllib.error
import urllib.request

C='38fb7b125cc0a5ae6e324db99066d3d139ce86ad'
T='c876482bdf7b332ed295a49b8e5f22ee6b1c646c'
RUNTIME='c1bd68330a10776f304a92a19d8f82c184c1abf523275a9e28dbb2c2e764e4fe'
TAR='f6d3a99c15eb75b5e1f6335aea0a4611203ee3409884fc32d77b9933f5d2262f'
LOCK='tools/dsh-runtime/package-lock.json'
SKIP={'node_modules/@img/sharp-wasm32','node_modules/@emnapi/runtime'}


def digest(p,algo='sha256'):
    with p.open('rb') as f:return hashlib.file_digest(f,algo).hexdigest()


def git(root,*args):return sp.check_output(['git','--no-replace-objects','-c','core.hooksPath=/dev/null','-C',root,*args],timeout=30)


def platform_ok(r,key,want):
    a=r.get(key,[])
    return ('!'+want) not in a and (not any(not x.startswith('!') for x in a) or want in a)


def expected(root,cpu='arm64'):
    packages=json.loads((root/LOCK).read_bytes())['packages']
    for n in SKIP:assert packages[n]['optional'] is True
    installed={n:r for n,r in packages.items() if n and n not in SKIP and all(platform_ok(r,k,v) for k,v in [('os','linux'),('cpu',cpu),('libc','glibc')])}
    assert len(installed)==682 and len({r['integrity'] for r in installed.values()})==668
    return installed


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None


def acquire(p,cpu='arm64',src=None):
    want=expected(src or p/'src',cpu); qualified=re.compile(r'https://registry\.npmjs\.org/(?:@[\w.+-]+/)?[\w.+-]+/-/[\w.+-]+\.tgz',re.ASCII)
    registry={}
    for r in want.values():
        url=r['resolved']; pin=r['integrity']; assert qualified.fullmatch(url) and re.fullmatch(r'sha512-[A-Za-z0-9+/]{86}==',pin)
        assert registry.setdefault(url,pin)==pin
    assert len(registry)==668
    def get(item):
        url,pin=item; raw=base64.b64decode(pin[7:],validate=True); key=raw.hex(); dest=p/'sri/_cacache/content-v2/sha512'/key[:2]/key[2:4]/key[4:]
        dest.parent.mkdir(parents=True,exist_ok=True)
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
        with opener.open(urllib.request.Request(url,headers={'Accept-Encoding':'identity'}),timeout=25) as r, dest.open('xb') as f:
            assert r.status==200 and r.url==url and r.headers.get('Content-Encoding','identity')=='identity'
            h=hashlib.sha512(); total=0
            while chunk:=r.read(1024*1024):
                total+=len(chunk);assert total<=48080284;h.update(chunk);f.write(chunk)
            assert h.digest()==raw and total>0
        return total
    def classify(item):
        try:return ('OK',get(item))
        except urllib.error.HTTPError as x:return ('HTTP_'+str(x.code) if x.code in (302,403,404,429,500,502,503,504) else 'HTTP_OTHER',0)
        except urllib.error.URLError:return ('TRANSPORT',0)
        except TimeoutError:return ('TIMEOUT',0)
        except AssertionError:return ('RESPONSE_OR_SRI',0)
        except (OSError, ValueError):return ('LOCAL_OR_FORMAT',0)
    with ThreadPoolExecutor(max_workers=6) as e:out=list(e.map(classify,registry.items()))
    from collections import Counter
    codes=Counter(k for k,n in out); total=sum(n for k,n in out)
    (p/'npm-status').write_text(''.join('B368_NPM_'+k+'='+str(v)+'\n' for k,v in sorted(codes.items())),encoding='ascii')
    assert codes=={'OK':668} and total=={'arm64':156673237,'x64':153584072}[cpu]


def offline(p):
    status=Path('/proc/self/status').read_text()
    assert os.getuid()!=0 and socket.if_nameindex()==[(1,'lo')] and 'CapEff:\t0000000000000000\n' in status and 'NoNewPrivs:\t1\n' in status
    src=p/'src'; assets=src/'app/src/main/assets'; recv=p/'receiver'; hd=p/'handoff';work=src/'app/build/locked-dsh-runtime'
    assert git(src,'rev-parse','HEAD','HEAD^{tree}').decode().split()==[C,T]
    want=expected(src); assert not work.exists()
    def run(root,*a):return sp.check_output(list(a),cwd=root,timeout=500)
    def py(root,*a):return run(root,sys.executable,'-sB',*a)
    assert run(src,'node','--version').strip()==b'v24.19.0' and run(src,'npm','--version').strip()==b'11.17.0'
    seed=json.loads(run(src,'node',p/'seed.cjs',src/LOCK,p/'sri',p/'arm-npm',p/'node-v24.19.0-linux-x64/lib/node_modules/npm/bin/npm-cli.js'))
    assert (seed['lockSha256'],seed['count'],seed['bytes'],len(seed['missing']))==(digest(src/LOCK),668,156673237,82)
    py(src,'tools/prepare-dsh-runtime.py','--cache',str(p/'arm-npm'),'--offline')
    built=json.loads((work/'build-report.json').read_bytes());arc=assets/'dsh-runtime.bin'
    assert digest(arc)==built['sha256']==RUNTIME and arc.stat().st_size==built['archive_bytes']==131056363
    got=json.loads((work/'node_modules/.package-lock.json').read_bytes())['packages'];assert set(want)==set(got) and digest(work/'package-lock.json')==digest(src/LOCK)
    for n,x in got.items():
        assert (x['integrity'],x['resolved'],x['version'])==tuple(want[n][k] for k in ('integrity','resolved','version'))
        assert json.loads((work/n/'package.json').read_bytes())['version']==x['version']
    for path in ('tools/prepare-backup-assets.py','tools/prepare-runtime-descriptor.py'):
        py(src,path,'--write');py(src,path,'--check')
    py(src,'tools/verify-runtime-descriptor-source.py')
    assert not git(src,'status','--porcelain=v1','--untracked-files=all')
    result=json.loads(py(src,'tools/ci-handoff.py','pack','--commit',C,'--dir',str(hd)));assert result['status']=='PASS_HANDOFF_PACK' and result['files']==14 and result['tarSha256']==TAR
    git(p,'clone','--no-hardlinks','--no-checkout',src,recv);git(recv,'remote','remove','origin');git(recv,'checkout','--detach',C)
    unpack=json.loads(py(recv,'tools/ci-handoff.py','unpack','--commit',C,'--dir',str(hd)));assert unpack['status']=='PASS_HANDOFF_UNPACK' and unpack['tarSha256']==TAR and unpack['files']==14
    py(recv,'tools/verify-runtime-descriptor-source.py')
    m=json.loads((hd/'handoff.json').read_bytes()); rows={x['path']:x for x in m['files']};assert len(rows)==14 and m['commit']==C and digest(hd/'handoff.tar')==TAR
    with tarfile.open(hd/'handoff.tar','r|') as tar:
        seen=set()
        for f in tar:
            x=rows[f.name];assert f.name not in seen and f.isreg() and f.mtime==0 and f.size==x['bytes'] and f.mode==(0o755 if x['executable'] else 0o644);seen.add(f.name)
            with tar.extractfile(f) as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==x['sha256']
            for r in (src,recv):
                local=r/f.name;assert local.resolve()==local and local.stat().st_size==x['bytes'] and digest(local)==x['sha256'] and bool(local.stat().st_mode&0o111)==x['executable']
        assert seen==rows.keys()
    for r in (src,recv):assert git(r,'rev-parse','HEAD','HEAD^{tree}').decode().split()==[C,T] and not git(r,'status','--porcelain=v1','--untracked-files=all')


if __name__=='__main__':
    assert __debug__ and len(sys.argv)==3 and sys.argv[1] in ('acquire','acquire-x64','offline')
    p=Path(sys.argv[2]); mode=sys.argv[1]
    if mode=='acquire-x64': acquire(p/'x64','x64',p/'src')
    elif mode=='acquire': acquire(p)
    else: offline(p)
