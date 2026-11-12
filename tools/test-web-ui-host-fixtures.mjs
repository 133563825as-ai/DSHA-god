import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const tempRoot = path.resolve(tmpdir());
const temp = mkdtempSync(path.join(tempRoot, 'dsha-web-ui-fixture-'));
try {
  const source = path.join(project, 'app/src/main/java/com/deepseekharness/app/util');
  const fixture = path.join(project, 'tools/fixtures/WebUiHostFixture.java');
  const compile = spawnSync('javac', ['-encoding', 'UTF-8', '-d', temp, path.join(source, 'WebUploadSessionBudget.java'),
    path.join(source, 'UiLanguagePreference.java'), path.join(source, 'RecoveryLocalePolicy.java'),
    path.join(source, 'WebPreviewPolicy.java'), fixture], { encoding: 'utf8' });
  assert.equal(compile.status, 0, `javac failed:\n${compile.stdout}\n${compile.stderr}`);
  const run = spawnSync('java', ['-cp', temp, 'WebUiHostFixture'], { encoding: 'utf8' });
  assert.equal(run.status, 0, `Java fixture failed:\n${run.stdout}\n${run.stderr}`);
  process.stdout.write(run.stdout);

  const readSource = relative => readFileSync(path.join(project, relative), 'utf8');
  const ui = 'app/src/main/java/com/deepseekharness/app/ui/';
  const contract = readSource(ui + 'RecoveryBrowserSurface.java');
  const factory = readSource('app/src/standard/java/com/deepseekharness/app/ui/RecoveryBrowserFactory.java');
  const surface = readSource(ui + 'RecoveryWebSurface.java');
  const activity = readSource(ui + 'RecoveryWebActivity.java');
  const scripts = readSource(ui + 'WebPageScripts.java');
  const formal = readSource(ui + 'WebPreviewActivity.java');
  const policy = readFileSync(path.join(source, 'RecoveryLocalePolicy.java'), 'utf8');
  const method = (text, start, end) => {
    const begin = text.indexOf(start), finish = text.indexOf(end, begin + start.length);
    assert.ok(begin >= 0 && finish > begin, `missing source anchors: ${start} / ${end}`);
    return text.slice(begin, finish);
  };
  const load = method(surface, '  public void load(', '  private void installLanguageListener(');
  const listener = method(surface, '  private void installLanguageListener(', '  public void syncLanguage(');
  const sync = method(surface, '  public void syncLanguage(', '  public boolean back(');
  const detach = method(surface, '  public void detach(', '  public void close(');
  const emergency = method(scripts, '  public static String emergencyCompatibility(', '  public static String compatibility(');
  const language = method(scripts, '  public static String language(', '  private static String read(');

  // 旧 low 的 manifest/扩展版本/native-port 检查迁移到实际 Standard 创建链与逐页安装的桥。
  // WebView 没有扩展缓存或 Gecko app-name 语法；不能以残留资产冒充当前运行覆盖。
  assert.match(contract, /interface RecoveryBrowserSurface extends AutoCloseable/);
  assert.match(factory, /static RecoveryBrowserSurface create\(Context context\)\s*\{\s*return new RecoveryWebSurface\(context\);/);
  assert.match(surface, /class RecoveryWebSurface implements RecoveryBrowserSurface/);
  assert.match(activity, /retained\.browser = RecoveryBrowserFactory\.create\(this\)/);
  assert.doesNotMatch(factory + surface + emergency, /Gecko|connectNative|recovery-web-integration/);
  assert.match(load, /RecoveryLocalePolicy\.launch\(base, auth\)/);
  assert.match(load, /if \(launch == null\)\s*\{\s*fail\("RECOVERY_ORIGIN_INVALID"\);\s*return;/);
  assert.match(load, /origin = launch\.origin;[\s\S]*String localAuth = launch\.authUrl;/);
  assert.match(load, /installLanguageListener\(\);[\s\S]*DOCUMENT_START_SCRIPT[\s\S]*addDocumentStartJavaScript\([\s\S]*WebPageScripts\.emergencyCompatibility\(app\),\s*java\.util\.Set\.of\(origin\.substring\(0, origin\.length\(\) - 1\)\)\)/);
  assert.match(surface, /needsFinishedPageLanguageBridge\([\s\S]*DOCUMENT_START_SCRIPT\)\)\)\s*web\.evaluateJavascript\(WebPageScripts\.language\(app\), null\)/);
  assert.match(emergency, /return language\(context\)[\s\S]*read\(context, "web-integration\/es-compat\.js"\)[\s\S]*read\(context, "web-integration\/compat\.js"\)/);
  assert.doesNotMatch(emergency, /NATIVE_PLUGINS|files\.js|bridge-token|startup\.js|compatibility\(context\)/);
  assert.match(listener, /removeWebMessageListener\(view, "DshaLanguage"\);\s*WebViewCompat\.addWebMessageListener\(\s*view,\s*"DshaLanguage",\s*java\.util\.Set\.of\(origin\.substring\(0, origin\.length\(\) - 1\)\)/);
  assert.match(listener, /if \(web != view\s*\|\| current == null\s*\|\| current\.isFinishing\(\)\s*\|\| current\.isDestroyed\(\)\s*\|\| !isMainFrame\s*\|\| !com\.deepseekharness\.app\.util\.RecoveryLocalePolicy\.sameOrigin\(\s*origin, sourceOrigin\.toString\(\)\)\) return;/);
  assert.match(listener, /String selected = message\.getData\(\);\s*if \(com\.deepseekharness\.app\.util\.RecoveryLocalePolicy\.supportedLanguage\(selected\)\)\s*LanguageController\.select\(current, selected\);/);
  assert.match(sync, /if \(!com\.deepseekharness\.app\.util\.RecoveryLocalePolicy\.supportedLanguage\(value\)\) return;/);
  assert.match(sync, /if \(current == null\s*\|\| !loaded\s*\|\| !com\.deepseekharness\.app\.util\.RecoveryLocalePolicy\.sameOrigin\(origin, current\.getUrl\(\)\)\)\s*return;/);
  assert.match(sync, /current\.evaluateJavascript\(\s*com\.deepseekharness\.app\.util\.RecoveryLocalePolicy\.pageScript\(value\), null\)/);
  const formalLanguage = method(formal,
    'androidx.webkit.WebViewCompat.removeWebMessageListener(view, "DshaLanguage");',
    'androidx.webkit.WebViewCompat.removeWebMessageListener(view, "DshaTheme");');
  assert.match(formalLanguage, /addWebMessageListener\(\s*view,\s*"DshaLanguage",\s*java\.util\.Collections\.singleton\(baseUrl\.substring\(0, baseUrl\.length\(\) - 1\)\)/);
  assert.match(formalLanguage, /if \(!isCurrentPage\(source\)\s*\|\| !mainFrame\s*\|\| !WebPreviewPolicy\.sameService\(baseUrl, origin\.toString\(\)\)\s*\|\| !WebPreviewPolicy\.sameService\(baseUrl, source\.getUrl\(\)\)\) return;/);
  assert.match(formalLanguage, /String language = message\.getData\(\);[\s\S]*if \(com\.deepseekharness\.app\.util\.UiLanguagePreference\.isSupportedLanguage\(language\)\)\s*LanguageController\.select\(this, language\);/);

  // 调用真实纯 Java 策略，并执行真实 WebPageScripts.language 返回表达式，不在测试中重写桥逻辑。
  const tags = ['zh','zh-Hant','en','ru','ja','ko','ar','pt','la','fr'];
  const invalidTags = ['system','ar-EG','zh-TW','invalid', "en';alert(1)//", ''];
  const languageReturn = language.match(/return ([\s\S]*);\s*\}\s*$/)?.[1];
  assert.ok(languageReturn, 'language script return expression must be present');
  const javaFixture = path.join(temp, 'RecoveryStandardFixture.java');
  writeFileSync(javaFixture, `import com.deepseekharness.app.util.RecoveryLocalePolicy;
import com.deepseekharness.app.util.UiLanguagePreference;
import com.deepseekharness.app.util.WebPreviewPolicy;
public final class RecoveryStandardFixture {
  private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
  public static void main(String[] args) {
    String base = "http://localhost:3081/";
    var launch = RecoveryLocalePolicy.launch("http://127.0.0.1:3081/", "http://127.0.0.1:3081/auth?token=x%2F127.0.0.1#home");
    check(launch != null && base.equals(launch.origin), "emergency localhost launch");
    check("http://localhost:3081/auth?token=x%2F127.0.0.1#home".equals(launch.authUrl), "raw auth bytes preserved");
    check(RecoveryLocalePolicy.sameOrigin(base, base + "chat"), "same emergency authority accepted");
    String formalBase = WebPreviewPolicy.loopbackBaseUrl("http://127.0.0.1:3081/auth");
    check("http://127.0.0.1:3081/".equals(formalBase), "formal origin remains numeric loopback");
    check(WebPreviewPolicy.sameService(formalBase, formalBase + "chat"), "same formal authority accepted");
    check(!WebPreviewPolicy.sameService(formalBase, base + "chat"), "formal bridge rejects emergency host");
    check(!WebPreviewPolicy.sameService(formalBase, "http://127.0.0.1:3082/chat"), "formal bridge rejects wrong port");
    for (String url : new String[]{"http://127.0.0.1:3081/chat", "http://localhost:3082/chat", "https://localhost:3081/chat", "http://localhost:3081@evil.test/chat", "http://evil.test:3081/chat", "http://localhost/chat", "file:///chat"}) {
      check(!RecoveryLocalePolicy.sameOrigin(base, url), "reject foreign origin: " + url);
      check(!RecoveryLocalePolicy.needsFinishedPageLanguageBridge(base, url, false), "no foreign finished-page injection");
    }
    check(RecoveryLocalePolicy.launch("http://127.0.0.1:3081/", "http://127.0.0.1:3082/auth") == null, "reject mismatched auth port");
    check(RecoveryLocalePolicy.launch("http://localhost:3081/", "http://localhost:3081/auth") == null, "accept only native formal launch input");
    check(RecoveryLocalePolicy.needsFinishedPageLanguageBridge(base, base + "chat", false), "old WebView language bridge");
    check(!RecoveryLocalePolicy.needsFinishedPageLanguageBridge(base, base + "chat", true), "do not duplicate document-start bridge");
    check(UiLanguagePreference.LANGUAGES.equals(java.util.List.of(${tags.map(tag => JSON.stringify(tag)).join(',')})), "exact canonical language set");
    for (String id : UiLanguagePreference.LANGUAGES) {
      check(RecoveryLocalePolicy.supportedLanguage(id), "supported canonical language");
      System.out.println("PAGE " + java.util.Base64.getEncoder().encodeToString(RecoveryLocalePolicy.pageScript(id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      String bridge = ${languageReturn};
      System.out.println("BRIDGE " + java.util.Base64.getEncoder().encodeToString(bridge.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    for (String id : new String[]{${invalidTags.map(tag => JSON.stringify(tag)).join(',')}, null}) {
      check(!RecoveryLocalePolicy.supportedLanguage(id), "reject invalid language");
      try { RecoveryLocalePolicy.pageScript(id); throw new AssertionError("invalid language reached script"); }
      catch (IllegalArgumentException expected) { check("RECOVERY_LANGUAGE".equals(expected.getMessage()), "explicit language refusal"); }
    }
  }
}
`, 'utf8');
  const policyCompile = spawnSync('javac', ['-encoding', 'UTF-8', '-cp', temp, '-d', temp, javaFixture], { encoding: 'utf8' });
  assert.equal(policyCompile.status, 0, `Standard fixture javac failed:\n${policyCompile.stdout}\n${policyCompile.stderr}`);
  const policyRun = spawnSync('java', ['-cp', temp, 'RecoveryStandardFixture'], { encoding: 'utf8' });
  assert.equal(policyRun.status, 0, `Standard policy fixture failed:\n${policyRun.stdout}\n${policyRun.stderr}`);
  const rows = policyRun.stdout.trim().split('\n');
  assert.equal(rows.length, tags.length * 2, 'every canonical tag must exercise both directions');
  const decode = (row, prefix) => {
    assert.ok(row.startsWith(prefix + ' '));
    return Buffer.from(row.slice(prefix.length + 1), 'base64').toString('utf8');
  };
  const sent = [], events = [], listeners = new Map();
  const pageWindow = {
    DshaLanguage: { postMessage: value => sent.push(value) },
    addEventListener(name, fn) { assert.ok(!listeners.has(name), 'selection listener must not be duplicated'); listeners.set(name, fn); },
    dispatchEvent(event) { events.push(event.type); listeners.get(event.type)?.(event); },
  };
  const pageLocation = { protocol: 'http:', hostname: 'localhost' };
  for (const field of ['cookie', 'authorization', 'apiKey']) {
    Object.defineProperty(pageWindow, field, { get() { assert.fail(`locale bridge accessed ${field}`); } });
  }
  const document = {};
  Object.defineProperty(document, 'cookie', { get() { assert.fail('locale bridge read document.cookie'); } });
  for (const field of ['href', 'search', 'hash']) {
    Object.defineProperty(pageLocation, field, { get() { assert.fail(`locale bridge accessed location.${field}`); } });
  }
  const context = vm.createContext({ window: pageWindow, document, location: pageLocation,
    CustomEvent: class { constructor(type) { this.type = type; } } });
  for (const [index, tag] of tags.entries()) {
    const pageScript = decode(rows[index * 2], 'PAGE');
    const bridge = decode(rows[index * 2 + 1], 'BRIDGE');
    assert.doesNotMatch(pageScript + bridge, /cookie|authorization|api.?key/i, 'language-only scripts remain credential-blind');
    vm.runInContext(pageScript, context);
    assert.equal(pageWindow.__DSHA_LANGUAGE__, tag);
    vm.runInContext(bridge, context);
    assert.equal(pageWindow.__DSHA_LANGUAGE__, tag);
    for (const location of [{ protocol: 'http:', hostname: '127.0.0.1' }, { protocol: 'https:', hostname: 'localhost' }]) {
      const foreignWindow = { dispatchEvent() { assert.fail('foreign page must not receive language'); } };
      vm.runInNewContext(pageScript, { window: foreignWindow, location });
      assert.equal(foreignWindow.__DSHA_LANGUAGE__, undefined);
    }
  }
  assert.equal(events.length, tags.length * 2);
  assert.ok(events.every(name => name === 'dsha-language'));
  assert.deepEqual(sent, [], 'native language injection must not echo back');
  assert.equal(listeners.size, 1);
  for (const tag of [...tags, ...invalidTags]) listeners.get('dsha-language-selected')({ detail: tag });
  assert.deepEqual(sent, tags, 'only the ten canonical tags may cross the Standard language bridge');
  delete pageWindow.DshaLanguage;
  assert.doesNotThrow(() => listeners.get('dsha-language-selected')({ detail: 'en' }), 'an unavailable native listener must not crash the page');
  assert.doesNotMatch(listener + sync + language, /cookie|authorization|api.?key/i);

  // 来源隔离与生命周期：保留页面只 detach；代次变化、renderer 退出与最终 close 必须释放旧桥。
  assert.match(load, /setAcceptThirdPartyCookies\(view, false\)/);
  assert.doesNotMatch(load, /setCookie\s*\(/, 'formal-host auth cookie must never be copied to localhost');
  assert.match(surface, /setAllowFileAccess\(false\)/);
  assert.match(surface, /setAllowContentAccess\(false\)/);
  assert.match(surface, /setMixedContentMode\(WebSettings\.MIXED_CONTENT_NEVER_ALLOW\)/);
  assert.doesNotMatch(surface, /addJavascriptInterface/);
  assert.match(surface, /return !origin\.isEmpty\(\)\s*&& \(url\.equals\(origin\.substring\(0, origin\.length\(\) - 1\)\)\s*\|\| url\.startsWith\(origin\)\);/);
  assert.equal((surface.match(/return !allowed\(/g) || []).length, 2, 'both navigation overloads enforce emergency origin');
  assert.match(load, /if \(view == null \|\| loaded\) return;/);
  assert.match(load, /loaded = true;\s*view\.loadUrl\(localAuth\);/);
  assert.match(detach, /error = null;/);
  assert.match(detach, /microphoneRequests\.cancel\(\);\s*microphoneRequests = null;/);
  assert.match(detach, /removeWebMessageListener\(view, "DshaLanguage"\)/);
  assert.match(detach, /view\.setWebChromeClient\(null\)/);
  assert.match(detach, /removeView\(view\)/);
  assert.match(detach, /context\.setBaseContext\(app\)/);
  assert.doesNotMatch(detach, /view\.destroy\(\)|loaded = false/);
  assert.match(surface, /public void close\(\)\s*\{\s*detach\(\);\s*if \(script != null\) script\.remove\(\);\s*if \(view != null\) view\.destroy\(\);\s*view = null;\s*loaded = false;/);
  assert.match(surface, /onRenderProcessGone\([\s\S]*microphoneRequests\.cancel\(\);[\s\S]*fail\("RECOVERY_RENDERER_GONE"\);[\s\S]*web\.destroy\(\);\s*view = null;\s*loaded = false;/);
  assert.match(surface, /public boolean back\(\)\s*\{\s*if \(view != null && view\.canGoBack\(\)\)\s*\{\s*view\.goBack\(\);\s*return true;\s*\}\s*return false;/);
  assert.match(activity, /protected void onCleared\(\)\s*\{\s*if \(browser != null\) browser\.close\(\);/);
  assert.match(activity, /if \(!identity\.equals\(retained\.identity\)\)\s*\{\s*if \(retained\.browser != null\) retained\.browser\.close\(\);\s*retained\.browser = null;\s*retained\.identity = identity;/);
  assert.match(activity, /if \(!retained\.identity\.equals\(state\.instanceId \+ ":" \+ state\.generation\)\s*\|\| !state\.ready\)\s*\{\s*if \(retained\.browser != null\)\s*\{\s*retained\.browser\.close\(\);\s*retained\.browser = null;/);
  assert.match(activity, /protected void onDestroy\(\)\s*\{\s*if \(retained != null && retained\.browser != null\) retained\.browser\.detach\(\);\s*if \(microphone != null\) microphone\.close\(\);/);
  assert.doesNotMatch(surface + emergency, /HarnessController|HttpShellService|WebFileActions|StartupPageGate/);
  // 此处读到的策略同时用于上面的真实 Java 编译，禁止桥退回凭据读取。
  assert.doesNotMatch(policy, /getCookie|authorization|api.?key/i);
  console.log('PASS Standard recovery WebView origin-bound ten-language bridge, credential isolation, and browser lifecycle source contracts');
} finally {
  const resolved = path.resolve(temp);
  if (!resolved.startsWith(tempRoot + path.sep)) throw new Error('refusing to remove fixture path outside temp root');
  rmSync(resolved, { recursive: true, force: true });
}
