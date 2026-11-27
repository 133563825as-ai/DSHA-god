"""Inventory fresh Kotlin and Java class outputs; no DEX, packaging or class loading."""
import hashlib
import json
from pathlib import Path
import subprocess as sp
import sys


def check(src, c, t):
    assert __debug__ and src.resolve()==src, 'SOURCE_PATH'
    def git(*args):
        return sp.check_output(['/usr/bin/git','--no-optional-locks','--no-replace-objects','-C',src,*args],timeout=10)
    assert git('rev-parse','HEAD','HEAD^{tree}').decode().split()==[c,t], 'SOURCE_IDENTITY'
    assert not git('status','--porcelain=v1'), 'SOURCE_DIRTY'
    root=src/'app/build'; assert root.is_dir() and root.resolve()==root, 'BUILD_ROOT'
    assert not [p for p in root.rglob('*') if p.is_file() and p.suffix in ('.dex','.apk')], 'UNEXPECTED_DEX_OR_APK'
    groups={}; seen=set()
    for compiler,kind,task in [('kotlin','built_in_kotlinc','compileStandardReleaseKotlin'),('javac','javac','compileStandardReleaseJavaWithJavac')]:
        d=root/f'intermediates/{kind}/standardRelease/{task}/classes'
        assert d.resolve()==d and d.is_dir(), 'CLASS_ROOT_'+compiler.upper()
        rows=[]
        for p in sorted(d.rglob('*')):
            assert not p.is_symlink(), 'CLASS_SYMLINK'
            if not p.is_file() or p.suffix!='.class':continue
            name=p.relative_to(d).as_posix(); assert name not in seen, 'CLASS_DUPLICATE'
            data=p.read_bytes(); assert len(data)>=8 and data[:4]==b'\xca\xfe\xba\xbe' and int.from_bytes(data[6:8],'big')==61, 'CLASS_MAGIC_OR_VERSION'
            seen.add(name);rows.append(dict(name=name,bytes=len(data),sha256=hashlib.sha256(data).hexdigest()))
        assert rows and any(r['name'].startswith('com/deepseekharness/app/') for r in rows), 'CLASS_PACKAGE_'+compiler.upper()
        groups[compiler]=dict(directory=d.relative_to(src).as_posix(),classes=len(rows),files=rows)
    f=root/'generated/source/buildConfig/standard/release/com/deepseekharness/app/BuildConfig.java'
    assert f.is_file() and f.resolve()==f, 'GENERATED_BUILD_CONFIG_PATH'
    text=f.read_text(encoding='utf-8'); assert 'REBIRTH_CANDIDATE = true' in text and 'dsh.android.client.rebirth' in text and 'VERSION_CODE = 368' in text, 'GENERATED_BUILD_CONFIG_IDENTITY'
    return dict(scope='FRESH_RELEASE_COMPILE_CLASS_BYTES_ONLY',commit=c,tree=t,compilers=groups,buildConfigSha256=hashlib.sha256(f.read_bytes()).hexdigest(),
                classMajor=61,duplicateOutputPaths=0,dexBuilt=False,apkBuilt=False,formalAdmission=False)


if __name__=='__main__':
    assert len(sys.argv)==4
    try:
        result=check(Path(sys.argv[1]),*sys.argv[2:])
    except AssertionError as e:
        allowed={'SOURCE_PATH','SOURCE_IDENTITY','SOURCE_DIRTY','BUILD_ROOT','UNEXPECTED_DEX_OR_APK',
                 'CLASS_ROOT_KOTLIN','CLASS_ROOT_JAVAC','CLASS_SYMLINK','CLASS_DUPLICATE','CLASS_MAGIC_OR_VERSION',
                 'CLASS_PACKAGE_KOTLIN','CLASS_PACKAGE_JAVAC','GENERATED_BUILD_CONFIG_PATH','GENERATED_BUILD_CONFIG_IDENTITY'}
        print('B368_CLASS_REFUSED='+(str(e) if str(e) in allowed else 'ASSERTION'),file=sys.stderr)
        raise SystemExit(1)
    except (OSError, UnicodeError, sp.SubprocessError):
        print('B368_CLASS_REFUSED=READ_FAILED',file=sys.stderr)
        raise SystemExit(1)
    print(json.dumps(result,indent=2))
