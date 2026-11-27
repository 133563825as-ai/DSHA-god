"""Inventory fresh Kotlin and Java class outputs; no DEX, packaging or class loading."""
import hashlib
import json
from pathlib import Path
import subprocess as sp
import sys


def check(src, c, t):
    assert __debug__ and src.resolve()==src
    def git(*args):
        return sp.check_output(['/usr/bin/git','--no-optional-locks','--no-replace-objects','-C',src,*args],timeout=10)
    assert git('rev-parse','HEAD','HEAD^{tree}').decode().split()==[c,t] and not git('status','--porcelain=v1')
    root=src/'app/build'; assert root.is_dir() and root.resolve()==root
    assert not [p for p in root.rglob('*') if p.is_file() and p.suffix in ('.dex','.apk')]
    groups={}; seen=set()
    for compiler,kind,task in [('kotlin','built_in_kotlinc','compileStandardReleaseKotlin'),('javac','javac','compileStandardReleaseJavaWithJavac')]:
        d=root/f'intermediates/{kind}/standardRelease/{task}/classes'
        assert d.resolve()==d and d.is_dir()
        rows=[]
        for p in sorted(d.rglob('*')):
            assert not p.is_symlink(),p
            if not p.is_file() or p.suffix!='.class':continue
            name=p.relative_to(d).as_posix(); assert name not in seen, name
            data=p.read_bytes(); assert data[:4]==b'\xca\xfe\xba\xbe' and int.from_bytes(data[6:8],'big')==61,name
            seen.add(name);rows.append(dict(name=name,bytes=len(data),sha256=hashlib.sha256(data).hexdigest()))
        assert rows and any(r['name'].startswith('com/deepseekharness/app/') for r in rows)
        groups[compiler]=dict(directory=d.relative_to(src).as_posix(),classes=len(rows),files=rows)
    cfgs=[p for p in root.rglob('BuildConfig.java') if '/standardRelease/' in p.as_posix()]
    f,=cfgs; text=f.read_text(); assert 'REBIRTH_CANDIDATE = true' in text and 'dsh.android.client.rebirth' in text and 'VERSION_CODE = 368' in text
    return dict(scope='FRESH_RELEASE_COMPILE_CLASS_BYTES_ONLY',commit=c,tree=t,compilers=groups,buildConfigSha256=hashlib.sha256(f.read_bytes()).hexdigest(),
                classMajor=61,duplicateOutputPaths=0,dexBuilt=False,apkBuilt=False,formalAdmission=False)


if __name__=='__main__':
    assert len(sys.argv)==4
    print(json.dumps(check(Path(sys.argv[1]),*sys.argv[2:]),indent=2))
