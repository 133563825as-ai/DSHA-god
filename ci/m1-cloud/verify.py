"""Static candidate helper. Candidate admission is disabled in policy.json."""
import hashlib, json, os, pathlib, re, shutil, subprocess, sys, time
import urllib.request
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
 'jvm':[PREFIX+'contract.BuiltinModulesTest.'+n for n in [
   'registersAboutAsAlwaysOnCardWithBilingualTitle','ensureRegisteredIsIdempotentAndRestoresHiddenLayout']]+[
   PREFIX+'contract.ContributionCatalogTest.'+n for n in [
   'stableOrderMatchesLayoutAndByKindQueries','layoutLoadAndSaveFailuresAreFailClosed']],
 'about':[PREFIX+'ui.AboutFragmentTest.'+n for n in [
   'bundledLicensesAreListedAndRenderedWithReadableNames','pageTitleComesFromBuiltinAboutContribution',
   'hiddenAboutCardKeepsHostLicenseRows','clickingLicenseShowsDialogWithTitleAndBody',
   'missingLicenseAssetDegradesToTypedMessage','stoppedPageRetiresQueuedReadAndDismissesItsDialog']]}

def sha(path):
    h=hashlib.sha256()
    with pathlib.Path(path).open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''): h.update(block)
    return h.hexdigest()

def git(*args): return subprocess.check_output(['git',*args],text=True).strip()

def policy():
    p=json.loads((HERE/'policy.json').read_text())
    assert p['admitted'] is True, 'STATIC_DRAFT_NOT_ADMITTED'
    expected=os.environ['M1_EXPECTED_SHA']
    assert p['repository'] and p['ref'] and re.fullmatch('[a-f0-9]{40}',expected)
    assert os.environ['GITHUB_REPOSITORY']==p['repository']
    assert os.environ['GITHUB_REF']==p['ref']
    assert os.environ['GITHUB_SHA']==expected==git('rev-parse','HEAD')
    return dict(p,candidateSha=expected)

def candidate():
    p=policy()
    assert git('rev-list','--parents','-n','1','HEAD').split()==[p['candidateSha'],BASE]
    changed=set(git('diff','--name-only',BASE,'HEAD').splitlines())
    helpers=set(p['reviewedHelperPaths'])
    assert helpers=={'.github/workflows/m1-cloud.yml','ci/m1-cloud/verify.py',
        'ci/m1-cloud/gate.gradle','ci/m1-cloud/policy.json'}, 'no arbitrary helper prefix'
    assert changed==set(PATCHES)|helpers
    for name,pin in PATCHES.items(): assert sha(ROOT/name)==pin,name
    protected()
    # Helper hashes are bound by reviewed candidate commit and separate author review;
    # do not demand a self-referential SHA of policy.json inside that same file.
    assert not git('status','--porcelain','--untracked-files=all')

def protected():
    for name,pin in PROTECTED.items(): assert sha(ROOT/name)==pin,name

def sdk34():
    policy(); protected()
    lock=json.loads((ROOT/'tools/robolectric-sdk.lock.json').read_text())
    version='14-robolectric-10818077-i7'
    row=next(x for x in lock['jars'] if x['version']==version)
    assert row==dict(version=version,size=151142227,sha256='3fdac2d463f431c6b8d83b9d0ca739e795de75fffedee648c6761bd4bd864701')
    assert (lock['repository'],lock['group'],lock['artifact'])==('https://repo1.maven.org/maven2','org.robolectric','android-all-instrumented')
    name='android-all-instrumented-'+version+'.jar'
    directory=pathlib.Path(os.environ['DSHA_ROBOLECTRIC_DEPENDENCY_DIR']); directory.mkdir()
    target=directory/name
    url=lock['repository']+'/org/robolectric/android-all-instrumented/'+version+'/'+name
    # No alternate repository/ref. Reject redirects rather than broaden origin.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,*args,**kwargs): raise ValueError('SDK redirect refused')
    with urllib.request.build_opener(NoRedirect).open(url,timeout=60) as response, target.open('xb') as f:
        total=0
        for block in iter(lambda:response.read(1024*1024),b''):
            total+=len(block); assert total<=row['size']; f.write(block)
    assert target.stat().st_size==row['size'] and sha(target)==row['sha256']
    print(json.dumps(dict(sdk=34,url=url,bytes=total,sha256=sha(target),offline=True)))

