"""Return fixed fields only from a runner-local Rebirth lint failure."""
import hashlib
import json
from pathlib import Path
import re
import runpy
import sys

# Isolated Python excludes the script directory from sys.path.
_shared = runpy.run_path(str(Path(__file__).with_name('online_b368_compile_diagnostics.py')))
GAV, TOKEN, locked, summarize = (_shared[k] for k in ('GAV', 'TOKEN', 'locked', 'summarize'))

GRAPH_SHA = 'bdabf3ef53ed9bd6439f28af3c73b9ad7cdd71897aaef586bd6b99365a867a60'
CONFIGS = {
    'standardReleaseCompileClasspath', 'standardReleaseRuntimeClasspath',
    'standardReleaseUnitTestCompileClasspath', 'standardReleaseUnitTestRuntimeClasspath',
    'standardReleaseAndroidTestCompileClasspath', 'standardReleaseAndroidTestRuntimeClasspath',
    'kotlinBuildToolsApiClasspath', 'kotlinCompilerClasspath', 'lintChecks', 'lintClassPath',
    'standardReleaseLintChecks', 'standardReleaseLintChecksClasspath', 'lintPublish',
    'coreLibraryDesugaring',
}


def extract(text, tasks, allowed):
    failed, gavs, files = summarize(text, tasks, allowed)
    gavs, files = set(gavs), set(files)
    configs = set()
    for name in re.findall(r"configuration ':app:([A-Za-z0-9_]+)'", text):
        if name in CONFIGS:
            configs.add(name)
        elif re.fullmatch(r'detachedConfiguration[0-9]{1,4}', name):
            configs.add('DETACHED')
        else:
            configs.add('UNLISTED')
    for name, gav in re.findall(rf'No cached version of ({TOKEN}) \(({GAV})\) available for offline mode', text):
        if name in allowed.get(gav, set()):
            files.add((gav, name)); gavs.add(gav)
    return failed, sorted(configs), sorted(gavs)[:12], sorted(files)[:12]


def main():
    assert __debug__ and len(sys.argv) == 5
    phase, log, lock, guard = sys.argv[1:]
    assert phase in ('lint', 'offline')
    tasks = json.loads(re.search(r'(?m)^    def expected = (.*)$', Path(guard).read_text(encoding='utf8'))[1])
    assert len(tasks) == 37 and hashlib.sha256(('\n'.join(tasks)+'\n').encode()).hexdigest() == GRAPH_SHA
    log = Path(log)
    assert log.is_file() and log.stat().st_size <= 16 * 2**20
    failed, configs, gavs, files = extract(log.read_text(encoding='utf8', errors='replace'), tasks, locked(Path(lock).read_bytes()))
    for task in failed:
        print(f'B368_LINT_FAILED_TASK={phase}{task}')
    for cfg in configs:
        print(f'B368_LINT_CONFIG={phase}:{cfg}')
    for gav in gavs:
        print(f'B368_LINT_LOCKED_COORDINATE={phase}:{gav}')
    for gav, name in files:
        print(f'B368_LINT_LOCKED_ARTIFACT={phase}:{gav}:{name}')


if __name__ == '__main__':
    main()
