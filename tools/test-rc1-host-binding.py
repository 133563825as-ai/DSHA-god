#!/usr/bin/env python3
"""R2适配器投影回归；仅Python/JDK，不运行Node/完整host/guest。

编译当前真实Preparation、migration票据逻辑、Rc1MigrationCache及HostFs映射方法。
fakeFS/JSON端口不证明真实文件系统或CJS行为；retained/missing的SettingsForms
跨语言全链仍需单独授权执行，不能把本投影当作R2完整验收。
"""
from pathlib import Path
import json
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/java/com/deepseekharness/app'
HOST = ROOT / 'tools/fixtures/rebirth-rc1-host/HostRc1Chain.java'


def block(source, marker):
    """从唯一声明提取平衡花括号块；忽略Java注释/字符串中的括号。"""
    if source.count(marker) != 1:
        raise AssertionError(f'non-unique source marker: {marker}')
    start = source.index(marker)
    opening = source.index('{', start)
    tokens = re.compile(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]', re.S)
    depth = 0
    for token in tokens.finditer(source, opening):
        if token.group() == '{':
            depth += 1
        elif token.group() == '}':
            depth -= 1
            if depth == 0:
                return source[start:token.end()]
    raise AssertionError(f'unclosed source marker: {marker}')


def projections(host):
    guest = (MAIN / 'runtime/GuestPluginScripts.java').read_text()
    declarations = [block(guest, marker) for marker in (
        'interface Runner {', 'interface Preparation {', 'private static final class Ticket {',
        'GuestPluginScripts(Preparation preparation, Runner runner)',
        'BoundedProcessRunner.Result migration(String command, String startupId)',
        'private static BoundedProcessRunner.Result unknown(',
        'private void recordFinalized(', 'private static void trim(',
        'static String migrationCommand(String command, String startupId) {',
        'static String migrationCommand(String command, String startupId, String expectedGeneration)',
    )]
    guest_projection = '''package com.deepseekharness.app.runtime;
import java.io.*; import java.util.*; import java.util.function.Consumer;
import com.deepseekharness.app.util.*;
final class GuestPluginScripts {
static final String RC1_MIGRATION_SCRIPT="rc1-migration.py";
private final Preparation preparation; private final Runner runner;
private final LinkedHashMap<String,Ticket> preparedReuse=new LinkedHashMap<>();
private final LinkedHashMap<String,BoundedProcessRunner.Result> finalizedReuse=new LinkedHashMap<>();
''' + '\n'.join(declarations) + '\n}'
    preparation = block(host, 'new GuestPluginScripts.Preparation()')
    small = block(host, '@Override public byte[] small(')
    atomic = block(host, '@Override public void atomic(')
    # 直接执行现存firstThenCached中的三个派发断言，而不是复制数字2。
    cached = block(host, 'private void firstThenCached()')
    assertions = re.findall(r'assertEquals\("cached [^;]+;|(?<=cached.migration\("finalize", "boot-cached"\);\n    )assertEquals\([^;]+;', cached)
    if len(assertions) != 3:
        raise AssertionError('cached dispatch assertions missing')
    host_projection = '''package com.deepseekharness.app.runtime;
import java.io.*; import java.nio.file.*; import java.nio.charset.StandardCharsets;
import java.util.*; import com.deepseekharness.app.backup.*;
final class Adapter {
final Path files=Path.of("/synthetic/host"),root=files.resolve("guest"),home=root.resolve(".dsh"),state=files.resolve("rc1-migration-state");
final HostFs fs=new HostFs(); int dispatched=2; boolean syntheticGuest;
// 仅此投影的目录就绪端口，不访问/synthetic或真实用户目录。
static class Files {static boolean isDirectory(Path p){return p.equals(Path.of("/synthetic/host/guest"));}}
class HostFs extends JvmBackupFileSystem {\n''' + small + '\n' + atomic + '''\n}
GuestPluginScripts scripts() { return new GuestPluginScripts(''' + preparation + ''', (command,timeout)-> {
dispatched++; if(command.contains(" finalize ") && !command.contains(" --expected-generation '"+Probe.GEN+"'")) throw new AssertionError("expected-generation missing");
if(syntheticGuest){boolean finish=command.contains(" finalize ");Map<String,Object> value=Probe.map("resultVersion",3L,"phase",finish?"finalize":"prepare","status",finish?"unknown":"prepared","startupId","guest","generation",Probe.GEN,"verificationScope",finish?"none":"current","contentCopyStatus",finish?"unknown":"verified","sourceObjectIdentity","unknown","settingsImportStatus",finish?"unknown":"pending","executionState",finish?"UNKNOWN":"COMPLETED","reason",finish?"EXPECTED_GENERATION_MISMATCH":"SYNTHETIC_PREPARED");String line="DSHA_RC1_MIGRATION="+new String(BackupJson.write(value,65536),StandardCharsets.UTF_8)+"\\n";return new com.deepseekharness.app.util.BoundedProcessRunner.Result(line,finish?1:0);}
return new com.deepseekharness.app.util.BoundedProcessRunner.Result("DSHA_RC1_MIGRATION={\\"resultVersion\\":3,\\"phase\\":\\"prepare\\",\\"status\\":\\"unknown\\",\\"startupId\\":\\"missing\\",\\"reason\\":\\"SETTINGS_LEDGER_MISSING\\"}\\n",1);
}); }
static void assertEquals(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
static void assertEquals(String message,Object a,Object b){assertEquals(a,b);}
void checkDispatch(){''' + '\n'.join(assertions) + '\n}\n}'
    return guest_projection, host_projection


