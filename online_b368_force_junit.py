"""Use this job's freshly compiled production classes to run the original force-stop tests."""
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys

TESTS = {
    'WebForcePreparationContractTest': 3,
    'WebForceStopGateTest': 2,
    'WebForceStopTextTest': 5,
    'WebStopCoordinatorTest': 3,
}
PACKAGE = 'com.deepseekharness.app.core.'


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main(p, jdk, commit, tree):
    assert __debug__ and os.getuid()!=0 and socket.if_nameindex()==[(1,'lo')]
    assert 'NoNewPrivs:\t1\n' in Path('/proc/self/status').read_text()
    repo=p/'receiver'; build=repo/'app/build'
    git=lambda *args: subprocess.check_output(['/usr/bin/git','--no-replace-objects','--no-optional-locks','-C',repo,*args],timeout=20)
    def source():
        assert git('rev-parse','HEAD','HEAD^{tree}').decode().split()==[commit,tree]
        assert not git('status','--porcelain=v1','--untracked-files=all') and not git('remote')
        assert not any(f.suffix in {'.apk','.dex','.aab','.apks'} for f in build.rglob('*'))
    source()
    junit=p/'junit-4.13.2.jar'; hamcrest=p/'hamcrest-core-1.3.jar'
    assert sha(junit)=='8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3'
    assert sha(hamcrest)=='66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9'
    prod=[build/'intermediates'/kind/'standardRelease'/task/'classes' for kind,task in (
        ('javac','compileStandardReleaseJavaWithJavac'),('built_in_kotlinc','compileStandardReleaseKotlin'))]
    assert all(f.resolve()==f and f.is_dir() for f in prod)
    for name in ('WebStopCoordinator','WebStopCoordinator$ForceStopPreparation','WebStopCoordinator$ForceStopRequest','WebLifecycleController'):
        f=prod[0]/'com/deepseekharness/app/core'/(name+'.class')
        assert f.is_file() and f.read_bytes()[:8]==bytes.fromhex('cafebabe0000003d')
    assert not list((prod[0]/'com/deepseekharness/app/core').glob('HarnessController$ForceStop*.class'))
    sources=[repo/'app/src/test/java/com/deepseekharness/app/core'/(name+'.java') for name in TESTS]
    assert all(f.resolve()==f and f.read_bytes()==git('cat-file','blob',commit+':'+f.relative_to(repo).as_posix()) for f in sources)
    cp=[*prod,junit,hamcrest]
    output=p/'force-test-classes'; output.mkdir()
    compile_log=p/'force-javac.log'
    with compile_log.open('xb') as out:
        rc=subprocess.run([jdk/'bin/javac','-J-Dfile.encoding=UTF-8','-encoding','UTF-8','--release','17',
                           '-cp',os.pathsep.join(map(str,cp)),'-d',output,*sources],cwd=repo,stdout=out,stderr=subprocess.STDOUT,timeout=80).returncode
    print(f'B368_FORCE_JAVAC={rc}:{sha(compile_log)}',flush=True)
    if rc:return rc
    for f in sorted(output.rglob('*.class')):
        assert f.read_bytes()[:8]==bytes.fromhex('cafebabe0000003d')
    outcomes={}
    for name,total in TESTS.items():
        log=p/(name+'.log')
        with log.open('xb') as out:
            rc=subprocess.run([jdk/'bin/java','-Dfile.encoding=UTF-8','-cp',os.pathsep.join(map(str,[output,*cp])),
                               'org.junit.runner.JUnitCore',PACKAGE+name],cwd=repo,stdout=out,stderr=subprocess.STDOUT,timeout=45).returncode
        data=log.read_bytes()
        if rc==0:
            hits=re.findall(rb'(?m)^OK \(([1-9][0-9]*) tests?\)$',data)
            assert hits==[str(total).encode()]
            seen=total
        else:
            seen=0
            for marker,pattern in [('MISSING_CLASS',rb'NoClassDefFoundError|ClassNotFoundException'),('ASSERTION',rb'AssertionError'),('ANDROID_STUB',rb'Stub!')]:
                if re.search(pattern,data):print(f'B368_FORCE_REASON={name}:{marker}',flush=True)
        outcomes[name]=rc
        print(f'B368_FORCE_CASE={name}:{rc}:{seen}:{sha(log)}',flush=True)
    source()
    return int(any(outcomes.values()))


if __name__=='__main__':
    p,jdk,c,t=sys.argv[1:]
    raise SystemExit(main(Path(p),Path(jdk),c,t))
