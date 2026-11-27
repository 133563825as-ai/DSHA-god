"""仅status/preflight；不是已获准执行项目任务的Gradle封装。"""
import hashlib
import json
import os
from pathlib import Path
import secrets
import stat
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = Path('/tmp/dsha-gradle.guard')
CACHE = Path('/root/.gradle')
SDK = Path('/root/android-sdk')
JDK = Path('/usr/lib/jvm/java-17-openjdk-arm64')
# 预留launcher384 + single-use daemon768 + test512 + native384 MiB；不是可任意fork的保证。
REQUIRED = 2048 * 1024 * 1024
INJECT = ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'GRADLE_OPTS', 'JAVA_OPTS',
          'GRADLE_USER_HOME', 'JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT')


class Refused(RuntimeError):
    pass


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def regular(path):
    node = path.lstat()
    if not stat.S_ISREG(node.st_mode):
        raise Refused('NOT_REGULAR:' + str(path))
    return node


def process_identity(pid):
    # stat comm可含空格和括号，按最后右括号之后取字段22。
    value = Path(f'/proc/{pid}/stat').read_text()
    tail = value[value.rindex(')') + 2:].split()
    return {'pid': pid, 'birth': tail[19],
            'boot': Path('/proc/sys/kernel/random/boot_id').read_text().strip()}