# 端口只保存合法夹具JSON文本；解析由Python预先登记，Java只按相同字节取对象。
# write采用精确JSON序列化以保留真实bindHistory的SHA；这不是BackupJson解析器测试。
JSON_PORT = r'''package com.deepseekharness.app.backup;
import java.util.*; import java.io.*; import java.nio.charset.StandardCharsets;
public final class BackupJson {
static final Map<String,Map<String,Object>> known=new HashMap<>();
public static void register(String text,Map<String,Object> value){known.put(text,value);}
public static Map<String,Object> read(byte[] bytes,int limit)throws IOException {
String text=new String(bytes,StandardCharsets.UTF_8); if(bytes.length>limit || !known.containsKey(text))throw new IOException("FAKE_JSON_UNREGISTERED"); return new LinkedHashMap<>(known.get(text)); }
public static byte[] write(Map<String,?> value,int limit)throws IOException {
String text=encode(value); if(text.getBytes(StandardCharsets.UTF_8).length>limit)throw new IOException("LIMIT");
known.put(text,new LinkedHashMap<>(value));
String guest="/root/.dsh", host="/synthetic/host/guest/.dsh";
String other=text.contains(host)?text.replace(host,guest):text.replace(guest,host);
known.put(other,(Map<String,Object>)remap(value,text.contains(host)?host:guest,text.contains(host)?guest:host));
return text.getBytes(StandardCharsets.UTF_8); }
static Object remap(Object v,String from,String to){if(v instanceof String)return ((String)v).replace(from,to);if(v instanceof Map){Map<String,Object> m=new LinkedHashMap<>();for(var e:((Map<?,?>)v).entrySet())m.put((String)e.getKey(),remap(e.getValue(),from,to));return m;}return v;}
static String encode(Object v){
if(v==null)return "null"; if(v instanceof String)return "\""+((String)v).replace("\\","\\\\").replace("\"","\\\"")+"\"";
if(v instanceof Map){List<String> rows=new ArrayList<>();for(var e:((Map<?,?>)v).entrySet())rows.add(encode(e.getKey())+":"+encode(e.getValue()));return "{"+String.join(",",rows)+"}";}
if(v instanceof List){List<String> rows=new ArrayList<>();for(Object x:(List<?>)v)rows.add(encode(x));return "["+String.join(",",rows)+"]";}return v.toString();}
}
'''
FS_PORT = '''package com.deepseekharness.app.backup;
import java.io.*; import java.util.*;
public interface BackupFileSystem {
final class Node {public final String type,key;public final long size,modified,device;public final int mode;
public Node(String t,String k,long s,long m,long d,int p){type=t;key=k;size=s;modified=m;device=d;mode=p;}
public boolean same(Node n){return n!=null&&type.equals(n.type)&&key.equals(n.key)&&size==n.size&&modified==n.modified&&device==n.device&&mode==n.mode;}}
Node stat(File f)throws IOException; File child(File r,String n)throws IOException;
byte[] small(File f,int limit)throws IOException; void atomic(File d,String n,byte[] b)throws IOException;
List<String> list(File f)throws IOException;
}
'''
JVM_FS_PORT = '''package com.deepseekharness.app.backup;
import java.io.*; import java.util.*;
public class JvmBackupFileSystem implements BackupFileSystem {
public final Map<File,byte[]> data=new HashMap<>();public final Map<File,Long> mtime=new HashMap<>();long clock=100;
public Node stat(File f){byte[] b=data.get(f);boolean leaf=f.getName().endsWith(".json")||f.getName().startsWith("settings.yaml")||f.getName().equals(".dsha-rc1-restore-generation");return new Node(b==null?(leaf?"MISSING":"DIRECTORY"):"FILE",f.getPath().endsWith(".dsh")?"100:123":"100:456",b==null?0:b.length,mtime.getOrDefault(f,0L),100,0700);}
public File child(File r,String n){return new File(r,n);}
public byte[] small(File f,int limit)throws IOException{byte[] b=data.get(f);if(b==null||b.length>limit)throw new IOException("MISSING");return b.clone();}
public void atomic(File d,String n,byte[] b)throws IOException{File f=new File(d,n);data.put(f,b.clone());mtime.put(f,++clock);}
public List<String> list(File f){return List.of();}
}
'''


