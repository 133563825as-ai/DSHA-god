// 测试包性能采样网页脚本的统计函数与 start/stop 流程（假 performance / PerformanceObserver）。
// 用法：node tools/test-perf-probe.mjs
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const probe = require(path.join(root, 'app/src/main/res/raw/dsha_perf_probe.js'));

// 百分位与原生 FrameStats 同为最近秩法。
assert.equal(probe.percentile([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 50), 5);
assert.equal(probe.percentile([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 90), 9);
assert.equal(probe.percentile([], 50), 0);

// rAF：120 Hz 预算 8.33ms，两个长间隔（>12.5ms），其中一个超过 2 倍。
const raf = probe.rafSummary([8.3, 8.4, 8.3, 13, 25, 8.3], 1000 / 120);
assert.equal(raf.count, 6);
assert.equal(raf.longIntervals, 2);
assert.equal(raf.over2xIntervals, 1);
assert.equal(raf.max, 25);

// 来源归类：插件、合并请求、DSH 本体。
assert.deepEqual(probe.classifySource('/plugins/dsh-web-mobile/lib/client.js'), { key: 'plugin:dsh-web-mobile', mobile: true });
const combo = probe.classifySource('/plugins/??dsh-web-mobile/lib/client.js,dsh-status-overlay/lib/client.js');
assert.equal(combo.mobile, true);
assert.equal(combo.combo, true);
assert.equal(combo.key, 'combo:dsh-web-mobile,dsh-status-overlay');
assert.equal(probe.classifySource('/assets/index-abc.js').key, 'dsh:/assets/index-abc.js');
assert.equal(probe.classifySource('').key, '(inline/unknown)');

// LoAF 按来源聚合。
const loaf = probe.summarizeLoaf([
  {
    startTime: 100, duration: 80, blockingDuration: 30, renderStart: 160, styleAndLayoutStart: 170,
    scripts: [
      { sourceURL: '/plugins/dsh-web-mobile/lib/client.js', sourceFunctionName: 'syncNav', invoker: 'ResizeObserver', duration: 40, forcedStyleAndLayoutDuration: 12 },
      { sourceURL: '/assets/index-abc.js', sourceFunctionName: 'render', invoker: 'Response.json.then', duration: 15 }
    ]
  },
  { startTime: 300, duration: 60, blockingDuration: 10, scripts: [{ sourceURL: '/plugins/dsh-web-mobile/lib/client.js', sourceFunctionName: 'syncNav', invoker: 'ResizeObserver', duration: 20 }] }
]);
assert.equal(loaf.count, 2);
assert.equal(loaf.totals.mobilePluginScriptMs, 60);
assert.equal(loaf.totals.dshScriptMs, 15);
assert.equal(loaf.totals.renderMs, 20);
assert.equal(loaf.totals.styleAndLayoutMs, 10);
assert.equal(loaf.bySource[0].source, 'plugin:dsh-web-mobile');
assert.equal(loaf.bySource[0].forcedLayoutMs, 12);
assert.equal(loaf.bySource[0].topFunctions[0].name, 'syncNav ← ResizeObserver');
assert.equal(loaf.bySource[0].topFunctions[0].count, 2);
assert.equal(loaf.worst[0].duration, 80);

// 事件延迟：processingStart - startTime。
const events = probe.summarizeEvents([
  { name: 'pointerdown', startTime: 10, processingStart: 14, processingEnd: 20, duration: 24 },
  { name: 'keydown', startTime: 50, processingStart: 80, processingEnd: 90, duration: 48 }
]);
assert.equal(events.byName.keydown.inputDelay.max, 30);
assert.equal(events.byName.pointerdown.processing.max, 6);
assert.equal(events.inputDelay.max, 30);

assert.equal(probe.chromeVersion('Mozilla/5.0 ... Chrome/142.0.7444.90 Mobile Safari/537.36'), '142.0.7444.90');
assert.equal(probe.chromeVersion('Firefox'), null);

// 完整 start/stop：模拟 rAF 与观察器回调。
function fakeEnv({ loafSupported }) {
  let now = 0;
  let rafQueue = [];
  const observers = [];
  class FakeObserver {
    constructor(cb) { this.cb = cb; this.records = []; observers.push(this); }
    observe(opts) { this.type = opts.type; this.opts = opts; }
    takeRecords() { const r = this.records; this.records = []; return r; }
    disconnect() { this.disconnected = true; }
  }
  FakeObserver.supportedEntryTypes = loafSupported ? ['event', 'long-animation-frame', 'longtask'] : ['event', 'longtask'];
  const document = {
    visibilityState: 'visible',
    querySelectorAll: () => [{ getAttribute: (n) => (n === 'src' ? '/plugins/dsh-web-mobile/lib/client.js' : null) }],
    querySelector: () => null,
    getElementsByTagName: () => ({ length: 1234 }),
    documentElement: { style: { getPropertyValue: () => '' } }
  };
  return {
    env: {
      performance: { now: () => now, getEntriesByType: () => [] },
      PerformanceObserver: FakeObserver,
      requestAnimationFrame: (cb) => { rafQueue.push(cb); return rafQueue.length; },
      cancelAnimationFrame: () => {},
      navigator: { userAgent: 'X Chrome/142.0.1.2 Mobile' },
      location: { pathname: '/session/1' },
      document, devicePixelRatio: 3, innerWidth: 412, innerHeight: 915
    },
    tick(ms) {
      now += ms;
      const q = rafQueue; rafQueue = [];
      q.forEach((cb) => cb(now));
    },
    observers
  };
}

{
  const fake = fakeEnv({ loafSupported: true });
  const p = probe.createProbe(fake.env);
  assert.equal(p.start(1000 / 120), 'started');
  assert.equal(p.start(1000 / 120), 'already-running');
  for (let i = 0; i < 10; i++) fake.tick(i === 5 ? 30 : 8.33);
  const loafObserver = fake.observers.find((o) => o.type === 'long-animation-frame');
  const eventObserver = fake.observers.find((o) => o.type === 'event');
  assert.ok(loafObserver && eventObserver);
  assert.equal(eventObserver.opts.durationThreshold, 16);
  assert.equal(fake.observers.some((o) => o.type === 'longtask'), false);
  loafObserver.cb({ getEntries: () => [{ startTime: 1, duration: 70, blockingDuration: 20, scripts: [] }] });
  // 停止时 takeRecords 的剩余条目也要计入。
  eventObserver.records.push({ name: 'input', startTime: 1, processingStart: 5, processingEnd: 9, duration: 32 });
  const out = JSON.parse(p.stop());
  assert.equal(p.isRunning(), false);
  assert.equal(out.chromeVersion, '142.0.1.2');
  assert.equal(out.mobile.loaded, true);
  assert.deepEqual(out.mobile.evidence, ['module:plugin:dsh-web-mobile']);
  assert.equal(out.raf.count, 9);
  assert.equal(out.raf.longIntervals, 1);
  assert.equal(out.longAnimationFrames.count, 1);
  assert.equal(out.events.count, 1);
  assert.equal(out.domNodes.end, 1234);
  assert.ok(loafObserver.disconnected && eventObserver.disconnected);
}

{
  const fake = fakeEnv({ loafSupported: false });
  const p = probe.createProbe(fake.env);
  p.start(16.7);
  const longtask = fake.observers.find((o) => o.type === 'longtask');
  assert.ok(longtask, 'LoAF 不可用时退回 longtask');
  longtask.cb({ getEntries: () => [{ duration: 120, attribution: [{ containerType: 'window', name: 'self' }] }] });
  const out = JSON.parse(p.stop());
  assert.equal(out.longAnimationFrames, undefined);
  assert.equal(out.longTasks.count, 1);
  assert.equal(out.longTasks.byContainer[0].totalMs, 120);
}

{
  const p = probe.createProbe(fakeEnv({ loafSupported: true }).env);
  assert.equal(JSON.parse(p.stop()).error, 'not-started');
}

console.log('PASS perf probe statistics, LoAF/longtask fallback, event timing and start/stop');