class GlobalLock:
    def __init__(self, path=LOCK):
        self.path = path
        self.owner = None

    def __enter__(self):
        try:
            self.path.mkdir(mode=0o700)
        except FileExistsError:
            raise Refused('GLOBAL_LOCK_EXISTS_NO_AUTOCLEAN') from None
        node = self.path.lstat()
        if not stat.S_ISDIR(node.st_mode) or node.st_uid != os.getuid() or node.st_mode & 0o077:
            raise Refused('GLOBAL_LOCK_DIRECTORY_IDENTITY')
        self.directory = (node.st_dev, node.st_ino)
        self.owner = json.dumps({**process_identity(os.getpid()), 'nonce': secrets.token_hex(24)}, sort_keys=True).encode()
        # 发布不完整时保留现场，绝不当作可自动清理stale。
        fd = os.open(self.path/'owner.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        try:
            if os.write(fd, self.owner) != len(self.owner):
                raise Refused('GLOBAL_LOCK_OWNER_PARTIAL_RETAINED')
            os.fsync(fd)
        finally:
            os.close(fd)
        return self

    def __exit__(self, *_):
        node = self.path.lstat()
        owner_path = self.path/'owner.json'
        if not stat.S_ISDIR(node.st_mode) or (node.st_dev, node.st_ino) != self.directory:
            raise Refused('GLOBAL_LOCK_CHANGED_RETAINED')
        fd = os.open(owner_path, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            opened = os.fstat(fd)
            owner = os.read(fd, len(self.owner) + 1)
        finally:
            os.close(fd)
        if not stat.S_ISREG(opened.st_mode) or owner != self.owner:
            raise Refused('GLOBAL_LOCK_CHANGED_RETAINED')
        saved = json.loads(self.owner)
        if any(saved[k] != v for k, v in process_identity(os.getpid()).items()):
            raise Refused('GLOBAL_LOCK_OWNER_CHANGED_RETAINED')
        # 固定已核对绝对目录，仅解除本次唯一owner，不递归删除。
        current_owner = owner_path.lstat()
        current_dir = self.path.lstat()
        if ((current_owner.st_dev, current_owner.st_ino) != (opened.st_dev, opened.st_ino)
                or (current_dir.st_dev, current_dir.st_ino) != self.directory):
            raise Refused('GLOBAL_LOCK_CHANGED_RETAINED')
        owner_path.unlink()
        self.path.rmdir()


def memory_available():
    # 仅候选余量：固定cgroup根可能不是当前进程实际层；不是完整资源准入证明。
    fields = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
    available = int(fields['MemAvailable'].split()[0]) * 1024
    maximum, current = Path('/sys/fs/cgroup/memory.max'), Path('/sys/fs/cgroup/memory.current')
    if maximum.is_file() and current.is_file():
        cap = maximum.read_text().strip()
        if cap != 'max':
            available = min(available, int(cap) - int(current.read_text()))
    return available


def injection(env, cache=CACHE, user_home=None, distribution=None):
    present = [key for key in INJECT if env.get(key)]
    present += [key for key in env if key.startswith('ORG_GRADLE_PROJECT_')]
    if present:
        raise Refused('ENVIRONMENT_OVERRIDE_NOT_ADMITTED:' + ','.join(sorted(present)))
    homes = [cache, user_home or Path.home()/'.gradle']
    for home in homes:
        for name in ('init.gradle', 'init.gradle.kts'):
            if os.path.lexists(home/name):
                raise Refused('IMPLICIT_INIT_NOT_ADMITTED:' + str(home/name))
        init = home/'init.d'
        if os.path.lexists(init) and (init.is_symlink() or not init.is_dir() or any(init.iterdir())):
            raise Refused('IMPLICIT_INIT_NOT_ADMITTED:' + str(init))
    if distribution:
        init = distribution/'init.d'
        # Gradle自带readme无执行作用，任何其余成员均拒绝。
        if init.is_symlink() or init.exists() and any(p.name != 'readme.txt' for p in init.iterdir()):
            raise Refused('DISTRIBUTION_INIT_NOT_ADMITTED')


def exact_inputs(root=ROOT, cache=CACHE, sdk=SDK, jdk=JDK):
    # 仅候选静态元数据：zip摘要不证明解包树一致，Java摘要未预先pin；绝不授执行。
    props = dict(line.split('=', 1) for line in (root/'gradle/wrapper/gradle-wrapper.properties').read_text().splitlines() if '=' in line)
    contract = json.loads((root/'ci/toolchain.lock.json').read_text())
    if contract['gradle'] != '9.3.1' or contract['java'] != '17' or contract['androidPlatform'] != 'android-37.0':
        raise Refused('TOOLCHAIN_CONTRACT_NOT_ADMITTED')
    candidates = sorted((cache/'wrapper/dists/gradle-9.3.1-bin').glob('*/gradle-9.3.1'))
    if len(candidates) != 1:
        raise Refused('EXACT_DISTRIBUTION_MISSING_OR_AMBIGUOUS')
    distribution = candidates[0]
    if distribution.resolve() != distribution or any(p.is_symlink() for p in [distribution, *distribution.parents] if p != Path('/')):
        raise Refused('DISTRIBUTION_SYMLINK')
    archive = distribution.parent/'gradle-9.3.1-bin.zip'
    if not archive.exists():
        raise Refused('DISTRIBUTION_ZIP_PIN_MISSING_NO_FALLBACK')
    regular(archive)
    if sha(archive) != props['distributionSha256Sum']:
        raise Refused('DISTRIBUTION_ZIP_SHA256')
    launcher = distribution/'lib/gradle-launcher-9.3.1.jar'
    regular(launcher)
    for binary in ('java', 'javac'):
        if not os.access(jdk/'bin'/binary, os.X_OK):
            raise Refused('JDK_EXECUTABLE_MISSING')
    release = (jdk/'release').read_text()
    if 'JAVA_VERSION="17.' not in release:
        raise Refused('JDK_NOT_17')
    metadata = sdk/'platforms/android-37.0/source.properties'
    platform = sdk/'platforms/android-37.0/android.jar'
    if 'AndroidVersion.ApiLevel=37.0' not in metadata.read_text():
        raise Refused('SDK_PLATFORM_METADATA')
    regular(platform)
    return {'distribution': str(distribution), 'zipSha256': sha(archive), 'launcherSha256': sha(launcher),
            'jdk':str(jdk), 'javaSha256':sha(jdk/'bin/java'), 'sdk':str(sdk), 'platformSha256':sha(platform)}


# 本轮不实现或启动任何JVM诊断probe。即便静态条件齐备也继续拒绝，
# 直到后续单独授权并独审真实双JVM锁及逐JVM oom900写读协议。

def status(path=LOCK):
    if not os.path.lexists(path):return {'lock':'absent','projectExecution':'NOT_ADMITTED'}
    node=path.lstat()
    return {'lock':'present_no_autoclean','symlink':stat.S_ISLNK(node.st_mode),
            'projectExecution':'NOT_ADMITTED'}


def main(argv=None):
    args=sys.argv[1:] if argv is None else argv
    if args==['--status']:
        print(json.dumps(status()));return 0
    if args!=['--preflight']:
        print('PROJECT_EXECUTION_NOT_ADMITTED',file=sys.stderr);return 2
    output=ROOT/'app/build/rebirth-rc1-closure/gradle-guard';output.mkdir(parents=True,exist_ok=True)
    work=Path(tempfile.mkdtemp(prefix='run-',dir=output))
    report={'projectExecution':'NOT_ADMITTED','probeExecuted':False,'status':'REFUSED','scope':'diagnostic preflight only'}
    try:
        with GlobalLock():
            injection(os.environ)
            available=memory_available();report['availableBytes']=available;report['requiredBytes']=REQUIRED
            if available<REQUIRED:raise Refused('INSUFFICIENT_HEADROOM_2048_MIB')
            report['inputs']=exact_inputs()
            injection(os.environ,distribution=Path(report['inputs']['distribution']))
            raise Refused('DIAGNOSTIC_JVM_PROBES_NOT_ADMITTED')
    except (Refused,OSError,ValueError,KeyError) as error:
        report['reason']=str(error);code=1
    (work/'receipt.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report));print('Evidence:',work)
    return code
