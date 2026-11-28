"""Run the existing identity-sensitive tests against two fresh, offline checkouts."""
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess as sp
import sys
from xml.etree import ElementTree as ET

CASES = {'UpdateEngineCheckTest': 8, 'UpdateOwnershipTest': 20}
METHODS = {'UpdateEngineCheckTest': ['rebirthCandidateNeverChecksRestoresDownloadsOrInstallsOfficialUpdates', 'legacyStartupHooksNeverCheckOrPromptButManualCheckStillWorks', 'absentFeedAndNoPublishedReleaseHaveNormalEmptyStates', 'legacyApplicationAndMissingAssetNeverBecomeInstallCandidates', 'certificateFailureIsUnknownThenARetryCanConfirmNoUpdate', 'serverFailureKeepsTheLastVerifiedChannelCandidate', 'switchingChannelWhileCheckingSupersedesTheStaleCheck', 'cancelAfterSwitchingDoesNotStartAnotherCheck'], 'UpdateOwnershipTest': ['applicationFacadesShareExactlyOneEngineAndQueue', 'buildSelectedUpdaterHonorsOfficialChannelBoundary', 'legacyJavaNullInputsKeepTheirOriginalShapeAndBehavior', 'dispatchedInstallerWithUnknownOutcomeIsNotClaimedAgain', 'retainedStoreAndLanguageChangeDoNotRequeueOrReclaimInstallation', 'clearedPageCannotPublishOrClaimAnOldCallback', 'replacementCandidateWithSameDigestDoesNotInheritOldClaim', 'channelChangeAfterVerificationInvalidatesClaim', 'pendingInstallationBlocksAllNewPageWork', 'processRebuildReportsInterruptedVerificationWithoutReplayingIt', 'serviceRedeliveryQueuesOneDownloadAndCancelPreventsFurtherResume', 'permissionCallbacksConsumeOnlyTheirOwnGenerationAndKind', 'unknownPermissionReceiptRetiresItsTicketWithoutStartingAnything', 'serviceCancelInvalidatesPermissionEvenWhenCandidateAndApkAreUnchanged', 'oldInstallPermissionResultCannotConsumeOrCancelANewerSameCandidateRequest', 'rebuiltPageKeepsTheOriginalPermissionButAProcessMarkerCannotRecreateIt', 'processRebuildReportsInterruptedDownloadPermissionWithoutReplayingIt', 'oldVerificationCannotOverwriteFailureOrANewerExplicitRequest', 'exhaustedOrCorruptPermissionCounterNeverReusesAnOldSystemRequestCode', 'lostReadyReceiptObservesExistingApkWithoutRepeatingDownloadOrSkippingVerification']}
PACKAGE = 'com.deepseekharness.app.core.'


def readback(repo, mode, code):
    generated = list((repo/'app/build/generated').rglob('BuildConfig.java'))
    paths = [f for f in generated if f.parent.as_posix().endswith('/com/deepseekharness/app')]
    reports = repo/'app/build/test-results/testStandardDebugUnitTest'
    xmls = sorted(reports.glob('TEST-*.xml'))
    if not xmls:
        print(f'B368_IDENTITY_NO_XML={mode}', flush=True)
        assert code != 0
        return False
    config, = paths
    body = config.read_text(encoding='utf-8')
    expected = 'true' if mode == 'rebirth' else 'false'
    application_id = 'dsh.android.client.rebirth' if mode == 'rebirth' else 'io.github.sunsetrne.dsha'
    assert re.search(r'\bREBIRTH_CANDIDATE\s*=\s*'+expected+r'\s*;', body)
    assert re.search(r'\bAPPLICATION_ID\s*=\s*"'+re.escape(application_id)+r'"\s*;', body)
    counts = {}
    for file in xmls:
        assert file.resolve() == file and file.is_file() and file.stat().st_size <= 4*2**20
        raw = file.read_bytes()
        assert not re.search(rb'<!DOCTYPE|<!ENTITY', raw, re.I)
        suite = ET.fromstring(raw)
        cls = suite.get('name', '')
        assert cls.startswith(PACKAGE) and file.name == 'TEST-'+cls+'.xml'
        short = cls.removeprefix(PACKAGE)
        assert short in CASES and short not in counts
        tests = list(suite.iter('testcase'))
        assert all(t.get('classname') == cls for t in tests)
        assert sorted(t.attrib.get('name') for t in tests) == sorted(METHODS[short])
        if short == 'UpdateOwnershipTest':
            boundary, = [t for t in tests if t.attrib.get('name') == 'buildSelectedUpdaterHonorsOfficialChannelBoundary']
            status = 'PASS' if all(boundary.find(k) is None for k in ('failure','error','skipped')) else 'FAIL'
            print(f'B368_UPDATE_BOUNDARY={mode}:{status}',flush=True)
        failed = sum(t.find('failure') is not None or t.find('error') is not None for t in tests)
        for test in tests:
            failure = test.find('failure')
            if failure is None:
                failure = test.find('error')
            if failure is None:
                continue
            method = test.attrib.get('name','')
            if method not in METHODS[short]:
                continue
            exception = failure.attrib.get('type','')
            category=('ASSERTION' if 'Assertion' in exception else 'TIMEOUT' if 'Timeout' in exception else 'OTHER')
            frame = re.search(r'(?m)^\s*at '+re.escape(cls)+r'\.([A-Za-z0-9_$]+)\('+re.escape(short)+r'\.java:([1-9][0-9]{0,4})\)',failure.text or '')
            line=int(frame[2]) if frame else 0
            print(f'B368_UPDATE_FAILURE={mode}:{short}:{method}:{category}:{line}',flush=True)
        skipped = sum(t.find('skipped') is not None for t in tests)
        counts[short] = (len(tests), failed, skipped)
        print(f'B368_IDENTITY_CLASS={mode}:{short}:{len(tests)}:{failed}:{skipped}:{hashlib.sha256(raw).hexdigest()}', flush=True)
    complete = set(counts) == set(CASES) and all(total == CASES[k] and skipped == 0 for k,(total,_,skipped) in counts.items())
    no_failure = all(row[1] == 0 for row in counts.values())
    assert code != 0 or (complete and no_failure)
    assert not any(f.suffix in {'.apk','.aab','.apks','.dex'} for f in (repo/'app/build').rglob('*'))
    if not complete:
        print(f'B368_IDENTITY_INCOMPLETE={mode}', flush=True)
        return False
    assert no_failure == (code == 0)
    print(f'B368_IDENTITY_READBACK={mode}:{expected}', flush=True)
    return True


