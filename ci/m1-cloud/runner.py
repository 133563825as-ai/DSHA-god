"""Linux phase owner: bounded private log, sanitized output, owned cleanup only."""
import ctypes, json, os, pathlib, re, select, signal, subprocess, time
LIMIT=8*1024*1024

# Only exact frozen prepare-standard-assets.py terminal messages are publishable.
DIAGNOSTICS = {
 '网页兼容代码与依赖锁不一致，请运行 tools/prepare-web-compat.mjs':('WEB_COMPAT_INPUT_MISMATCH','web-compat'),
 '缺少锁定的新版 dsh-runtime.bin；请先运行 tools/build-dsh-runtime.py，不能发布旧版 dsh':('RUNTIME_ARCHIVE_MISSING','dsh-runtime'),
 'dsh 离线运行时与当前补丁/依赖锁不一致，请重新生成 dsh-runtime.bin':('RUNTIME_RECIPE_OR_ARCHIVE_MISMATCH','dsh-runtime'),
 'Ubuntu 离线基础工具与依赖锁不一致，请运行 tools/prepare-ubuntu-tools.py':('UBUNTU_RECIPE_OR_ARCHIVE_MISMATCH','ubuntu-tools'),
 'gh 固定输入与 tools/gh-cli/packages.lock.json 不一致，请运行 tools/prepare-gh-cli.py':('GH_ARCHIVE_MISMATCH','gh-cli'),
 '受管 gh 安装项与 tools/gh-cli/packages.lock.json 不一致':('GH_INSTALL_MISMATCH','gh-cli'),
 'Ubuntu 基础包状态已变化，必须重新解析离线工具依赖':('ROOTFS_BASE_STATUS_MISMATCH','rootfs-status')}

class FirstDiagnostic:
    def __init__(self):
        self.active=False; self.first=None; self.truncated=False
    def feed(self,line):
        if line==b'Traceback (most recent call last):': self.active=True; return
        if not self.active or self.first is not None:return
        # Entire line <=1024, exact exception class delimiter; no arbitrary class echo.
        if len(line)>1024:self.truncated=True;return
        text=line.decode('utf-8',errors='replace')
        match=re.fullmatch(r'(ValueError|FileNotFoundError|PermissionError|JSONDecodeError|OSError|RuntimeError|AssertionError|KeyError|TypeError): (.*)',text)
        if not match:return
        cls,message=match.groups()
        code,role=DIAGNOSTICS.get(message,('UNKNOWN_REDACTED','unknown'))
        if cls!='ValueError':code,role='UNKNOWN_REDACTED','unknown'
        self.first=dict(exceptionClass=cls,errorCode=code,objectRole=role,
            diagnosticLine=(cls+': '+message) if code!='UNKNOWN_REDACTED' else '[REDACTED_UNKNOWN_DIAGNOSTIC]',
            truncated=self.truncated,attribution='untrusted-subprocess-exact-text-not-proof')
    def result(self):
        return self.first or dict(exceptionClass='unknown',errorCode='UNKNOWN_REDACTED',
            objectRole='unknown',diagnosticLine='[REDACTED_OR_ABSENT_DIAGNOSTIC]',
            truncated=self.truncated,attribution='untrusted-subprocess-exact-text-not-proof')

