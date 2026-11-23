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
    assert event['before']==PARENT
    assert event['after']==os.environ['GITHUB_SHA']==head
    assert event['ref']==os.environ['GITHUB_REF'] and not event.get('deleted')
    assert event.get('repository',{}).get('full_name')=='133563825as-ai/DSHA-god'
    return dict(candidateSha=head)

PARENT='eca2d17f1902d12c0bc5cdaf6b5da427c9615ea9'
PREVIOUS_RUN_PARENT='9d86874daea4bf0589eb1f4611b88d5789a3facb'
PREVIOUS_PARENT='4c408269228756ed31e1c89381c5114dd02df1e7'
CHANGED={'.github/workflows/m1-cloud.yml','ci/m1-cloud/verify.py'}

def candidate():
    p=policy()
    def parents(commit):
        return [x[7:] for x in git('cat-file','-p',commit).splitlines() if x.startswith('parent ')]
    assert parents(p['candidateSha'])==[PARENT]
    assert parents(PARENT)==[PREVIOUS_RUN_PARENT]
    assert parents(PREVIOUS_RUN_PARENT)==[PREVIOUS_PARENT]
    assert parents(PREVIOUS_PARENT)==[BASE]
    assert set(git('diff','--name-only',PARENT,'HEAD').splitlines())==CHANGED
    for name,pin in PATCHES.items(): assert sha(ROOT/name)==pin,name
    protected()
    assert sha(ROOT/ABOUT_SOURCE)==ABOUT_SOURCE_SHA256
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

# Source coordinates are frozen, not inferred from arbitrary exception messages.
ABOUT_SOURCE='app/src/test/java/com/deepseekharness/app/ui/AboutFragmentTest.java'
ABOUT_SOURCE_SHA256='0d8a5764cd5d4ac9d2f52b41f16608b92985c975fc17a981150050b8ea5b49c7'
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
        records=[]; about_stage=None
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
            aboutFailureStage=about_stage,
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