def java_value(value):
    if value is None:
        return 'null'
    if isinstance(value, bool):
        return str(value).lower()
    if isinstance(value, int):
        return f'{value}L'
    if isinstance(value, str):
        return json.dumps(value)
    if isinstance(value, list):
        return 'List.of(' + ','.join(map(java_value, value)) + ')'
    return 'map(' + ','.join(java_value(x) for row in value.items() for x in row) + ')'


def fixture_records():
    generation = '12345678-1234-1234-1234-123456789abc'
    sha = 'a' * 64
    current = dict(version=2, generation=generation, startupId='old', dshHome='/root/.dsh',
                   dataRoot=dict(path='/root/.dsh', device='100', inode='123'), inputs={})
    prepared = dict(current, dshVersion='0.2.2-rc.1', status='prepared', protectionComplete=True,
                    inputDigest='d' * 64, settingsSha256=sha)
    receipt = dict(version=2, generation=generation, dshVersion='0.2.2-rc.1', status='committed',
                   protectionComplete=True, sourcePreserved=True, settingsImported=True)
    ledger = dict(version=2, generation=generation, sourceSha256=sha, status='verified',
                  expectedSections=['first'], sections={'first': {'status': 'verified'}})
    # 真实checkpoint gate要求settings输入SHA一致；fakeFS输入读取也返回同一真实hash。
    import hashlib
    sha = hashlib.sha256(b'saved setting').hexdigest()
    current['inputs'] = {'settings.yaml': sha}
    prepared['inputs'] = dict(current['inputs'])
    prepared['settingsSha256'] = ledger['sourceSha256'] = sha
    return {'current.json': current, f'generations/{generation}/reuse.json': prepared,
            f'generations/{generation}/receipt.json': receipt,
            f'generations/{generation}/settings-result.json': ledger}


