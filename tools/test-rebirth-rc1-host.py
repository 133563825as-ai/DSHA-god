#!/usr/bin/env python3
"""离线窄宿主门禁；不是 Android/Gradle/SDK 构建。见 fixtures 中的边界说明。"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / 'tools/fixtures/rebirth-rc1-host'
MAIN = ROOT / 'app/src/main/java/com/deepseekharness/app'
TEST = ROOT / 'app/src/test/java/com/deepseekharness/app'
# 显式清单，不通过 sourcepath 解析整个 Android 树。
SOURCES = '''
backup/RetainedCatalogue backup/BackupFileSystem backup/BackupJson backup/BackupLimits
backup/BackupArchive backup/BackupControl backup/BackupSource backup/HostOperationArchive backup/CompletedHistory
backup/CredentialFileBackupSource backup/CredentialProjection backup/DataRootPolicy
backup/FileBackupSource backup/GuestDataResolver backup/PrivateRoots
backup/TransactionMarkers backup/UnavailableBackupSource backup/VerifiedBackupCopy
backup/PortableBackupCrypto backup/UserDataLayout
runtime/GuestPluginScripts runtime/Rc1MigrationCache runtime/GuestCommandOutput
runtime/GuestPreparationFailure runtime/GuestScriptBudget runtime/GuestScriptQueue
util/BackupScopes util/BoundedProcessRunner util/CredentialPathRules util/CredentialPaths
util/DocumentPaths util/FileKind util/GuestCommandOutcome util/GuestPaths
util/GuestTimingEvidence util/Ids util/ManagedRuntimeLayout util/PluginDownloadSource
util/PluginJournalNames util/ProcessOutputCapture util/ProcessPipePoller
util/ProfileConfigPath util/Rc1MigrationResult util/Rc1MigrationReuse util/RegisterBudget
util/SensitiveData util/ShellQuote util/Constants util/NotificationIds util/ColdInstallPackages
util/BuiltinPlugins util/BuiltinPluginRegistry
'''.split()
TESTS = ['runtime/GuestPluginScriptsTest', 'backup/RetainedCatalogueTest',
         'runtime/Rc1MigrationCacheTest', 'util/Rc1MigrationResultTest', 'util/Rc1MigrationReuseTest']


def adapters(directory):
    def put(package, name, body):
        target = directory / package.replace('.', '/') / (name + '.java')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text('package ' + package + ';\n' + body, encoding='utf-8')
    base = 'com.deepseekharness.app.'
    reject = 'throw new AssertionError("Android adapter must not execute");'
    put('android.content.pm', 'ApplicationInfo', 'public class ApplicationInfo { public String dataDir; }')
    put('android.content', 'Context', 'public class Context { public android.content.pm.ApplicationInfo getApplicationInfo(){' + reject + '} public java.io.File getFilesDir(){' + reject + '} }')
    put('android.util', 'Log', 'public class Log { public static int w(String t,String m){System.err.println(t+": "+m);return 0;} public static int i(String t,String m){return w(t,m);} }')
    put(base+'runtime', 'RootfsInstaller', 'class RootfsInstaller { static void python(android.content.Context c,java.io.File f) throws java.io.IOException {'+reject+'} static void pnpm(android.content.Context c,java.io.File f) throws java.io.IOException {'+reject+'} }')
    put(base+'runtime', 'RuntimeTools', 'class RuntimeTools { static void prepare(android.content.Context c,java.io.File f) throws java.io.IOException {'+reject+'} static boolean queryReady(android.content.Context c,java.io.File f){'+reject+'} static String managedAssetId(android.content.Context c){'+reject+'} static String lastRegisteredAssetId(android.content.Context c,java.io.File f){'+reject+'} }')
    # Android constructor永不调用；abstract 接口方法由编译器保留，不借用 Android SDK。
    # 具体方法签名由当前JVM测试实现提供，但构造立即拒绝。
    jvm = (TEST/'backup/JvmBackupFileSystem.java').read_text().replace('public class JvmBackupFileSystem', 'public class AndroidBackupFileSystem')
    jvm = jvm.replace('implements BackupFileSystem {', 'implements BackupFileSystem { public AndroidBackupFileSystem(){'+reject+'}')
    path = directory/'com/deepseekharness/app/backup/AndroidBackupFileSystem.java'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(jvm)
    put(base+'util', 'UiText', 'public class UiText { public static String text(String s){return s;} public static String format(String s,Object... args){return String.format(java.util.Locale.ROOT,s,args);} }')
    put(base+'util', 'Compat', 'public class Compat { public static boolean isSymbolicLink(java.io.File f){return java.nio.file.Files.isSymbolicLink(f.toPath());} }')
    # 仅裁掉非被测定位器/归档维护入口；保留本轮 Catalogue 所用当前真实纯方法。
    native = (MAIN/'backup/NativeDataLocations.java').read_text()
    constants = native[native.index('  private static final Set<String> SESSION_NAMES'):native.index('  public static final class Selection')]
    methods = native[native.index('  public static String classify'):native.rindex('}')]
    put(base+'backup', 'NativeDataLocations', 'import java.util.*; public class NativeDataLocations {\n'+constants+methods+'}')
    for name, body in [('EnvironmentRebuildTransaction','HOME = PrivateRoots.ENVIRONMENTS'), ('ManagedRuntimeTransaction','HOME = PrivateRoots.RUNTIMES'), ('PluginInstallJournals','DIRECTORY = "plugin-install-operations", HISTORY_PROOF = "plugin-install-completed-v1.json"'), ('PluginJournalQuarantine','DIRECTORY = "plugin-install-quarantine"')]:
        put(base+'backup', name, 'public class '+name+' { public static final String '+body+'; }')
    put(base+'backup', 'ColdInstallTransaction', 'public class ColdInstallTransaction { public static class Candidate { public void verify(BackupFileSystem f,com.deepseekharness.app.util.ColdInstallPackages.HostRoot h){'+reject+'} public java.io.File root(){'+reject+'} } }')
    put(base+'backup', 'NativeConfigurationReset', 'class NativeConfigurationReset { static boolean owns(BackupFileSystem f,java.io.File d){return false;} static void clearRolledBackPlaintext(BackupFileSystem f,java.io.File d){'+reject+'} }')
    put(base+'backup', 'RestoreStaging', 'class RestoreStaging { static void clearSensitivePlaintext(BackupFileSystem f,java.io.File d){'+reject+'} }')


def jar(pattern, gradle_home):
    matches = sorted((gradle_home/'caches/modules-2/files-2.1').glob(pattern))
    if not matches:
        raise RuntimeError('missing offline jar: '+pattern)
    return matches[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT/'app/build/rebirth-rc1-closure/host-verification')
    parser.add_argument('--compile-only', action='store_true')
    parser.add_argument('--junit-only', action='store_true', help='diagnostic compile+JUnit only; not full-chain PASS')
    parser.add_argument('--consumers-only', action='store_true', help='Startup/WebReady consumers and exact WebRun slice only; no Catalogue or cross-layer')
    parser.add_argument('--gradle-home', type=Path, default=Path(os.environ.get('GRADLE_USER_HOME', Path.home()/'.gradle')),
                        help='existing offline dependency cache; independent of isolated child HOME')
    parser.add_argument('--runtime', type=Path, default=os.environ.get('DSHA_TEST_RUNTIME'),
                        help='existing proven raw test runtime; never downloads or installs')
    args = parser.parse_args()
    output = args.output.resolve()
    expected = (ROOT/'app/build/rebirth-rc1-closure/host-verification').resolve()
    if output != expected:
        parser.error('output must be the dedicated host-verification directory')
    output.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix='run-', dir=output))
    env = {'PATH': os.environ['PATH'], 'HOME': str(run/'home'), 'LANG': 'C.UTF-8', 'TMPDIR': str(run)}
    Path(env['HOME']).mkdir()
    if sum([args.compile_only, args.junit_only, args.consumers_only]) > 1:
        parser.error('--compile-only, --junit-only and --consumers-only are mutually exclusive')
    if not args.compile_only and not args.junit_only and not args.consumers_only:
        if args.runtime is None or not args.runtime.is_dir():
            parser.error('full chain needs --runtime or DSHA_TEST_RUNTIME pointing to existing raw fixture')
        env['DSHA_TEST_RUNTIME'] = str(args.runtime.resolve())
        env['DSHA_PYTHON'] = sys.executable
    print('Logs:', run, flush=True)
    def execute(label, command, timeout=180):
        print(label, flush=True)
        log = run/(label+'.log')
        with log.open('w') as stream:
            stream.write('$ '+ ' '.join(map(str,command))+'\n'); stream.flush()
            try:
                result = subprocess.run([str(x) for x in command], cwd=ROOT, env=env, text=True,
                                        stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
            except subprocess.TimeoutExpired:
                stream.write('\nTIMEOUT='+str(timeout)+'\n')
                raise
            stream.write('\nexit='+str(result.returncode)+'\n')
        text = log.read_text()
        print(text, end='', flush=True)
        result.check_returncode()
        return text
    locked = json.loads((ROOT/'tools/backup-dependencies.lock.json').read_text())['dependencies']
    gradle_home = args.gradle_home.resolve()
    jars = [jar('junit/junit/4.13.2/*/junit-4.13.2.jar', gradle_home),
            jar('org.hamcrest/hamcrest-core/1.3/*/hamcrest-core-1.3.jar', gradle_home)]
    for dependency in locked:
        name, version = dependency['name'], dependency['version']
        path = jar(f"{dependency['group']}/{name}/{version}/*/{name}-{version}.jar", gradle_home)
        if hashlib.sha256(path.read_bytes()).hexdigest() != dependency['sha256']:
            raise RuntimeError('locked jar SHA mismatch: '+str(path))
        jars.append(path)
    cp = os.pathsep.join(map(str, jars))
    stubs = run/'adapters'; adapters(stubs)
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location('consumer_adapters', FIXTURE/'consumer_adapters.py')
    consumers = importlib.util.module_from_spec(spec); spec.loader.exec_module(consumers)
    slice_proof = consumers.prepare(stubs, MAIN)
    classes = run/'classes'; classes.mkdir()
    selected_tests = consumers.TESTS if args.consumers_only else TESTS + consumers.TESTS
    sources = [MAIN/(x+'.java') for x in SOURCES + consumers.SOURCES] + [TEST/(x+'.java') for x in selected_tests] + [TEST/'backup/JvmBackupFileSystem.java'] + sorted(stubs.rglob('*.java')) + sorted(FIXTURE.glob('*.java'))
    manifest = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources if p.is_relative_to(ROOT) and not p.is_relative_to(run)}
    for path in [ROOT/'app/src/main/assets'/name for name in
                 ['rc1-migration.py', 'rc1-settings-migration.cjs', 'rc1-settings-migration-patch.json', 'runtime-patches.json']]:
        manifest[str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
    manifest['tools/fixtures/rebirth-rc1-host/hook.mjs'] = hashlib.sha256((FIXTURE/'hook.mjs').read_bytes()).hexdigest()
    manifest['tools/test-rebirth-rc1-host.py'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    manifest['tools/fixtures/rebirth-rc1-host/consumer_adapters.py'] = hashlib.sha256((FIXTURE/'consumer_adapters.py').read_bytes()).hexdigest()
    manifest['app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java'] = slice_proof['sourceSha256']
    (run/'inputs.json').write_text(json.dumps({'sourceSha256': manifest,
        'adapterSha256': {str(p.relative_to(stubs)): hashlib.sha256(p.read_bytes()).hexdigest() for p in stubs.rglob('*.java')},
        'jarSha256': {p: hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in cp.split(os.pathsep)},
        'runtime': env.get('DSHA_TEST_RUNTIME'), 'gradleHome': str(gradle_home),
        'controllerSlice': slice_proof, 'selectedTests': selected_tests,
        'unexecuted': ['WebLifecycleStartProtectionTest (real Robolectric SDK34 unavailable)'],
        'scope': 'explicit host javac/JUnit; adapters are not Android validation'}, indent=2))
    execute('javac', ['javac','-J-Xmx384m','-encoding','UTF-8','-sourcepath',str(stubs),'-cp',cp,'-d',classes,*sources])
    if not args.compile_only:
        failed = []
        stages = [
            ('cross-layer', ['java','-Xmx384m','-cp',str(classes)+os.pathsep+cp,'com.deepseekharness.app.runtime.HostRc1Chain',ROOT,run,sys.executable,shutil.which('node')]),
            ('junit', ['java','-Xmx384m','-cp',str(classes)+os.pathsep+cp,'com.deepseekharness.app.runtime.HostJUnit',*[('com.deepseekharness.app.'+x.replace('/','.')) for x in selected_tests]])]
        if args.junit_only or args.consumers_only:
            stages = [row for row in stages if row[0] == 'junit']
        for label, command in stages:
            try:
                execute(label, command, timeout=900 if label == 'junit' else 180)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
                failed.append(label)
                print(f'{label} FAILED: {error}', flush=True)
        if failed:
            (run/'FAILED').write_text('\n'.join(failed)+'\n')
            return 1
    marker, summary = ('COMPILE_ONLY_PASS', 'compile only; no tests executed\n') if args.compile_only else (
        ('CONSUMERS_ONLY_PASS', 'StartupPipeline/WebReadyTasks current tests + exact nested WebRun slice; not full Controller/Robolectric/chain\n') if args.consumers_only else (
        ('JUNIT_ONLY_PASS', 'targeted host JUnit only; cross-layer not executed in this run\n') if args.junit_only
        else ('PASS', 'host targeted tests, consumers and cross-layer chain passed; not full Controller/Robolectric\n')))
    (run/marker).write_text(summary)
    print('Logs:',run)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
