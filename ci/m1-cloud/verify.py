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
    assert os.environ['GITHUB_REPOSITORY']=='133563825as-ai/DSHA-god'
    assert os.environ['GITHUB_REF']=='refs/heads/validate/m1-cloud-20261124'
    assert os.environ['GITHUB_EVENT_NAME']=='push'
    assert os.environ['GITHUB_RUN_ATTEMPT']=='1', 'one-time event: rerun refused'
    event=json.loads(pathlib.Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    head=git('rev-parse','HEAD')
    assert re.fullmatch('[a-f0-9]{40}',head)
    assert event['before']=='4c408269228756ed31e1c89381c5114dd02df1e7'
    assert event['after']==os.environ['GITHUB_SHA']==head
    assert event['ref']==os.environ['GITHUB_REF'] and not event.get('deleted')
    assert event.get('repository',{}).get('full_name')=='133563825as-ai/DSHA-god'
    return dict(candidateSha=head)

PARENT='4c408269228756ed31e1c89381c5114dd02df1e7'
CHANGED={'.github/workflows/m1-cloud.yml','.github/workflows/ci-fast.yml',
 '.github/workflows/docs-check.yml','ci/m1-cloud/verify.py','ci/m1-cloud/runner.py',
 'ci/m1-cloud/gate.gradle'}

def candidate():
    p=policy()
    def parents(commit):
        return [x[7:] for x in git('cat-file','-p',commit).splitlines() if x.startswith('parent ')]
    assert parents(p['candidateSha'])==[PARENT]
    assert parents(PARENT)==[BASE]
    assert set(git('diff','--name-only',PARENT,'HEAD').splitlines())==CHANGED
    for name,pin in PATCHES.items(): assert sha(ROOT/name)==pin,name
    protected()
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
    directory=pathlib.Path(os.environ['RUNNER_TEMP'])/'m1-sdk34'; directory.mkdir()
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
        records=[]
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
    elif command=='sdk34': sdk34()
    elif command=='phase': phase(sys.argv[2])
    elif command=='finalize': finalize_evidence()
    else: raise SystemExit('unknown command')
