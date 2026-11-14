"""Startup/WebReady宿主连接边界；被测方法来自当前源码，不重写。"""
import hashlib

SOURCES = '''
core/StartupPipeline core/WebReadyTasks core/StartupPreparationTiming core/StartupStopFailure
core/StartupStopText core/WebStopText core/StartupEnvironmentActions core/WebProcessSession
core/HarnessSessionState core/StartupStopWait core/WebStopCoordinator
util/CredentialRead util/WebStopBudget util/WebStopDiagnostic util/WebStopException
util/WebLifecycle util/WebStartProtection util/WebSessionProof util/WebProcSel
util/ProcessIdentity util/ProcessTermination util/Compat util/AgentTaskRegistration
util/ProcStat util/DshEntrypoint util/PreviewPageSession util/WebPreviewPolicy util/AgentTaskState
'''.split()
TESTS = ['core/StartupPipelineTest', 'core/WebReadyTasksTest']


def prepare(directory, main):
    base = 'com.deepseekharness.app.'
    reject = 'throw new AssertionError("unexecuted Android/owner boundary");'
    def put(package, name, body):
        path = directory / package.replace('.', '/') / (name + '.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('package '+package+';\n'+body)
    # Compat必须编译当前真类，其未知launcher不得信号的策略不能被固定false替换。
    compat = directory/'com/deepseekharness/app/util/Compat.java'
    if compat.exists():
        # 已核对的固定run适配器文件；只删除本次生成的编译替身，不动源码。
        assert compat.resolve().is_relative_to(directory.resolve())
        compat.unlink()
    put('android.os', 'SystemClock', 'public class SystemClock { public static long elapsedRealtime(){return System.nanoTime()/1000000;} }')
    put('android.os', 'Build', 'public class Build { public static class VERSION { public static final int SDK_INT=34; } }')
    put('android.os', 'Process', 'public class Process { public static int myPid(){'+reject+'} }')
    put('android.system', 'ErrnoException', 'public class ErrnoException extends Exception { public int errno; }')
    put('android.system', 'StructStat', 'public class StructStat { public int st_mode; public StructStat(int m){st_mode=m;} }')
    put('android.system', 'OsConstants', 'public class OsConstants { public static final int SIGQUIT=3,SIGTERM=15,SIGKILL=9,ESRCH=3; public static boolean S_ISLNK(int m){return (m&0170000)==0120000;} }')
    put('android.system', 'Os', '''public class Os {
      public static StructStat lstat(String p) throws ErrnoException { return new StructStat(java.nio.file.Files.isSymbolicLink(java.nio.file.Path.of(p))?0120000:0100000); }
      public static void symlink(String a,String b) throws ErrnoException {REJECT}
      public static void link(String a,String b) throws ErrnoException {REJECT}
      public static void chmod(String a,int m) throws ErrnoException {REJECT}
      public static String readlink(String p) throws ErrnoException {REJECT}
      public static void kill(int p,int s) throws ErrnoException {REJECT}
    }'''.replace('REJECT',reject))
    # 被测源码生产构造器仅编译不执行；测试自身注入的Runtime/Diagnostics才是本轮边界。
    put(base+'core','ConfigStore','class ConfigStore { com.deepseekharness.app.util.CredentialRead readApiKey(){'+reject+'} }')
    put(base+'core','StartupDiagnostics','class StartupDiagnostics { String recordId(){'+reject+'} void message(long g,String s){'+reject+'} void issue(long g,String p,String s){'+reject+'} }')
    put(base+'runtime','ProotBootstrap','''public class ProotBootstrap {
      public void ensureRuntimeFiles(){REJECT} public boolean isEnvironmentReady(){REJECT}
      public boolean hasOfflineBundle(){REJECT}
      public void extractOfflineBundle(java.util.function.BiConsumer<Long,Long> p){REJECT}
      public com.deepseekharness.app.util.BoundedProcessRunner.Result prepareRc1MigrationResult(String id){REJECT}
      public com.deepseekharness.app.util.BoundedProcessRunner.Result finalizeRc1MigrationResult(String id){REJECT}
      public com.deepseekharness.app.util.BoundedProcessRunner.Result registerBuiltinPluginsResult(){REJECT}
      public com.deepseekharness.app.util.BoundedProcessRunner.Result runPluginManagerResult(String a,String b){REJECT}
      public static class StartupPlugins { public com.deepseekharness.app.util.BoundedProcessRunner.Result register(){REJECT} public com.deepseekharness.app.util.BoundedProcessRunner.Result legacy(){REJECT} public void close(){REJECT} }
      public StartupPlugins prepareStartupPlugins(){REJECT}
      public com.deepseekharness.app.util.BoundedProcessRunner.Result materializeCasLinksResult(java.util.function.Consumer<String> p){REJECT}
      public java.io.File getRootfsDir(){REJECT}
    }'''.replace('REJECT',reject))
    put(base+'runtime','WebProcessManager','''public class WebProcessManager {
      public WebProcessManager(android.content.Context c){if(c!=null){REJECT}}
      public com.deepseekharness.app.core.WebStopCoordinator.Result stopResult(){REJECT}
      public boolean confirmStopped(boolean alive){REJECT}
      public boolean isRunning(){REJECT} public boolean hasRecordedSession(){REJECT}
      public boolean recoveredLauncherExited(com.deepseekharness.app.util.WebSessionProof.Saved s) throws java.io.IOException {REJECT}
      public boolean confirmNoUntrackedWebLaunchers() throws java.io.IOException {REJECT}
      public String processCommandDigest(int p){REJECT}
    }'''.replace('REJECT',reject))
    put(base+'runtime','StoredWebSession','''public class StoredWebSession {
      public StoredWebSession(android.content.Context c){REJECT}
      public record Record(com.deepseekharness.app.util.WebSessionProof.Saved proof){}
      public Record read(){REJECT}
    }'''.replace('REJECT',reject))
    put(base+'core','HarnessController','class HarnessController { android.content.Context context(){'+reject+'} com.deepseekharness.app.runtime.ProotBootstrap proot(){'+reject+'} }')
    put(base+'core','WebRecovery','class WebRecovery {}')
    put(base+'plugins','PluginManagementEngine','public class PluginManagementEngine { public static PluginManagementEngine get(android.content.Context c){'+reject+'} public void yieldToUserAction(){'+reject+'} }')
    put(base[:-1],'LanProxyService','public class LanProxyService { public static void stop(){'+reject+'} }')
    put(base[:-1],'BackupManager','public class BackupManager { public static void recoverInterrupted(Object o){'+reject+'} }')
    put(base[:-1],'HttpShellService','public class HttpShellService { public static Lease acquire(android.content.Context c){'+reject+'} public static class Lease { public void ensureStarted(){'+reject+'} public void close(){'+reject+'} } }')
    for name, body in [
        ('EnvironmentMaintenance','static void cleanupCompleted(HarnessController o)'),
        ('PluginActivationHooks','static void beforeLaunch(HarnessController o,String id)'),
    ]:
        put(base+'core',name,'class '+name+' { '+body+'{'+reject+'} }')
    put(base+'core','StartupRepairs','class StartupRepairs { static String prepareSafeProfile(HarnessController o){'+reject+'} static void checkpoint(HarnessController o,org.json.JSONObject j){'+reject+'} }')
    put(base+'backup','ConfigurationSnapshots','public class ConfigurationSnapshots { public static String errorCode(Exception e){'+reject+'} }')
    put('org.json','JSONObject','public class JSONObject { public JSONObject put(String k,String v){'+reject+'} }')
    # UiText只保留中文文案边界，不改变被测门禁。
    put(base+'util','UiText','public class UiText { public static String text(String s){return s;} public static String choose(String c,String e){return c;} public static String format(String s,Object... a){return String.format(java.util.Locale.ROOT,s,a);} }')
    controller = main/'core/WebLifecycleController.java'
    source = controller.read_text()
    start = '  static final class WebRun {'
    end = '\n  /** 启动准入结果：DEFERRED'
    assert source.count(start) == source.count(end) == 1
    piece = source[source.index(start):source.index(end)]
    # 逐字切出完整嵌套类，不改finalize方法，不引入整个Controller的Android图。
    assert piece.endswith('  }\n') and piece.count('String finalizeMigration()') == 1
    put(base+'core','WebLifecycleController','import java.io.IOException; import com.deepseekharness.app.runtime.ProotBootstrap; class WebLifecycleController {\n'+piece+'}\n')
    return {'source':str(controller), 'sourceSha256':hashlib.sha256(controller.read_bytes()).hexdigest(),
            'sliceSha256':hashlib.sha256(piece.encode()).hexdigest(), 'start':start, 'end':end,
            'scope':'exact complete nested WebRun only; not full Controller or Robolectric'}
