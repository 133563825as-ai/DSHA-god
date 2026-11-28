"""Bounded diagnostics from a private Gradle failure; never emit log text or paths."""
from pathlib import Path
import re
import sys

from online_b368_compile_diagnostics import GAV, TOKEN, locked, summarize

TASKS = {
    ':verifyDshaBuildConventions', ':app:testStandardDebugUnitTest',
    ':app:compileStandardDebugUnitTestJavaWithJavac', ':app:compileStandardDebugUnitTestKotlin',
    ':app:compileStandardDebugJavaWithJavac', ':app:compileStandardDebugKotlin',
    ':app:processStandardDebugResources', ':app:processStandardDebugUnitTestResources',
    ':app:generateStandardDebugUnitTestConfig', ':app:checkStandardDebugAarMetadata',
    ':app:mergeStandardDebugResources', ':app:mergeStandardDebugAssets',
    ':app:mockableAndroidJar', ':app:prepareStandardAssets', ':app:prepareRecoveryAssets',
}
CONFIGS = {
    'standardDebugUnitTestCompileClasspath', 'standardDebugUnitTestRuntimeClasspath',
    'standardDebugCompileClasspath', 'standardDebugRuntimeClasspath',
    'androidJdkImage', 'androidApis', 'androidTestUtil', 'coreLibraryDesugaring',
    'kotlinCompilerClasspath', 'kotlinBuildToolsApiClasspath',
}


def markers(text, allowed):
    tasks, gavs, files = summarize(text, TASKS, allowed)
    gavs, files = set(gavs), set(files)
    configs = set(re.findall(r"(?:configuration|artifacts for configuration) ':(?:app:)?([A-Za-z0-9]+)'", text)) & CONFIGS
    for line in text.splitlines():
        if not re.search(r'(?i)could not|no cached|not available.*offline|failed to transform',line):
            continue
        for name, gav in re.findall(rf'\b({TOKEN}) \(({GAV})\)',line):
            if name in allowed.get(gav, set()):
                gavs.add(gav)
                files.add((gav,name))
        for gav in re.findall(rf'(?<![\w.+:\-])({GAV})(?![\w+:\-])',line):
            if gav in allowed or (gav := gav.rstrip('.')) in allowed:
                gavs.add(gav)
    out=[f'B368_JVM_FAILED_TASK={x}' for x in tasks]
    out.extend(f'B368_JVM_FAILED_CONFIG={x}' for x in sorted(configs))
    out.extend(f'B368_JVM_LOCKED_COORDINATE={x}' for x in sorted(gavs)[:12])
    out.extend(f'B368_JVM_LOCKED_ARTIFACT={gav}:{name}' for gav,name in sorted(files)[:12])
    if not out:
        out.append('B368_JVM_FAILURE_DETAIL=UNCLASSIFIED')
    return out


if __name__=='__main__':
    assert __debug__ and len(sys.argv)==3
    log,lock=map(Path,sys.argv[1:]); assert log.is_file() and 0 < log.stat().st_size <= 16*2**20
    for marker in markers(log.read_text(encoding='utf8',errors='replace'),locked(lock.read_bytes())):
        print(marker)