class PregraphDiagnostic:
    """Finite untrusted Gradle failure hints; never echo matched text or infer cause."""
    REPOS=(('https://repo.maven.apache.org/maven2/','CENTRAL'),('https://repo1.maven.org/maven2/','CENTRAL'),
           ('https://dl.google.com/dl/android/maven2/','GOOGLE'),('https://plugins.gradle.org/m2/','PLUGIN_PORTAL'),
           ('https://jitpack.io/','JITPACK'),('https://maven.mozilla.org/maven2/','MOZILLA'))
    def __init__(self, metadata=None):
        self.active=False;self.codes=[];self.requests=[];self.catalog=None
        self.metadata=metadata or pathlib.Path(__file__).resolve().parents[2]/'gradle/verification-metadata.xml'
    def request(self, status, url):
        # Attribute the URL on THIS error only. Emit literals or trusted repository metadata, never log text.
        repo='UNKNOWN'; component=None
        for prefix,label in self.REPOS:
            if url and url.startswith(prefix):
                repo=label; rel=url[len(prefix):];break
        if repo!='UNKNOWN' and re.fullmatch(r'[A-Za-z0-9_./+\-]+',rel) and all(p not in ('','.','..') for p in rel.split('/')):
            if self.catalog is None:
                import xml.etree.ElementTree as ET
                self.catalog={}
                try:
                    # Derive publishable labels only from the reviewed metadata bytes.
                    import hashlib
                    data=self.metadata.read_bytes()
                    if hashlib.sha256(data).hexdigest()!='ecd1db829722c4c414e180e4839670e6fe905fb89d171bc53a1a4018d1a49d26':raise ValueError('metadata drift')
                    for row in ET.fromstring(data).findall('.//{*}component'):
                        group,name,version=(row.attrib[k] for k in ('group','name','version'))
                        key=group.replace('.','/')+'/'+name+'/'+version+'/'
                        coordinate=':'.join((group,name,version))
                        # A canonical POM request identifies the component, not a verified/downloaded file.
                        self.catalog[key+name+'-'+version+'.pom']=coordinate
                        for artifact in row.findall('{*}artifact'):
                            self.catalog[key+artifact.attrib['name']]=coordinate
                except (OSError,ET.ParseError,KeyError,ValueError):self.catalog={}
            component=self.catalog.get(rel)
        entry=dict(code=status,repo=repo,component=component)
        if entry not in self.requests and len(self.requests)<8:self.requests.append(entry)
    def feed(self,line):
        if len(line)>4096:return
        text=line.decode('utf-8',errors='replace')
        if text=='FAILURE: Build failed with an exception.':self.active=True;return
        if not self.active:return
        code=None
        # Only fixed grammar inside a Gradle failure section, not free-form HTTP mentions.
        status=re.fullmatch(r"[ \t]*> (?:Could not (?:GET|HEAD) '([^'\r\n]+)'\. Received status code |Received status code )(401|403|429|5[0-9]{2}) from server: [^\r\n]*",text)
        if status:
            value=status.group(2);code='HTTP_5XX' if value.startswith('5') else 'HTTP_'+value
            self.request(code,status.group(1))
        elif re.fullmatch(r'[ \t]*> Dependency verification failed for configuration [^\r\n]+',text):code='STRICT_DEPENDENCY_VERIFICATION'
        elif re.fullmatch(r'[ \t]*> .*PKIX path building failed[^\r\n]*',text):code='TLS_PKIX'
        elif re.fullmatch(r'[ \t]*> (?:Read timed out|Connect timed out|Connection timed out)',text):code='NETWORK_TIMEOUT'
        elif re.fullmatch(r'[ \t]*> Namespace not specified\.[^\r\n]*',text):code='AGP_NAMESPACE_REQUIRED'
        elif re.fullmatch(r'[ \t]*> SDK location not found\.[^\r\n]*',text):code='AGP_SDK_LOCATION'
        else:
            gate=re.fullmatch(r'[ \t]*> (M1_BAD_PHASE|M1_BAD_EXACT_METHODS|M1_REFUSED_START_PARAMETERS|M1_ROOT_DRIFT|M1_TEST_TYPE_COUNT|M1_RUST_OFF_DRIFT|M1_RUST_ARGV_DRIFT|M1_JAVA17_DRIFT|M1_PRODUCTION_SYSTEM_DRIFT|M1_UNIT_SYSTEM_DRIFT|M1_OLD_TEST_REPORTS|M1_ROBOLECTRIC_OFFLINE_REQUIRED|M1_NONFRESH_TEST_FILE)',text)
            if gate:code=gate.group(1)
            elif re.fullmatch(r'[ \t]*> M1_EXACT_GRAPH_DRIFT:[a-f0-9]{64}',text):code='M1_EXACT_GRAPH_DRIFT'
            elif re.fullmatch(r'[ \t]*> M1_PREWORKER_FILTER_DRIFT:[^\r\n]+',text):code='M1_PREWORKER_FILTER_DRIFT'
        if code and code not in self.codes and len(self.codes)<8:self.codes.append(code)
    def result(self):
        return dict(codes=self.codes or ['UNKNOWN'],httpRequests=self.requests,
            attribution='untrusted-pregraph-log-hints-not-causal-proof')

class BoundedLines:
    """Discard an entire oversized physical line, never parse its prefix or suffix."""
    def __init__(self, inspect, diagnostic):
        self.inspect=inspect; self.diagnostic=diagnostic
        self.pending=b''; self.dropping=False; self.dropped=0
    def feed(self,data):
        start=0
        while start<len(data):
            end=data.find(b'\n',start)
            stop=len(data) if end<0 else end
            if not self.dropping:
                if len(self.pending)+stop-start>65536:
                    self.pending=b''; self.dropping=True; self.dropped+=1
                    if self.diagnostic.active:self.diagnostic.truncated=True
                else:self.pending+=data[start:stop]
            if end<0:return
            if not self.dropping:self.inspect(self.pending.rstrip(b'\r'))
            self.pending=b''; self.dropping=False; start=end+1
    def finish(self):
        if self.pending and not self.dropping:self.inspect(self.pending.rstrip(b'\r'))
        self.pending=b''; self.dropping=False

def children():
    return [int(x) for x in pathlib.Path('/proc/self/task/%s/children'%os.getpid()).read_text().split()]