def verify_xml(folder,expected,started):
    observed=[]
    for path in sorted(folder.glob('TEST-*.xml')):
        assert path.stat().st_mtime_ns>=started,path
        root=ET.parse(path).getroot()
        for case in root.iter('testcase'):
            assert not any(case.find(tag) is not None for tag in ('failure','error','skipped'))
            observed.append(case.attrib['classname']+'.'+case.attrib['name'])
    # Exact methods, no parametrized suffixes silently stripped, duplicates rejected.
    assert sorted(observed)==sorted(expected) and len(set(observed))==len(expected),observed
    return observed

def phase(name):
    p=policy(); protected(); assert name in ('compile','jvm','about')
    evidence=pathlib.Path(os.environ['M1_EVIDENCE']); evidence.mkdir(exist_ok=True)
    report=evidence/name; report.mkdir()  # Never overwrite previous phase evidence.
    reports=ROOT/'app/build/test-results/testStandardDebugUnitTest'
    html=ROOT/'app/build/reports/tests/testStandardDebugUnitTest'
    assert not reports.exists() and not html.exists(), 'previous Test reports must be moved, never cleaned'
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
    if name=='about':
        d=pathlib.Path(os.environ['DSHA_ROBOLECTRIC_DEPENDENCY_DIR'])
        files=list(d.iterdir()); assert len(files)==1 and sha(files[0])=='3fdac2d463f431c6b8d83b9d0ca739e795de75fffedee648c6761bd4bd864701'
    env=dict(os.environ,M1_PHASE=name,M1_METHODS=json.dumps(METHODS.get(name,[])))
    assert 'DSHA_UNIT_TEST_STATE' not in env
    # Source-only stays false through the existing project contract; no -x or rerun.
    (report/'invocation.json').write_text(json.dumps(dict(argv=argv,phase=name,commit=git('rev-parse','HEAD')),indent=2))
    started=time.time_ns()
    with (report/'gradle.log').open('xb') as log:
        child=subprocess.Popen(argv,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        timed=False
        try: code=child.wait(timeout=880)
        except subprocess.TimeoutExpired:
            import signal
            timed=True; os.killpg(child.pid,signal.SIGTERM)
            try: child.wait(timeout=5)
            except subprocess.TimeoutExpired: os.killpg(child.pid,signal.SIGKILL)
            code=child.wait(timeout=5)
    # Numeric exit survives later XML/log validation failures.
    temp=report/'exit.tmp'; temp.write_text(json.dumps(dict(exit=code,timedOut=timed)))
    os.replace(temp,report/'exit.json')
    # Emit bounded machine outcomes, never arbitrary project stdout/secrets.
    text=(report/'gradle.log').read_text(errors='replace')
    outcomes=[]
    for line in text.splitlines():
        if re.fullmatch(r'> Task :app:[A-Za-z0-9]+(?: (?:FAILED|UP-TO-DATE|SKIPPED|NO-SOURCE|FROM-CACHE))?',line):
            outcomes.append(line)
    failure=next((x for x in outcomes if x.endswith(' FAILED')),None)
    print('M1_PHASE_EXIT='+json.dumps(dict(phase=name,exit=code,timedOut=timed,
        firstRedactedFailureOutcome=failure,outcomes=outcomes[:54],rawEvidenceRetainedOnlyOnRunner=True)),flush=True)
    assert code==0 and not timed
    assert 'M1_CLOUD_GRAPH_ACCEPTED=' in text
    if name!='compile':
        exact='> Task '+target
        assert any(line==exact for line in text.splitlines()), 'Test not freshly executed'
        try:
            observed=verify_xml(reports,METHODS[name],started)
            (report/'xml-verification.json').write_text(json.dumps(dict(methods=observed,count=len(observed))))
            print('M1_XML_VERIFIED='+json.dumps(dict(phase=name,methods=observed,count=len(observed))),flush=True)
        finally:
            if reports.exists(): shutil.move(str(reports),str(report/'xml'))
            if html.exists(): shutil.move(str(html),str(report/'html'))
    protected()

if __name__=='__main__':
    command=sys.argv[1]
    if command=='candidate': candidate()
    elif command=='protected': policy(); protected()
    elif command=='sdk34': sdk34()
    elif command=='phase': phase(sys.argv[2])
    else: raise SystemExit('unknown command')