def probe_source():
    registrations = []
    for name, value in fixture_records().items():
        host_value = json.loads(json.dumps(value).replace('/root/.dsh', '/synthetic/host/guest/.dsh'))
        for item in (value, host_value):
            text = json.dumps(item, separators=(',', ':'))
            registrations.append(f'BackupJson.register({json.dumps(text)}, {java_value(item)});')
        text = json.dumps(host_value, separators=(',', ':'))
        registrations.append(f'a.fs.atomic(a.state.toFile(),{json.dumps(name)},{json.dumps(text)}.getBytes(StandardCharsets.UTF_8));')
    return '''package com.deepseekharness.app.runtime;
import java.util.*;import java.io.*;import java.nio.charset.StandardCharsets;import com.deepseekharness.app.backup.*;
public class Probe {
static final String GEN="12345678-1234-1234-1234-123456789abc";
static Map<String,Object> map(Object...pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
static Adapter fixture()throws Exception {Adapter a=new Adapter();
''' + '\n'.join(registrations) + '''
a.fs.atomic(a.home.toFile(),"settings.yaml","saved setting".getBytes(StandardCharsets.UTF_8));return a;}
static String call(GuestPluginScripts g,String phase,String id)throws Exception{return g.migration(phase,id).output;}
public static void main(String[]args)throws Exception{
Adapter a=fixture();File ledger=new File(a.state.toFile(),"generations/"+GEN+"/settings-result.json");
byte[] before=a.fs.data.get(ledger).clone();Long mtime=a.fs.mtime.get(ledger);
var reuse=Rc1MigrationCache.reuse(a.fs,a.files.toFile(),a.home.toFile());check(reuse.status==Rc1MigrationCache.Reuse.Status.REUSABLE_HISTORY,"fixture history "+reuse.reason);
GuestPluginScripts g=a.scripts();String prepared=call(g,"prepare","cached");
check(prepared.contains("historical-complete")&&prepared.contains("NOT_SENT")&&prepared.contains("verified-historical"),"cached prepare: "+prepared);
check(com.deepseekharness.app.util.Rc1MigrationResult.allowsStart(com.deepseekharness.app.util.Rc1MigrationResult.parse(prepared)),"prepare protocol");
String raw=new String(a.fs.data.get(new File(a.state.toFile(),"current.json")),StandardCharsets.UTF_8);
check(raw.contains("history-only")&&raw.contains(a.home.toString())&&!raw.contains("/root/.dsh"),"durable host home");
// fakeJSON登记映射后的读回；只对真实映射结果执行，不改变磁盘端口字节。
String mapped=raw.replace(a.home.toString(),"/root/.dsh");
check(Arrays.equals(a.fs.small(new File(a.state.toFile(),"current.json"),65536),mapped.getBytes(StandardCharsets.UTF_8)),"read mapping");
String finalized=call(g,"finalize","cached");check(finalized.contains("historical-complete"),"local finalize: "+finalized);
check(finalized.equals(call(g,"finalize","cached")),"finalize noReplay");a.checkDispatch();
check(Arrays.equals(before,a.fs.data.get(ledger))&&mtime.equals(a.fs.mtime.get(ledger)),"ledger bytes+mtime");
// 反向映射仅允许state/current.json，其他目录或名称必须逐字节透传。
byte[] literal="\\\"/root/.dsh\\\"".getBytes(StandardCharsets.UTF_8);
a.fs.atomic(a.home.toFile(),"current.json",literal);a.fs.atomic(a.state.toFile(),"reuse.json",literal);
check(Arrays.equals(literal,a.fs.data.get(new File(a.home.toFile(),"current.json"))),"outside state mapping");
check(Arrays.equals(literal,a.fs.data.get(new File(a.state.toFile(),"reuse.json"))),"non-current mapping");
Adapter missing=fixture();missing.fs.data.remove(new File(missing.state.toFile(),"generations/"+GEN+"/settings-result.json"));
var absent=Rc1MigrationCache.reuse(missing.fs,missing.files.toFile(),missing.home.toFile());
check(absent.status==Rc1MigrationCache.Reuse.Status.UNAVAILABLE&&"SETTINGS_LEDGER_MISSING".equals(absent.reason),"missing ledger strict");
GuestPluginScripts mg=missing.scripts();String unknown=call(mg,"prepare","missing");
check(unknown.contains("unknown")&&!unknown.contains("historical-complete"),"missing ledger must not succeed");
int sent=missing.dispatched;call(mg,"prepare","missing");call(mg,"finalize","missing");call(mg,"finalize","missing");check(sent==missing.dispatched,"unknown noReplay");
// fake runner只验证Java票据传参/保留unknown；不声称执行过Python代次拒绝。
Adapter guest=fixture();guest.syntheticGuest=true;guest.fs.data.remove(new File(guest.state.toFile(),"generations/"+GEN+"/settings-result.json"));
GuestPluginScripts gg=guest.scripts();check(call(gg,"prepare","guest").contains("prepared"),"guest ticket");
String rejected=call(gg,"finalize","guest");check(rejected.contains("EXPECTED_GENERATION_MISMATCH")&&rejected.contains("unknown"),"generation rejection");
int finalSent=guest.dispatched;check(rejected.equals(call(gg,"finalize","guest"))&&finalSent==guest.dispatched,"rejected finalize noReplay");
System.out.println("PROJECTION_PASS retained bind/mapping/local2/ledger bytes+mtime; missing ledger UNAVAILABLE/unknown noReplay; expected-generation Java argument+unknown noReplay (fake runner)");
}}
'''


