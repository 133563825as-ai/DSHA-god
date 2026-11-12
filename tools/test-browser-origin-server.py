#!/usr/bin/env python3
"""Actual Java restore server over real sockets/files; Android syscall/UI APIs are explicit exclusions."""
import argparse
import os
from pathlib import Path
import runpy
import subprocess
import sys
ROOT = Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk',type=Path,default=Path(os.environ.get('DSHA_JAVA17','F:/DSHA/_toolchains/jdk-17')))
    parser.add_argument('--framework',type=Path,help='显式的真实 Android framework jar；仍须匹配固定 SHA-256')
    args=parser.parse_args()
    jars=runpy.run_path(str(ROOT/'tools/prepare-sprout-host-jars.py'))
    framework_jar=jars['jar_specs']()['framework']
    if args.framework is not None:
        framework_jar=framework_jar._replace(path=args.framework)
    try:
        framework=jars['check_jar'](framework_jar)
    except (OSError,ValueError) as error:
        parser.error(str(error))
    output=ROOT/'app/build/browser-origin190/java-server'
    output.mkdir(parents=True,exist_ok=True)
    # 固定真实 Android framework/JSONObject；不接受 android.jar 桩或 Gradle cache 猜测。
    compiled=ROOT/'app/build/intermediates/javac/standardRelease/compileStandardReleaseJavaWithJavac/classes'
    cp=os.pathsep.join(map(str,[framework,compiled]))
    sources=[ROOT/'app/src/main/java/com/deepseekharness/app'/p for p in [
        'ui/BrowserOriginRestoreServer.java','backup/BackupFileSystem.java','backup/BackupLimits.java','util/FileKind.java',
        'util/ColdInstallPackages.java']]
    sources += [ROOT/'app/src/test/java/com/deepseekharness/app/backup/JvmBackupFileSystem.java',ROOT/'tools/fixtures/browser-origin/BrowserOriginRestoreServerTest.java']
    suffix='.exe' if os.name=='nt' else ''
    command=[str(args.jdk/('bin/javac'+suffix)),'-encoding','UTF-8','--release','17','-cp',cp,'-d',str(output),*map(str,sources)]
    result=subprocess.run(command,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    (output/'compile.log').write_text(result.stdout,encoding='utf8');print(result.stdout,end='');result.check_returncode()
    runtime=os.pathsep.join([str(output),cp]);directory=output/('fixture-'+str(os.getpid()))
    result=subprocess.run([str(args.jdk/('bin/java'+suffix)),'-cp',runtime,'com.deepseekharness.app.ui.BrowserOriginRestoreServerTest',str(directory)],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=90)
    (output/'result.log').write_text(result.stdout,encoding='utf8');print(result.stdout,end='');result.check_returncode()
    (output/'framework-sha256.txt').write_text(framework_jar.sha256+'\n',encoding='ascii')

if __name__=='__main__':main()