def run_phase(argv,env,report,target):
    assert ctypes.CDLL(None,use_errno=True).prctl(36,1,0,0,0)==0
    child=None; code=None; stop=None; killed=[]; count=0
    outcomes=[]; graph=False; graph_hash=None; graph_count=None; kotlin=None; kotlin_action=False; unknown=False
    started=time.monotonic(); diagnostic=FirstDiagnostic(); pregraph=PregraphDiagnostic()
    def inspect(line):
        nonlocal graph,graph_hash,graph_count,kotlin,kotlin_action
        diagnostic.feed(line)
        if graph_count is None and not outcomes:pregraph.feed(line)
        text=line.decode('utf-8',errors='replace')
        if re.fullmatch(r'> Task :app:[A-Za-z0-9]+(?: (?:FAILED|UP-TO-DATE|SKIPPED|NO-SOURCE|FROM-CACHE))?',text):
            if len(outcomes)<60: outcomes.append(text)
            if 'Kotlin' in text and not any(x in text for x in ('UP-TO-DATE','SKIPPED','NO-SOURCE')):kotlin_action=True
        if text.startswith('M1_CLOUD_GRAPH='):
            import hashlib
            rows=json.loads(text.split('=',1)[1]);graph_count=len(rows)
            graph_hash=hashlib.sha256(json.dumps(sorted(rows,key=lambda x:x['path']),ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
        if text.startswith('M1_CLOUD_GRAPH_ACCEPTED=') and text.split('=',1)[1] in (
            '0728ffaa72386f94443366534232fd6deb18ef7dff1c01774d6cbddea72c6ddf',
            'a64f220adcbba0675b3aac62a13442dfaded848bbec9225fac2ca550f7d10056'):graph=True
        if 'Finished executing kotlin compiler using IN_PROCESS strategy' in text or 'Finished executing kotlin compiler using IN-PROCESS strategy' in text:kotlin='in-process'
        if 'Finished executing kotlin compiler using DAEMON strategy' in text:kotlin='daemon-refused'
    lines=BoundedLines(inspect,diagnostic)
    try:
        with (report/'private-gradle.log').open('xb') as log:
            child=subprocess.Popen(argv,env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,start_new_session=True,bufsize=0)
            os.set_blocking(child.stdout.fileno(),False)
            while True:
                if time.monotonic()-started>880:stop='timeout';break
                readable=select.select([child.stdout],[],[],.1)[0]
                if readable:
                    data=os.read(child.stdout.fileno(),65536)
                    if data:
                        count+=len(data)
                        if count>LIMIT:stop='log-limit';break
                        log.write(data);lines.feed(data)
                    elif child.poll() is not None:break
                elif child.poll() is not None:
                    # Pipe EOF must be observed; a surviving descendant holding it is bounded.
                    if time.monotonic()-started>875:stop='pipe-not-closed';break
            lines.finish()
            log.flush();os.fsync(log.fileno())
    except BaseException as exc:
        stop='runner-error:'+type(exc).__name__
    finally:
        deadline=time.monotonic()+10
        if child is not None:
            if child.poll() is None:
                try:os.killpg(child.pid,signal.SIGTERM)
                except ProcessLookupError:pass
                try:child.wait(timeout=2)
                except subprocess.TimeoutExpired:pass
            # Never address a reaped leader PGID with no owned descendants.
            if child.poll() is None or children():
                try:os.killpg(child.pid,signal.SIGKILL)
                except ProcessLookupError:pass
        try:
            while children() and time.monotonic()<deadline:
                for pid in children():
                    try:
                        fd=os.pidfd_open(pid)
                        try:
                            if pid in children():signal.pidfd_send_signal(fd,signal.SIGKILL);killed.append(pid)
                        finally:os.close(fd)
                    except ProcessLookupError:pass
                while True:
                    try:
                        pid,status=os.waitpid(-1,os.WNOHANG)
                        if not pid:break
                        if child is not None and pid==child.pid:code=os.waitstatus_to_exitcode(status)
                    except ChildProcessError:break
                time.sleep(.05)
            if child is not None and code is None:code=child.poll()
            remaining=children()
        except BaseException:
            remaining=None;unknown=True
        unknown=unknown or remaining is None or bool(remaining) or code is None
        result=dict(exit=code,unknown=unknown,stopReason=stop,ownedRemainingCount=None if remaining is None else len(remaining),
            ownedKilledCount=len(set(killed)),seconds=round(time.monotonic()-started,3),
            logBytes=min(count,LIMIT),droppedLongLines=lines.dropped,firstDiagnostic=diagnostic.result(),
            pregraphDiagnostic=(pregraph.result() if graph_count is None and not outcomes else None),
            graphAccepted=graph,observedGraphSha256=graph_hash,
            observedGraphNodes=graph_count,outcomes=outcomes,
            firstFailure=next((x for x in outcomes if x.endswith(' FAILED')),None),
            testExecuted=('> Task '+target) in outcomes,
            kotlinStrategy=kotlin or ('unknown' if kotlin_action else 'not-observed-no-kotlin-action'))
        temp=report/'exit.tmp'
        with temp.open('x') as f:json.dump(result,f);f.flush();os.fsync(f.fileno())
        os.replace(temp,report/'exit.json')
    return result