def main():
    path, gradle, commit, tree, *opts = sys.argv[1:]
    p = Path(path)
    assert __debug__ and p.resolve() == p and os.getuid() != 0 and socket.if_nameindex() == [(1, 'lo')]
    assert 'NoNewPrivs:\t1\n' in Path('/proc/self/status').read_text()
    assert not os.environ.get('DSHA_UNIT_TEST_STATE')
    assert not any(x in opts for x in ('-x','--tests','--dry-run','--continue'))
    os.environ['DSHA_ROBOLECTRIC_DEPENDENCY_DIR'] = str(p/'robo')
    py = [sys.executable,'-sB']
    rows = []
    for mode, dirname in (('rebirth','receiver'),('mod','mod-receiver')):
        repo = p/dirname
        git = lambda *a: sp.check_output(['git','--no-optional-locks','--no-replace-objects',*a],cwd=repo,timeout=20)
        assert git('rev-parse','HEAD','HEAD^{tree}').decode().split() == [commit,tree]
        assert not git('status','--porcelain=v1','--untracked-files=all') and not git('remote')
        assert not (repo/'app/build').exists()
        home = p/(mode+'-gradle-home'); home.mkdir()
        env = dict(os.environ,GRADLE_USER_HOME=str(home))
        lane = p/('compare-'+mode); lane.mkdir()
        flags = list(opts)
        if mode == 'mod':
            flags.remove('-Pdsha.identity=rebirth'); flags.remove('-Pdsha.rebirthCandidate=true')
            flags.extend(['-Pdsha.identity=mod','-Pdsha.rebirthCandidate=false'])
        targets = sum((['--tests',PACKAGE+k] for k in CASES),[])
        with (lane/'monitor.log').open('xb') as out:
            rc=sp.run(py+[p/'online_b368_compile_monitor.py',lane,gradle,*flags,'--offline','--rerun-tasks',
                       '--project-cache-dir',lane/'project-cache','-Pdsha.sourceOnlyTests=false',
                       'verifyDshaBuildConventions',':app:testStandardDebugUnitTest',*targets],
                      cwd=repo,env=env,stdout=out,stderr=sp.STDOUT,timeout=1050).returncode
        assert not git('status','--porcelain=v1','--untracked-files=all')
        print(f'B368_IDENTITY_EXIT={mode}:{rc}',flush=True)
        for line in (lane/'monitor.log').read_text().splitlines():
            if re.fullmatch(r'B368_COMPILE_(?:(?:ENTRY|MIN)_MIB=[0-9]{1,8}|RESOURCE_OR_TIME_STOP=true)',line):
                print(f'B368_IDENTITY_RESOURCE={mode}:{line.removeprefix("B368_COMPILE_").replace("=",":")}',flush=True)
        log=lane/'compile.log'
        if log.is_file():
            text=log.read_text(encoding='utf-8',errors='replace')
            for label,pattern in {'CACHE_MISS':r'available for offline mode','RESOLVER':r'Could not resolve|Could not find',
                                  'TEST_FAILURE':r'There were failing tests','CONVENTION':r'DSHA_(?:ANDROID_OPTIONS|RESOURCE_SOURCE_ROOT|EXPECTED_TEST_TASK)'}.items():
                if re.search(pattern,text):print(f'B368_IDENTITY_DIAG={mode}:{label}',flush=True)
        rows.append(readback(repo, mode, rc))
    if not all(rows):
        return 1
    print('B368_IDENTITY_COMPARISON=COMPLETE',flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
