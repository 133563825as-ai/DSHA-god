"""One-time narrow verification for the Rebirth first modular candidate."""
import hashlib, json, os, pathlib, re, shutil, subprocess, sys, time
import urllib.request, urllib.error
from email.utils import parsedate_to_datetime
import math
import xml.etree.ElementTree as ET
ROOT = pathlib.Path.cwd()
HERE = pathlib.Path(__file__).resolve().parent
BASE = 'f67e3b8bffcf8640e3864393827f31d71b9ec22f'
PATCHES = {
 'tools/prepare-backup-assets.py':'523a87feb2961f17c25467068bb15c334655f8bb38eb667f3d59df6ce40fdcca',
 'tools/test-backup-signed-builtin-files.py':'1bced95c20e7a34eb805cef329c717f137caf261e3e00d3d36c8d6d0fb39d672',
 'tools/prepare-runtime-descriptor.py':'8be243303a16fcd6c755d1996fac04f6e53bc09b205f9ee504bc79a24d1d0969',
 'build-logic/android.gradle':'3bbb725998ea4ad62321a21acd68fdbc040564950f93ea29cbb3812445fdd607'}
PROTECTED = {
 'app/src/main/assets/runtime-descriptor.json':'77251bc495ebb176302a0e35174aee03a6b7ebfd0947d1e6c004c41de7830b1d',
 'app/src/main/assets/managed-package-proofs.json':'db04ce9b0260a840e0205aefbc6862511dac77297dd9394a8366e9a3578f1d8b',
 'tools/robolectric-sdk.lock.json':'9e7380ed24ecccce360943f2b877008a1777b7734cc3361621f4e41645d6ef39'}
PREFIX='com.deepseekharness.app.'
METHODS={
 "jvm": [
  PREFIX+"contract.BuiltinModulesCompositionTest.duplicateAndPartialRegistrationAreRejectedLocally",
  PREFIX+"contract.BuiltinModulesCompositionTest.disableReenableChangesLeaseWithoutChangingLayout",
  PREFIX+"contract.ModuleRegistryTest.safeModeKeepsOnlyAlwaysOnAndHostRecoveryEntry",
  PREFIX+"contract.ContractLifecycleInteropTest.secondPassNullStillCleansEarlierContributionAndRouteBeforeThrowing",
  PREFIX+"ui.ThemeChangeRequestTest.successFailureOrExceptionFallbackCanCommitAtMostOnce",
  PREFIX+"util.EntryTargetTest.onlyAFreshLauncherEntryWithoutPendingNativeWorkOpensWeb",
  PREFIX+"ui.StorageDemoNavigationLeaseTest.stoppedPageCannotPublishAcrossRecreatedRoute"
 ],
 "about": [
  PREFIX+"ui.SettingsBuiltinModuleCompositionTest.productEntryUsesVisibleContributionAndExistingPreferences",
  PREFIX+"ui.SettingsBuiltinModuleCompositionTest.hideAndDisableAreIndependentAndRecreateKeepsChoice",
  PREFIX+"ui.SettingsBuiltinModuleCompositionTest.queuedAndLateRequestsCannotCrossGeneration",
  PREFIX+"ui.SettingsBuiltinModuleCompositionTest.closingPageRetiresOnlyItsRequests",
  PREFIX+"ui.SettingsBuiltinModuleCompositionTest.optionalFailureCannotPolluteLegalFallback",
  PREFIX+"ui.AboutFragmentTest.hiddenAboutCardKeepsHostLicenseRows",
  PREFIX+"ui.AboutFragmentTest.stoppedPageRetiresQueuedReadAndDismissesItsDialog",
  PREFIX+"ui.SettingsStorageNavigationTest.settingsClickAndSystemBackKeepAboutReachable",
  PREFIX+"ui.SettingsStorageNavigationTest.hiddenDemoCannotReopenFromRestoredRoute",
  PREFIX+"ui.SettingsStorageNavigationTest.singlePaneRecreateRetiresOldOwner"
 ]
}

def sha(path):
    h=hashlib.sha256()
    with pathlib.Path(path).open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''): h.update(block)
    return h.hexdigest()

def git(*args): return subprocess.check_output(['git',*args],text=True).strip()