class HostBindingTest(unittest.TestCase):
    def test_projection_and_old_adapter_mutations(self):
        host = HOST.read_text()
        # 源码结构断言保留未授权执行的真实链路guard，而不声称运行过它们。
        for guard in ('hook(0);', 'assertArrayEquals(before, Files.readAllBytes(ledger));',
                      '"history ledger mtime must not move"', '"EXPECTED_GENERATION_MISMATCH"',
                      '"unknown cannot replay"', '"unknown", rejected.get("status")'):
            self.assertIn(guard, host)
        bind = block(host, 'public void bindMigrationHistory(')
        old_dispatch = host.replace('"cached prepare remains local", 2, dispatched',
                                    '"cached prepare remains local", 3, dispatched')
        self.assertNotEqual(host, old_dispatch)
        for label, source, expected in (
            ('current', host, True), ('old-noBind', host.replace(bind, ''), False),
            ('old-dispatch3', old_dispatch, False),
            ('old-noReverseMapping', host.replace(block(host, '@Override public void atomic('),
                '@Override public void atomic(File directory, String name, byte[] bytes) throws IOException { super.atomic(directory, name, bytes); }'), False),
        ):
            with self.subTest(label=label), tempfile.TemporaryDirectory(prefix='rc1-host-binding-') as tmp:
                target = Path(tmp)
                guest, adapter = projections(source)
                sources = {
                    'runtime/GuestPluginScripts.java': guest,
                    'runtime/Adapter.java': adapter,
                    'runtime/Probe.java': probe_source(),
                    'backup/BackupJson.java': JSON_PORT,
                    'backup/BackupFileSystem.java': FS_PORT,
                    'backup/JvmBackupFileSystem.java': JVM_FS_PORT,
                    'util/Constants.java': 'package com.deepseekharness.app.util; public class Constants {public static final String DSH_VERSION="0.2.2-rc.1";}',
                    'util/ShellQuote.java': '''package com.deepseekharness.app.util; public class ShellQuote {public static String arg(String s){if(!s.matches("[a-z0-9-]+"))throw new IllegalArgumentException("SYNTHETIC_ID_ONLY");return "'"+s+"'";}}''',
                    'util/BoundedProcessRunner.java': '''package com.deepseekharness.app.util;
public class BoundedProcessRunner {public static class Result {public final String output;public final int exitCode;public final boolean timedOut=false,truncated=false;public Result(String s,int c){output=s;exitCode=c;}}public static Result localCompletion(String s){return new Result(s,0);}}''',
                    'runtime/GuestPreparationFailure.java': '''package com.deepseekharness.app.runtime;class GuestPreparationFailure extends java.io.IOException {enum Code {ENV_NOT_READY} GuestPreparationFailure(Code c){super(c.name());}}''',
                }
                for relative in ('runtime/Rc1MigrationCache.java', 'util/Rc1MigrationReuse.java',
                                 'util/Rc1MigrationResult.java', 'util/Ids.java'):
                    sources[relative] = (MAIN / relative).read_text()
                paths = []
                for name, content in sources.items():
                    path = target / 'com/deepseekharness/app' / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(content)
                    paths.append(str(path))
                compile_result = subprocess.run(['javac', '-J-Xmx96m', '-encoding', 'UTF-8', '-d', str(target), *paths],
                                                capture_output=True, text=True, timeout=90)
                self.assertEqual(compile_result.returncode, 0, compile_result.stdout + compile_result.stderr)
                result = subprocess.run(['java', '-Xmx96m', '-cp', str(target),
                                         'com.deepseekharness.app.runtime.Probe'], capture_output=True, text=True, timeout=30)
                if expected:
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertIn('PROJECTION_PASS', result.stdout)
                else:
                    self.assertNotEqual(result.returncode, 0, 'old adapter mutation survived')
                    failure = {'old-noBind': 'MIGRATION_HISTORY_HANDOFF_UNVERIFIED',
                               'old-dispatch3': '3 != 2',
                               'old-noReverseMapping': 'durable host home'}[label]
                    self.assertIn('AssertionError', result.stderr)
                    self.assertIn(failure, result.stderr)
                print(f'{label}: exit={result.returncode}\n{result.stdout}{result.stderr}', flush=True)


if __name__ == '__main__':
    unittest.main()