def policy():
    assert os.environ['GITHUB_REPOSITORY']=='133563825as-ai/DSHA-god'
    assert os.environ['GITHUB_REF']=='refs/heads/validate/m1-cloud-20261124'
    assert os.environ['GITHUB_EVENT_NAME']=='push'
    assert os.environ['GITHUB_RUN_ATTEMPT']=='1', 'one-time event: rerun refused'
    event=json.loads(pathlib.Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    head=git('rev-parse','HEAD')
    assert re.fullmatch('[a-f0-9]{40}',head)
    assert event['before']==PARENT
    assert event['after']==os.environ['GITHUB_SHA']==head
    assert event['ref']==os.environ['GITHUB_REF'] and not event.get('deleted')
    assert event.get('repository',{}).get('full_name')=='133563825as-ai/DSHA-god'
    return dict(candidateSha=head)

PARENT='30672ce7fb0e3544e6de86f1af70dcb9d1fc95aa'
FIRST_MODULAR_JAVAC_FIX='700bfe52b54e60dd68811d9f1a28a3d7d46b0736'
FIRST_MODULAR_LAYOUT_FIX='adc10c916d5ae42f1fe4d6ace7a507460a10f1c5'
FIRST_MODULAR_ASSEMBLY='bfe715be654f5efd8553a55307f85ffe79e09e2c'
FIRST_MODULAR_BASE='477d70c8261b2ce78a90bdd9df709054dcf149fe'
HIDDEN_RECOVERY_PARENT='323a3ffa4763a104bbf880bad962c1c71ee299a8'
SDK_BUDGET_PARENT='756fc44b67f863ff2d46f479f9b354169e1c72dd'
FIXTURE_TEST_PARENT='78f1713cf4a96c4eb33dd619942f9905000d4af2'
COMBINED_PARENT='b3e2ebaf304552817e0e9c952ca0d1c50828cf49'
NAV_TEST_PARENT='b6b19fd36fb5956133358a953d8331d5d78b573d'
NAV_PARENT='9393dcc6bbc21d040490ea5a4dc00f9fb2bfd141'
SDK_WAIT_PARENT='e081cac90549708ce7c6bdb2c5f76115bb42e5af'
SLICE_PARENT='59a111106d382b5316b709b3fb2ade9929f00069'
GREEN_PARENT='66d76f1fa70eb7f8cf3cf38f141b766f2b1b80bc'
FIXTURE_PARENT='957794f01ec1411cdae0a08e9c3f75325f05e4e8'
SDK_RETRY_PARENT='09bbc007bd097727f558c11ea2961fdc70021b1b'
LINE_PARSER_PARENT='2511fb2ea44b9e85d701a15dbca1838fdf6fc56e'
DIAGNOSTIC_PARENT='eca2d17f1902d12c0bc5cdaf6b5da427c9615ea9'
PREVIOUS_RUN_PARENT='9d86874daea4bf0589eb1f4611b88d5789a3facb'
PREVIOUS_PARENT='4c408269228756ed31e1c89381c5114dd02df1e7'
RENAMED_FROM='app/src/test/java/com/deepseekharness/app/contract/BuiltinModulesTest.kt'
RENAMED_TO='app/src/test/java/com/deepseekharness/app/contract/BuiltinModulesCompositionTest.kt'
CHANGED={'.github/workflows/m1-cloud.yml', 'ci/m1-cloud/verify.py',
  'app/src/test/java/com/deepseekharness/app/ui/SettingsBuiltinModuleCompositionTest.java'}

def candidate():
    p=policy()
    def parents(commit):
        return [x[7:] for x in git('cat-file','-p',commit).splitlines() if x.startswith('parent ')]
    assert parents(p['candidateSha'])==[PARENT]
    assert parents(PARENT)==[FIRST_MODULAR_JAVAC_FIX]
    assert parents(FIRST_MODULAR_JAVAC_FIX)==[FIRST_MODULAR_LAYOUT_FIX]
    assert parents(FIRST_MODULAR_LAYOUT_FIX)==[FIRST_MODULAR_ASSEMBLY]
    assert parents(FIRST_MODULAR_ASSEMBLY)==[FIRST_MODULAR_BASE]
    assert parents(FIRST_MODULAR_BASE)==[HIDDEN_RECOVERY_PARENT]
    assert parents(HIDDEN_RECOVERY_PARENT)==[SDK_BUDGET_PARENT]
    assert parents(SDK_BUDGET_PARENT)==[FIXTURE_TEST_PARENT]
    assert parents(FIXTURE_TEST_PARENT)==[COMBINED_PARENT]
    assert parents(COMBINED_PARENT)==[NAV_TEST_PARENT]
    assert parents(NAV_TEST_PARENT)==[NAV_PARENT]
    assert parents(NAV_PARENT)==[SDK_WAIT_PARENT]
    assert parents(SDK_WAIT_PARENT)==[SLICE_PARENT]
    assert parents(SLICE_PARENT)==[GREEN_PARENT]
    assert parents(GREEN_PARENT)==[FIXTURE_PARENT]
    assert parents(FIXTURE_PARENT)==[SDK_RETRY_PARENT]
    assert parents(SDK_RETRY_PARENT)==[LINE_PARSER_PARENT]
    assert parents(LINE_PARSER_PARENT)==[DIAGNOSTIC_PARENT]
    assert parents(DIAGNOSTIC_PARENT)==[PREVIOUS_RUN_PARENT]
    assert parents(PREVIOUS_RUN_PARENT)==[PREVIOUS_PARENT]
    assert parents(PREVIOUS_PARENT)==[BASE]
    assert set(git('diff','--no-renames','--name-only',PARENT,'HEAD').splitlines())==CHANGED
    assert git('rev-parse',FIRST_MODULAR_ASSEMBLY+':'+RENAMED_FROM)==git('rev-parse','HEAD:'+RENAMED_TO)
    for name,pin in PATCHES.items(): assert sha(ROOT/name)==pin,name
    protected()
    assert sha(ROOT/ABOUT_SOURCE)==ABOUT_SOURCE_SHA256
    assert not git('status','--porcelain','--untracked-files=all')

def protected():
    for name,pin in PROTECTED.items(): assert sha(ROOT/name)==pin,name

def sdk_open(opener,url,deadline):
    # Three sequential GETs at <=60s each + <=300s backoff fit the 480s soft budget.
    # The deadline is checked between I/O calls, not a transfer-wide hard timeout.
    # Actions enforces the independent 540s hard step window (including stalled I/O).
    waited=0
    for attempt in range(1,4):
        remaining=deadline-time.monotonic()
        if remaining<=0:raise TimeoutError('SDK time budget exhausted')
        try:return opener.open(url,timeout=min(60,remaining))
        except urllib.error.HTTPError as exc:
            if exc.code!=429:raise
            header=exc.headers.get('Retry-After')
            exc.close()
            if attempt==3:
                print(json.dumps(dict(sdk=34,status=429,attempt=attempt,delaySeconds=0,state='ATTEMPTS_EXHAUSTED')),flush=True)
                raise
            if header is None:delay=5
            elif re.fullmatch(r'[0-9]{1,9}',header):delay=int(header)
            else:
                try:
                    date=parsedate_to_datetime(header)
                    if date.tzinfo is None:raise ValueError()
                    delay=max(0,math.ceil(date.timestamp()-time.time()))
                except (ValueError,TypeError,OverflowError):
                    raise ValueError('SDK Retry-After invalid') from None
            # Never shorten a server delay to force another request.
            if delay>300-waited or delay+60>deadline-time.monotonic():
                print(json.dumps(dict(sdk=34,status=429,attempt=attempt,delaySeconds=0,state='RETRY_BUDGET_REFUSED')),flush=True)
                raise
            print(json.dumps(dict(sdk=34,status=429,attempt=attempt,delaySeconds=delay,state='WAIT')),flush=True)
            time.sleep(delay);waited+=delay

def sdk34():
    policy(); protected()
    lock=json.loads((ROOT/'tools/robolectric-sdk.lock.json').read_text())
    version='14-robolectric-10818077-i7'
    row=next(x for x in lock['jars'] if x['version']==version)
    assert row==dict(version=version,size=151142227,sha256='3fdac2d463f431c6b8d83b9d0ca739e795de75fffedee648c6761bd4bd864701')
    assert (lock['repository'],lock['group'],lock['artifact'])==('https://repo1.maven.org/maven2','org.robolectric','android-all-instrumented')
    name='android-all-instrumented-'+version+'.jar'
    directory=pathlib.Path(os.environ['RUNNER_TEMP'])/'m1-sdk34'; directory.mkdir()
    target=directory/name
    url=lock['repository']+'/org/robolectric/android-all-instrumented/'+version+'/'+name
    # No alternate repository/ref. Reject redirects rather than broaden origin.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,*args,**kwargs): raise ValueError('SDK redirect refused')
    deadline=time.monotonic()+480
    with sdk_open(urllib.request.build_opener(NoRedirect),url,deadline) as response, target.open('xb') as f:
        total=0
        for block in iter(lambda:response.read(1024*1024),b''):
            assert time.monotonic()<=deadline, 'SDK time budget exhausted'
            total+=len(block); assert total<=row['size']; f.write(block)
    assert time.monotonic()<=deadline, 'SDK time budget exhausted'
    assert target.stat().st_size==row['size'] and sha(target)==row['sha256']
    print(json.dumps(dict(sdk=34,url=url,bytes=total,sha256=sha(target),offline=True)))

def verify_xml(folder,expected,started):
    observed=[]
    for path in sorted(folder.glob('TEST-*.xml')):
        assert path.stat().st_mtime_ns>=started,path
        assert path.stat().st_size<=2*1024*1024, 'XML byte limit'
        root=ET.parse(path).getroot()
        assert root.tag=='testsuite'
        assert all(int(root.get(k,'0'))==0 for k in ('failures','errors','skipped'))
        assert int(root.get('tests','-1'))==len(list(root.iter('testcase')))
        for case in root.iter('testcase'):
            assert not any(case.find(tag) is not None for tag in ('failure','error','skipped'))
            observed.append(case.attrib['classname']+'.'+case.attrib['name'])
    # Exact methods, no parametrized suffixes silently stripped, duplicates rejected.
    assert sorted(observed)==sorted(expected) and len(set(observed))==len(expected),observed
    return observed

# Source coordinates are frozen, not inferred from arbitrary exception messages.
ABOUT_SOURCE='app/src/test/java/com/deepseekharness/app/ui/AboutFragmentTest.java'
ABOUT_SOURCE_SHA256='a7f4ce17820b85498c7fa60acf1667f4085ce9a6dd93699b059a14d6c18a7578'
ABOUT_METHOD='stoppedPageRetiresQueuedReadAndDismissesItsDialog'
ABOUT_STAGES={
 222:'ABOUT_STOP_SETUP',223:'ABOUT_STOP_QUEUE_INITIAL',224:'ABOUT_STOP_SNAPSHOT',
 225:'ABOUT_STOP_INITIAL_STOP',226:'ABOUT_STOP_DRAIN_STALE',
 227:'ABOUT_STOP_NO_NEW_DIALOG',228:'ABOUT_STOP_RESTART',229:'ABOUT_STOP_QUEUE_REOPEN',
 230:'ABOUT_STOP_DRAIN_REOPEN',231:'ABOUT_STOP_CAPTURE_REOPEN',
 232:'ABOUT_STOP_REOPEN_NONNULL',233:'ABOUT_STOP_REOPEN_SHOWING',
 234:'ABOUT_STOP_DIALOG_STOP',235:'ABOUT_STOP_DIALOG_DISMISSED',236:'ABOUT_STOP_FINAL_RESTART'}

def about_failure_stage(case):
    # Untrusted stack text is a location hint only, never proof of root cause.
    result=dict(code='ABOUT_STOP_LOCATION_UNKNOWN',sourceMatched=False,
        attribution='untrusted-stack-location-not-causal-proof')
    if (case.get('classname'),case.get('name'))!=(PREFIX+'ui.AboutFragmentTest',ABOUT_METHOD):
        return None
    if not any(case.find(tag) is not None for tag in ('failure','error')):return None
    if sha(ROOT/ABOUT_SOURCE)!=ABOUT_SOURCE_SHA256:return result
    result['sourceMatched']=True
    nodes=[case.find(tag) for tag in ('failure','error') if case.find(tag) is not None]
    # Do not echo type/message/body/path/line/unknown stage, including malicious inputs.
    if len(nodes)!=1:return result
    text=nodes[0].text or ''
    if len(text)>64*1024:return result
    frame=r'[ \t]+at com\.deepseekharness\.app\.ui\.AboutFragmentTest\.'+ABOUT_METHOD+r'\(AboutFragmentTest\.java:([0-9]{1,4})\)'
    coordinates=[int(m.group(1)) for line in text.splitlines()
        if (m:=re.fullmatch(frame,line))]
    if len(coordinates)==1:result['code']=ABOUT_STAGES.get(coordinates[0],result['code'])
    return result

NAV_SOURCE='app/src/test/java/com/deepseekharness/app/ui/SettingsStorageNavigationTest.java'
NAV_SOURCE_SHA256='78ba7754b593ecfbe42b13542759abf0a3c9fd97f1be09c1fa1fdf19cc4e10b1'
NAV_CLASS=PREFIX+'ui.SettingsStorageNavigationTest'
NAV_METHODS=('settingsClickAndSystemBackKeepAboutReachable','singlePaneRecreateRetiresOldOwner',
    'hiddenDemoCannotReopenFromRestoredRoute','hiddenSettingsEntryStillOpensLegalAbout')
# Exact member and in-file numeric coordinates only; neither arbitrary frame nor message is exported.
NAV_LOCATIONS={
 'setup':(82,100,'NAV_SETUP_LOCATION'),
 'teardown':(101,110,'NAV_TEARDOWN_LOCATION'),
 'registry':(179,179,'NAV_REGISTRY_LOCATION'),
 'current':(180,180,'NAV_CURRENT_FRAGMENT_LOCATION'),
 'drain':(181,184,'NAV_LIFECYCLE_DRAIN_LOCATION'),
 'clickDemo':(185,188,'NAV_DEMO_BUTTON_LOCATION'),
 'systemBack':(189,192,'NAV_SYSTEM_BACK_LOCATION'),
 'openAbout':(193,196,'NAV_ABOUT_BUTTON_LOCATION'),
 'assertLegalAbout':(197,202,'NAV_LEGAL_ASSERTION_LOCATION'),
 'clickText':(203,207,'NAV_BUTTON_LOCATION'),
 'visibleText':(208,216,'NAV_VIEW_LOOKUP_LOCATION'),
 'words':(217,223,'NAV_VIEW_TEXT_LOCATION'),
 NAV_METHODS[0]:(112,121,'NAV_CLICK_BACK_TEST_LOCATION'),
 NAV_METHODS[1]:(123,147,'NAV_RECREATE_TEST_LOCATION'),
 NAV_METHODS[2]:(149,162,'NAV_HIDDEN_RESTORE_TEST_LOCATION'),
 NAV_METHODS[3]:(164,177,'NAV_HIDDEN_ABOUT_TEST_LOCATION')}
NAV_NESTED_LOCATIONS={
 ('App','harnessController'):(44,46,'NAV_REAL_CONTROLLER_BOUNDARY_LOCATION'),
 ('Host','onCreate'):(49,55,'NAV_HOST_CREATE_LOCATION'),
 ('ControllerConfig','get'):(61,63,'NAV_CONTROLLER_SHADOW_LOCATION'),
 ('ControllerConfig','config'):(64,64,'NAV_CONFIG_SHADOW_LOCATION')}
NAV_FAILURE_TYPES={
 'java.lang.AssertionError':'ASSERTION',
 'java.lang.NullPointerException':'NULL_POINTER',
 'java.lang.IllegalStateException':'ILLEGAL_STATE',
 'java.lang.IllegalArgumentException':'ILLEGAL_ARGUMENT',
 'android.view.InflateException':'INFLATE',
 'android.content.res.Resources$NotFoundException':'RESOURCE_NOT_FOUND',
 'java.lang.NoClassDefFoundError':'CLASS_NOT_FOUND',
 'java.lang.RuntimeException':'RUNTIME',
 'org.junit.internal.runners.model.MultipleFailureException':'MULTIPLE_FAILURES'}

def navigation_failure_stage(case):
    identity=(case.get('classname'),case.get('name'))
    if identity[0]!=NAV_CLASS or identity[1] not in NAV_METHODS:return None
    nodes=[case.find(tag) for tag in ('failure','error') if case.find(tag) is not None]
    if not nodes:return None
    result=dict(code='NAV_LOCATION_UNKNOWN',sourceMatched=False,failureType='UNKNOWN',
        attribution='untrusted-stack-location-not-causal-proof')
    try:
        if sha(ROOT/NAV_SOURCE)!=NAV_SOURCE_SHA256:return result
    except OSError:return result
    result['sourceMatched']=True
    if len(nodes)!=1:return result
    node=nodes[0]
    result['failureType']=NAV_FAILURE_TYPES.get(node.get('type'),'UNKNOWN')
    text=node.text or ''
    if len(text)>64*1024:return result
    frame=(r'[ \t]+at com\.deepseekharness\.app\.ui\.SettingsStorageNavigationTest'
        r'(?:\$(App|Host|ControllerConfig|MemoryConfig))?\.([A-Za-z][A-Za-z0-9]*)'
        r'\(SettingsStorageNavigationTest\.java:([0-9]{1,4})\)')
    # Take only the first recognized authored frame. A deeper caller is not a root-cause claim.
    for line in text.splitlines():
        match=re.fullmatch(frame,line)
        if not match:continue
        nested,member,coordinate=match.groups()
        location=(NAV_NESTED_LOCATIONS.get((nested,member)) if nested else NAV_LOCATIONS.get(member))
        if location and location[0]<=int(coordinate)<=location[1]:
            result['code']=location[2]
            break
    return result

THEME_SOURCE='app/src/test/java/com/deepseekharness/app/ui/SettingsBuiltinModuleCompositionTest.java'
THEME_SOURCE_SHA256='f8e9d94a90a3b74b4a87de5200ce0a6038c37b426ce796b030e4e6666672155a'
THEME_CLASS=PREFIX+'ui.SettingsBuiltinModuleCompositionTest'
THEME_METHOD='productEntryUsesVisibleContributionAndExistingPreferences'
THEME_STAGES={88:'REGISTRY_IDENTITY',89:'VISIBLE_CONTRIBUTION',90:'ABOUT_DESCRIPTOR',
 91:'DEMO_DEFAULT_ABSENT',92:'SETTINGS_CREATE',93:'ENTRY_VISIBILITY',94:'THEME_PAGE_OPEN_OR_CLOSE',
 95:'SWATCH_LOOKUP',96:'SWATCH_PRESENT',98:'SWATCH_CLICK',99:'PALETTE_SAVE',
 101:'THEME_RECREATE_QUEUE',102:'THEME_ACTIVITY_RECREATED',
 104:'SETTINGS_SUMMARY',105:'HOST_RECREATE',106:'PALETTE_AFTER_RECREATE'}

def theme_failure_stage(case):
    if (case.get('classname'),case.get('name'))!=(THEME_CLASS,THEME_METHOD): return None
    nodes=[case.find(k) for k in ('failure','error') if case.find(k) is not None]
    if not nodes: return None
    out=dict(code='THEME_LOCATION_UNKNOWN', sourceMatched=sha(ROOT/THEME_SOURCE)==THEME_SOURCE_SHA256,
        failureType='UNKNOWN',attribution='untrusted-stack-location-not-causal-proof')
    if len(nodes)!=1 or not out['sourceMatched']: return out
    out['failureType']=NAV_FAILURE_TYPES.get(nodes[0].get('type'),'UNKNOWN')
    text=nodes[0].text or ''
    if len(text)>64*1024: return out
    frame=re.compile(r'[ \t]+at '+re.escape(THEME_CLASS+'.'+THEME_METHOD)+
        r'\(SettingsBuiltinModuleCompositionTest\.java:([0-9]{1,4})\)')
    rows=[int(m.group(1)) for line in text.splitlines() if (m:=frame.fullmatch(line))]
    if len(rows)==1: out['code']=THEME_STAGES.get(rows[0],out['code'])
    return out

def metadata_diagnostics():
    # Trusted constant roles only: never echo paths or digest data from log text.
    roles={
      'dsh-runtime-metadata':('app/src/main/assets/dsh-runtime.inputs.json',11824,
        'afd49561b67cb5d23ca844a4d8b9dc1f0f61b2977412562f20684d777a8ddce0'),
      'ubuntu-tools-metadata':('app/src/main/assets/ubuntu-tools.inputs.json',592,
        'b5f1a66c66ee8ece40b98d673557071b0dd8bb455cf731ca33c21d9b563c32a2')}
    result=[]
    for role,(relative,size,pin) in roles.items():
        row=dict(role=role,reference='A5-staged-metadata-not-a-replacement-pin',
            expectedBytes=size,expectedSha256=pin,actualBytes=None,actualSha256=None,status='UNKNOWN')
        try:
            path=ROOT/relative
            if path.is_symlink() or not path.is_file() or path.stat().st_size>64*1024:
                row['status']='MISSING_OR_NONREGULAR_OR_SIZE_LIMIT'
            else:
                row['actualBytes']=path.stat().st_size;row['actualSha256']=sha(path)
                row['status']='MATCH' if (row['actualBytes']==size and row['actualSha256']==pin) else 'DIFFERENT'
        except Exception:row['status']='READ_FAILED_REDACTED'
        result.append(row)
    return result

def handoff_metadata(restore=False):
    # GITHUB_SHA is frozen by policy(), never read metadata from the handoff as truth.
    p=policy()
    rows=metadata_diagnostics()
    blobs=[]
    for row,relative in zip(rows,('app/src/main/assets/dsh-runtime.inputs.json',
                                'app/src/main/assets/ubuntu-tools.inputs.json')):
        blob=subprocess.check_output(['git','cat-file','blob',p['candidateSha']+':'+relative])
        assert len(blob)==row['expectedBytes'] and hashlib.sha256(blob).hexdigest()==row['expectedSha256']
        path=ROOT/relative
        assert all(not part.is_symlink() for part in (path,*path.parents))
        assert path.is_file() and path.stat().st_size<=64*1024
        blobs.append((path,blob))
    if restore:
        # The original handoff action must succeed first; write only the two metadata.
        for path,blob in blobs:path.write_bytes(blob)
    rows=metadata_diagnostics()
    assert all(row['status']=='MATCH' for row in rows), 'M1_CANDIDATE_METADATA_MISMATCH'
    if restore:
        # Metadata is not an archive pin. Re-run the existing archive/size/pin checks.
        import importlib.util
        spec=importlib.util.spec_from_file_location('m1_ci_assets',ROOT/'tools/ci-assets.py')
        assets=importlib.util.module_from_spec(spec);spec.loader.exec_module(assets)
        assets.verify_sources()
        protected()
    print('M1_HANDOFF_METADATA='+json.dumps(dict(restored=restore,metadata=rows,
        originalArchivePinsRechecked=restore)))

def phase(name):
    from runner import run_phase
    policy(); protected(); assert name in ('compile','jvm','about')
    evidence=pathlib.Path(os.environ['RUNNER_TEMP'])/'m1-evidence'
    evidence.mkdir(exist_ok=True)
    report=evidence/name; report.mkdir()
    reports=ROOT/'app/build/test-results/testStandardDebugUnitTest'
    html=ROOT/'app/build/reports/tests/testStandardDebugUnitTest'
    assert not reports.exists() and not html.exists(), 'old Test reports'
    target=':app:compileStandardDebugUnitTestJavaWithJavac' if name=='compile' else ':app:testStandardDebugUnitTest'
    argv=['/bin/bash','./gradlew','--no-daemon','--no-parallel','--max-workers=1',
        '--no-build-cache','--no-configuration-cache','--dependency-verification=strict',
        '--console=plain','--info','-I',str(HERE/'gate.gradle'),
        '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8',
        '-Porg.gradle.java.installations.auto-download=false','-Pandroid.builder.sdkDownload=false',
        '-Pdsha.identity=rebirth','-Pdsha.rebirthCandidate=true','-Pdsha.unsignedPackage=true',
        '-Pdsha.rust.mode=off','-Pkotlin.compiler.execution.strategy=in-process',target]
    if name!='compile':
        for method in METHODS[name]: argv+=['--tests',method]
    sdk=pathlib.Path(os.environ['RUNNER_TEMP'])/'m1-sdk34'
    if name=='about':
        files=list(sdk.iterdir()); assert len(files)==1 and sha(files[0])=='3fdac2d463f431c6b8d83b9d0ca739e795de75fffedee648c6761bd4bd864701'
    env=dict(os.environ,M1_PHASE=name,M1_METHODS=json.dumps(METHODS.get(name,[])),
        DSHA_ROBOLECTRIC_DEPENDENCY_DIR=str(sdk))
    assert 'DSHA_UNIT_TEST_STATE' not in env
    # Run with a clean execution env; do not send Actions tokens to Gradle.
    env={k:v for k,v in env.items() if not any(t in k.upper() for t in ('TOKEN','SECRET','PASSWORD','CREDENTIAL'))}
    started=time.time_ns()
    result=run_phase(argv,env,report,target)
    safe=pathlib.Path(os.environ['RUNNER_TEMP'])/'m1-safe-evidence'; safe.mkdir(exist_ok=True)
    phase_safe=safe/name; phase_safe.mkdir()
    accepted=False; xml_error=None
    try:
        assert result['exit']==0 and not result['unknown'] and not result['stopReason']
        assert result['graphAccepted'] and result['kotlinStrategy'] in ('in-process','not-observed-no-kotlin-action')
        if name!='compile':
            assert result['testExecuted'], 'Test not freshly executed'
            observed=verify_xml(reports,METHODS[name],started)
            accepted=True
        else: accepted=True
    except Exception as exc:
        xml_error=type(exc).__name__  # Never arbitrary diagnostic content.
    finally:
        # Export only structural XML facts, never system-out/err/properties or bodies.
        records=[]; about_stage=None; navigation_stages={}; theme_stage=None
        if name!='compile' and reports.exists():
            for path in sorted(reports.glob('TEST-*.xml'))[:10]:
                if path.stat().st_size>2*1024*1024: xml_error='XmlSizeLimit'; accepted=False; continue
                try:
                    tree=ET.parse(path).getroot()
                    for case in tree.iter('testcase'):
                        cls=case.get('classname',''); method=case.get('name','')
                        if cls+'.'+method not in METHODS[name]:
                            records.append(dict(status='UNEXPECTED_IDENTITY_REDACTED')); continue
                        status=next((x for x in ('failure','error','skipped') if case.find(x) is not None),'passed')
                        records.append(dict(classname=cls,name=method,status=status))
                        if name=='about':
                            found_theme=theme_failure_stage(case)
                            if found_theme is not None:
                                theme_stage=found_theme if theme_stage is None else dict(code='THEME_DUPLICATE_REDACTED')
                            nav_stage=navigation_failure_stage(case)
                            if nav_stage is not None:
                                navigation_stages[method]=(nav_stage if method not in navigation_stages else dict(
                                    code='NAV_DUPLICATE_REDACTED',sourceMatched=False,failureType='UNKNOWN',
                                    attribution='untrusted-stack-location-not-causal-proof'))
                            stage=about_failure_stage(case)
                            if stage is not None:
                                about_stage=stage if about_stage is None else dict(
                                    code='ABOUT_STOP_DUPLICATE_REDACTED',sourceMatched=False,
                                    attribution='untrusted-stack-location-not-causal-proof')
                    if len(records)>20: records=records[:20]; accepted=False;xml_error='XmlCaseLimit'
                except Exception: xml_error='XmlParseFailure';accepted=False
            shutil.move(str(reports),str(report/'raw-xml'))
        if html.exists(): shutil.move(str(html),str(report/'raw-html'))
        if name!='compile':
            suites=ET.Element('testsuites')
            for cls in sorted({x['classname'] for x in records if 'classname' in x}):
                cases=[x for x in records if x.get('classname')==cls]
                suite=ET.SubElement(suites,'testsuite',name=cls,tests=str(len(cases)),
                    failures=str(sum(x['status']=='failure' for x in cases)),
                    errors=str(sum(x['status']=='error' for x in cases)),
                    skipped=str(sum(x['status']=='skipped' for x in cases)))
                for x in cases:
                    case=ET.SubElement(suite,'testcase',classname=cls,name=x['name'])
                    if x['status']!='passed':ET.SubElement(case,x['status'])
            ET.ElementTree(suites).write(phase_safe/'filtered-junit.xml',encoding='utf-8',xml_declaration=True)
        terminal=dict(result,accepted=accepted,xmlCheckError=xml_error,xmlCases=records,
            aboutFailureStage=about_stage,navigationFailureStages=navigation_stages,themeFailureStage=theme_stage,
            failedPhaseMetadata=(metadata_diagnostics() if not accepted else []),
            argv=argv,commit=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],
            runAttempt=os.environ['GITHUB_RUN_ATTEMPT'],phase=name,
            rawXmlUploaded=False,rawLogUploaded=False,filteredXmlOnly=True,artifactVisibility='public-repository')
        (phase_safe/'result.json').write_text(json.dumps(terminal,indent=2)+'\n')
        print('M1_PHASE_RESULT='+json.dumps(terminal),flush=True)
    assert accepted,'M1_PHASE_NOT_ACCEPTED'
    protected()

def finalize_evidence():
    # Missing result is UNKNOWN/not reached, never invented numeric exit/PASS.
    root=pathlib.Path(os.environ['RUNNER_TEMP'])/'m1-safe-evidence'
    root.mkdir(exist_ok=True)
    for name in ('compile','jvm','about'):
        folder=root/name;folder.mkdir(exist_ok=True)
        path=folder/'result.json'
        if not path.exists():
            path.write_text(json.dumps(dict(exit=None,unknown=True,accepted=False,
                status='NO_TERMINAL_RECEIPT_OR_PHASE_NOT_REACHED',phase=name,
                commit=os.environ.get('GITHUB_SHA'),runId=os.environ.get('GITHUB_RUN_ID'),
                runAttempt=os.environ.get('GITHUB_RUN_ATTEMPT'),argv=None)))

if __name__=='__main__':
    command=sys.argv[1]
    if command=='candidate': candidate()
    elif command=='protected': policy(); protected()
    elif command=='metadata-checkout': handoff_metadata()
    elif command=='metadata-restore': handoff_metadata(restore=True)
    elif command=='sdk34': sdk34()
    elif command=='phase': phase(sys.argv[2])
    elif command=='finalize': finalize_evidence()
    else: raise SystemExit('unknown command')
