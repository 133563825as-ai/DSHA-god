// DSHA_GEAR_OWNER_V1：两个活动实例不得互相删除齿轮再重挂。
// 只跑真实生成器输出（applyMobileClientPatches）在合成 DOM/RAF 中的任务；不启动浏览器、
// 不连接设备、不读取用户数据。稳定后多轮 flush 必须零 DOM mutation。
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import {applyMobileClientPatches} from './apply-mobile-client-patches.mjs';

const upstream = readFileSync(new URL('./mobile-plugin-upstream/lib/client.js', import.meta.url));
const generated = applyMobileClientPatches(upstream);
assert.match(generated, /DSHA_GEAR_OWNER_V1/, '真实生成器必须输出共享 owner 差异');
assert.ok(!generated.includes('function createDrawerQuickSettingsTask(ctx) {\n    const t = ctx.locale.bind'),
  '生成器没有落 owner 前导');

let mutations = 0;
let onMutation = () => {};

class FakeElement {
  constructor(tag) {
    this.tagName = tag;
    this.children = [];
    this.attributes = new Map();
    this.listeners = new Map();
    this.parentElement = null;
    this.width = 0;
    this.computed = {
      display: 'inline-flex',
      visibility: 'visible',
      paddingLeft: '0px',
      paddingRight: '0px',
      marginLeft: '0px',
      marginRight: '0px',
    };
    this.dataset = new Proxy({}, {
      set: (target, key, value) => {
        target[key] = value;
        mutations += 1;
        onMutation();
        return true;
      },
      deleteProperty: (target, key) => {
        delete target[key];
        mutations += 1;
        onMutation();
        return true;
      },
    });
  }
  setAttribute(name, value) {
    this.attributes.set(name, String(value));
    mutations += 1;
    onMutation();
  }
  getAttribute(name) {
    return this.attributes.has(name) ? this.attributes.get(name) : null;
  }
  hasAttribute(name) {
    return this.attributes.has(name);
  }
  removeAttribute(name) {
    if (this.attributes.delete(name)) {
      mutations += 1;
      onMutation();
    }
  }
  appendChild(child) {
    child.parentElement = this;
    this.children.push(child);
    mutations += 1;
    onMutation();
    return child;
  }
  remove() {
    if (this.parentElement) {
      const at = this.parentElement.children.indexOf(this);
      if (at >= 0) {
        this.parentElement.children.splice(at, 1);
        mutations += 1;
        onMutation();
      }
      this.parentElement = null;
    }
  }
  addEventListener(type, handler) {
    this.listeners.set(type, handler);
  }
  removeEventListener(type) {
    this.listeners.delete(type);
  }
  getBoundingClientRect() {
    return {width: this.width, height: 20};
  }
  set innerHTML(value) {
    this.markup = value;
  }
  get innerHTML() {
    return this.markup ?? '';
  }
  querySelector() {
    return null;
  }
  querySelectorAll(selector) {
    if (selector === '[data-mobile-nav="quick-settings-wrap"]') {
      return this.children.filter((child) => child.dataset.mobileNav === 'quick-settings-wrap');
    }
    return [];
  }
  get isConnected() {
    return this.connected !== false;
  }
  closest() { return null; }
  contains(target) { return target === this || this.children.some((child) => child.contains(target)); }
  click() {}
}

function createHarness() {
  const frame = new FakeElement('div');
  frame.setAttribute('data-mobile-nav', 'frame');
  const row = new FakeElement('div');
  frame.appendChild(row);
  frame.querySelector = (selector) => (selector === ROW_SELECTOR ? row : null);
  const documentListeners = new Map();
  const document = {
    documentElement: new FakeElement('html'),
    querySelector: (selector) => (selector === '[data-mobile-nav="frame"]' ? frame : null),
    querySelectorAll: () => [],
    createElement: (tag) => new FakeElement(tag),
    addEventListener: (type, callback) => {
      if (!documentListeners.has(type)) documentListeners.set(type, new Set());
      documentListeners.get(type).add(callback);
    },
    removeEventListener: (type, callback) => documentListeners.get(type)?.delete(callback),
  };
  const frames = [];
  const timers = new Map();
  let timer = 0;
  let scheduled = [];
  const localeCallbacks = [];
  const sandbox = {console, document, performance: {now: () => 10}, getComputedStyle: (element) => element.computed, HTMLElement: FakeElement};
  sandbox.window = {
    __ModuleLoader__: {load: ({factory}) => { sandbox.loaded = factory(() => ({})); }},
    setTimeout: (run, millis) => {
      const id = ++timer;
      timers.set(id, run);
      return id;
    },
    clearTimeout: (id) => timers.delete(id),
    requestAnimationFrame: (run) => {
      const id = ++timer;
      frames.push([id, run]);
      return id;
    },
    cancelAnimationFrame: (id) => {
      const at = frames.findIndex(([frameId]) => frameId === id);
      if (at >= 0) frames.splice(at, 1);
    },
  };
  sandbox.globalThis = sandbox;
  sandbox.setTimeout = sandbox.window.setTimeout;
  sandbox.clearTimeout = sandbox.window.clearTimeout;
  sandbox.requestAnimationFrame = sandbox.window.requestAnimationFrame;
  sandbox.cancelAnimationFrame = sandbox.window.cancelAnimationFrame;
  vm.createContext(sandbox);
  vm.runInContext(
    generated.replace('var __cache = {};', 'globalThis.__mobileRequire = __localRequire; var __cache = {};'),
    sandbox,
    {filename: 'generated-client.js'},
  );
  const mod = sandbox.__mobileRequire('./effects/drawer-quick-settings.js');
  const reloadMod = () => {
    // 独立模块缓存、同一 window：模拟两个 bundle 实例共用 owner。
    vm.runInContext(
      generated.replace('var __cache = {};', 'globalThis.__mobileRequire = __localRequire; var __cache = {};'),
      sandbox,
      {filename: 'generated-client-reloaded.js'},
    );
    return sandbox.__mobileRequire('./effects/drawer-quick-settings.js');
  };
  const ctx = {locale: {bind: () => (key) => key, subscribe: (callback) => {
    localeCallbacks.push(callback);
    return () => {};
  }}};
  // 加载同一真实生成 bundle 的 core，不另写 activate/deactivate 模型。
  const {createReconcilerCore} = sandbox.__mobileRequire('./core/reconciler-core.js');
  const createCore = () => createReconcilerCore({
    requestFrame: (run) => {
      const id = sandbox.window.requestAnimationFrame(run);
      return () => sandbox.window.cancelAnimationFrame(id);
    },
    onError: (name, error) => { throw error; },
  });
  const tasks = [];
  const wraps = () =>
    row.children.filter((child) => child.dataset.mobileNav === 'quick-settings-wrap');
  const flush = () => {
    let guard = 0;
    while (frames.length && guard++ < 100) {
      const batch = frames.splice(0);
      for (const [, run] of batch) run();
    }
  };
  onMutation = () => {
    scheduled = [...tasks];
  };
  const settle = () => {
    for (let round = 0; round < 12; round += 1) {
      const pending = scheduled;
      scheduled = [];
      for (const task of pending) task.ensure();
      flush();
      if (pending.length === 0 && frames.length === 0) break;
    }
  };
  // 提取真实 bundle 的整个 adapter 模块；只替换平台 effect 注册边界与调度器，
  // 不手写 run/observer/cleanup，以便直接检出外层 RAF 复活 stable task。
  const adapterObservers = [];
  const effectFactories = [];
  let adapterEnsures = 0;
  const adapterModule = generated.split('__modules["effects/tablet-quick-settings.js"] = function (require, module, exports) {')[1]
    .split('\n};\n__modules[')[0];
  assert.ok(adapterModule, '真实 tablet adapter 模块必须存在');
  const adapter = {exports: {}};
  sandbox.__adapterRequire = (name) => {
    if (name === './platform/breakpoints.js') return {
      TABLET_TOUCH_QUERY: 'test-tablet',
      installMobileEffect: (context, name, factory) => effectFactories.push(factory),
    };
    if (name === './effects/drawer-quick-settings.js') return {
      createDrawerQuickSettingsTask: (context) => {
        const task = mod.createDrawerQuickSettingsTask(context);
        return {ensure: () => { adapterEnsures += 1; task.ensure(); }, dispose: () => task.dispose()};
      },
    };
    throw Error('unexpected adapter dependency: ' + name);
  };
  sandbox.__adapterModule = adapter;
  sandbox.MutationObserver = class {
    constructor(callback) { this.callback = callback; adapterObservers.push(this); }
    observe() {}
    disconnect() { this.disconnected = true; }
  };
  vm.runInContext('(function(require, module, exports) {' + adapterModule
    + '\n})(__adapterRequire, __adapterModule, __adapterModule.exports)', sandbox);
  adapter.exports.installTabletQuickSettings(ctx);
  assert.equal(effectFactories.length, 1);
  const startTablet = () => {
    const cleanup = effectFactories[0]();
    return {cleanup, observer: adapterObservers.at(-1)};
  };
  const fireDocument = (type, target) => {
    for (const callback of [...(documentListeners.get(type) ?? [])]) callback({target});
  };
  return {frame, row, mod, reloadMod, ctx, tasks, wraps, flush, settle, document, createCore, localeCallbacks,
    startTablet, fireDocument, adapterEnsures: () => adapterEnsures,
    requestFrame: sandbox.window.requestAnimationFrame, pendingTimers: () => timers.size,
    owner: () => sandbox.window.__dshaMobileGearOwnerV1.owner,
    callbacks: () => ({timers: [...timers.values()], frames: frames.map(([, run]) => run)}),
    pendingFrames: () => frames.length};
}

const ROW_SELECTOR = '[class*="sidebarCol"] [class*="triggerRow"]';

function launcher() {
  const element = new FakeElement('button');
  element.setAttribute('aria-haspopup', 'menu');
  element.width = 110;
  return element;
}

function createTasks(harness, count) {
  for (let index = 0; index < count; index += 1)
    harness.tasks.push(harness.mod.createDrawerQuickSettingsTask(harness.ctx));
  return harness.tasks;
}

test('两个实例交替 ensure 后稳定：多轮 flush 零新增 DOM mutation', () => {
  const harness = createHarness();
  harness.row.width = 260;
  harness.row.appendChild(launcher());
  const [first, second] = createTasks(harness, 2);
  first.ensure();
  second.ensure();
  harness.settle();
  assert.equal(harness.wraps().length, 1, '同一行必须只有一颗齿轮');
  const wrap = harness.wraps()[0];
  assert.equal(wrap.parentElement, harness.row);
  const stable = mutations;
  for (let round = 0; round < 10; round += 1) {
    first.ensure();
    second.ensure();
    first.ensure();
    harness.flush();
    harness.flush();
  }
  assert.equal(mutations, stable, '稳定后 loser/owner 的 ensure 不得再写 DOM');
  assert.equal(harness.wraps().length, 1);
  assert.equal(harness.wraps()[0], wrap, '齿轮被另一个实例替换过');
  assert.equal(harness.pendingFrames(), 0, '稳定后不得继续排队 RAF');

  const before = mutations;
  first.ensure();
  assert.equal(mutations, before, '稳定 owner 的重复 ensure 必须无写入');
});

test('当前 owner 退休后旧 dispose 不清新 owner，下一有效实例只接管一次', () => {
  const harness = createHarness();
  harness.row.width = 260;
  harness.row.appendChild(launcher());
  const [first, second] = createTasks(harness, 2);
  second.ensure();
  harness.settle();
  assert.equal(harness.wraps().length, 1);

  first.dispose();
  assert.equal(harness.wraps().length, 1, '迟到旧 dispose 清掉了新 owner 的齿轮');
  const before = mutations;
  second.ensure();
  assert.equal(harness.wraps().length, 1);
  assert.equal(mutations, before, 'owner 存活时重复 ensure 必须幂等');
  harness.settle();
  assert.equal(harness.wraps().length, 1);

  second.dispose();
  assert.equal(harness.wraps().length, 0, 'owner dispose 后应清空齿轮');
  const third = harness.mod.createDrawerQuickSettingsTask(harness.ctx);
  harness.tasks.push(third);
  third.ensure();
  harness.settle();
  assert.equal(harness.wraps().length, 1, '新实例没有接管');
  const after = mutations;
  first.ensure();
  second.ensure();
  assert.equal(mutations, after, '旧 task 再 ensure 不得抢占当前有效 owner');
});

test('移动帧连接状态切换：离线 ensure 零 mutation，重连 owner 保持幂等', () => {
  const harness = createHarness();
  harness.row.width = 260;
  harness.row.appendChild(launcher());
  const [first] = createTasks(harness, 1);
  first.ensure();
  harness.settle();
  assert.equal(harness.wraps().length, 1);

  harness.frame.connected = false;
  const before = mutations;
  first.ensure();
  harness.flush();
  assert.equal(mutations, before, '离开移动帧后 ensure 不得改 DOM');
  assert.equal(harness.wraps().length, 1);

  harness.frame.connected = true;
  first.ensure();
  harness.settle();
  assert.equal(harness.wraps().length, 1, '回到移动帧后应恢复挂载');
  assert.equal(harness.pendingFrames(), 0, '恢复后不得留下续排 RAF');
});

test('真实 core 保留原 registered task：deactivate→activate 取得新 epoch，旧异步/点击不写新 owner', () => {
  const harness = createHarness();
  harness.row.width = 260;
  const trigger = launcher();
  harness.row.appendChild(trigger);
  harness.row.querySelector = () => trigger;
  const [task] = createTasks(harness, 1);
  const core = harness.createCore();
  core.register(task);
  core.activate();
  assert.equal(harness.wraps().length, 1);
  const oldOwner = harness.owner();
  const oldButton = harness.wraps()[0].children[0];
  const oldClick = oldButton.listeners.get('click');
  const oldPress = oldButton.listeners.get('pointerdown');
  oldPress();
  harness.fireDocument('pointerdown', trigger);
  harness.fireDocument('pointerup', trigger);
  oldButton.listeners.get('pointerup')();
  oldClick();
  const queued = harness.callbacks();
  assert.ok(queued.timers.length > 0, '必须实际排过按下态 timer');
  assert.ok(queued.frames.length > 0, '必须实际排过点击链 RAF');

  core.deactivate();
  assert.equal(harness.wraps().length, 0, '真实 deactivate 必须 dispose 当前任务');
  assert.equal(harness.owner(), undefined);
  assert.equal(core.size, 1, 'deactivate 不得删 registered task');
  core.activate();
  assert.equal(harness.wraps().length, 1, '同一 registered task 再 activate 必须恢复齿轮');
  assert.notEqual(harness.owner(), oldOwner, '同一 task 的新激活必须拿新 owner token');
  const wrap = harness.wraps()[0];
  const newButton = wrap.children[0];
  newButton.listeners.get('pointerdown')();
  harness.fireDocument('pointerdown', trigger);
  newButton.listeners.get('click')();
  const pendingFrames = harness.pendingFrames();
  const before = mutations;
  for (const callback of queued.timers) callback();
  for (const callback of queued.frames) callback();
  oldClick();
  oldPress();
  harness.localeCallbacks[0]();
  assert.equal(mutations, before, '迟到旧 epoch timer/RAF/click/locale 不得写新 owner');
  assert.equal(harness.wraps()[0], wrap);
  assert.ok(newButton.hasAttribute('data-mobile-nav-quick-pressed'), '旧 timer 清掉新按下态');
  assert.ok(harness.row.hasAttribute('data-mobile-nav-row-pressed'), '旧 epoch timer 清掉新账户行按下态');
  assert.ok(harness.document.documentElement.hasAttribute('data-mobile-nav-quick'), '旧 RAF 清掉新点击标记');
  assert.equal(harness.pendingFrames(), pendingFrames, '旧点击不能续排新 RAF');

  for (let cycle = 0; cycle < 3; cycle += 1) {
    core.deactivate();
    assert.equal(harness.wraps().length, 0);
    core.activate();
    assert.equal(harness.wraps().length, 1, '反复 tablet/mobile 不得永久停用任务');
  }
  core.deactivate();
});

test('两模块真实 core 活实例：旧 task dispose 不清新 owner，当前 task 停用确实清理', () => {
  const harness = createHarness();
  harness.row.width = 260;
  harness.row.appendChild(launcher());
  const [first] = createTasks(harness, 1);
  const second = harness.reloadMod().createDrawerQuickSettingsTask(harness.ctx);
  harness.tasks.push(second);
  const firstCore = harness.createCore();
  const secondCore = harness.createCore();
  const removeFirst = firstCore.register(first);
  const removeSecond = secondCore.register(second);
  firstCore.activate();
  secondCore.activate();
  harness.settle();
  assert.equal(harness.wraps().length, 1);
  const stable = mutations;
  for (let round = 0; round < 10; round += 1) {
    firstCore.note(['*']);
    secondCore.note(['*']);
    harness.flush();
  }
  assert.equal(mutations, stable, '两个真实活 core 稳态必须零额外 mutation');
  secondCore.deactivate(); // loser 也必须能再次激活，而不是 dispose 后永久退休。
  secondCore.activate();
  assert.equal(mutations, stable, 'loser 重激活不能争抢当前 owner');
  firstCore.deactivate();
  secondCore.note(['*']);
  harness.flush();
  assert.equal(harness.wraps().length, 1, '有效 loser 应在 owner 停用后接管');
  const owner = harness.owner();
  const before = mutations;
  first.dispose();
  removeFirst();
  removeFirst();
  assert.equal(harness.owner(), owner, '旧 dispose/unregister 不得释放其它 task owner');
  assert.equal(mutations, before, '旧 dispose 必须无副作用');
  firstCore.activate();
  assert.equal(firstCore.size, 0, '真实 unregister 后 activate 不应复活旧 task');
  assert.equal(harness.owner(), owner);
  removeSecond();
  assert.equal(harness.wraps().length, 0, '当前 task unregister 必须停止当前 epoch');
  assert.equal(harness.owner(), undefined);
  secondCore.deactivate();
  secondCore.activate();
  assert.equal(harness.wraps().length, 0, '已 unregister task 不能由 activate 重挂');
});

test('真实 tablet effect cleanup 取消自身外层 RAF，强交付旧 run/observer 也零 ensure/零 claim', () => {
  const harness = createHarness();
  harness.row.width = 260;
  harness.row.appendChild(launcher());
  const old = harness.startTablet();
  old.observer.callback();
  old.observer.callback();
  const queued = harness.callbacks().frames;
  assert.equal(queued.length, 1, '同 effect 的 observer 必须合并 RAF');
  let unrelatedRan = false;
  harness.requestFrame(() => { unrelatedRan = true; });
  old.cleanup();
  assert.ok(old.observer.disconnected);
  assert.equal(harness.wraps().length, 0);
  assert.equal(harness.pendingFrames(), 1, 'cleanup 必须实际取消本 effect RAF，只留其它 task');
  const ensures = harness.adapterEnsures();
  const before = mutations;
  queued[0](); // 即使取消的帧已被分发，旧闭包仍必须封印。
  old.observer.callback(); // disconnect 不能撤销已进入投递队列的 observer 回调。
  assert.equal(harness.adapterEnsures(), ensures, 'cleanup 后旧 run 不得调用 stable task.ensure');
  assert.equal(harness.owner(), undefined, 'cleanup 后旧 run 不得重新 claim owner');
  assert.equal(mutations, before);
  assert.equal(harness.pendingFrames(), 1, '只保留别的 task RAF，不能全局清场');
  harness.flush();
  assert.ok(unrelatedRan, '不能取消其它 task RAF');
  const next = harness.startTablet();
  const newOwner = harness.owner();
  assert.ok(newOwner, '新 effect 必须可合法激活');
  const stable = mutations;
  queued[0]();
  old.observer.callback();
  old.cleanup();
  assert.equal(harness.adapterEnsures(), ensures + 1, '旧 callback 不能向新期投递 ensure');
  assert.equal(harness.owner(), newOwner);
  assert.equal(mutations, stable);
  next.cleanup();
});

for (const released of [false, true]) {
  test(`普通 dispose 清账户行/齿轮按下态与 timer（${released ? 'pointerup 后等待' : '仍按住'}）`, () => {
    const harness = createHarness();
    harness.row.width = 260;
    const trigger = launcher();
    harness.row.appendChild(trigger);
    const [task] = createTasks(harness, 1);
    task.ensure();
    const button = harness.wraps()[0].children[0];
    harness.fireDocument('pointerdown', trigger);
    button.listeners.get('pointerdown')();
    if (released) {
      harness.fireDocument('pointerup', trigger);
      button.listeners.get('pointerup')();
    }
    assert.ok(harness.row.hasAttribute('data-mobile-nav-row-pressed'));
    assert.ok(button.hasAttribute('data-mobile-nav-quick-pressed'));
    const queued = harness.callbacks().timers;
    task.dispose();
    assert.equal(harness.row.hasAttribute('data-mobile-nav-row-pressed'), false, 'dispose 清 timer 后不能残留账户行按下态');
    assert.equal(button.hasAttribute('data-mobile-nav-quick-pressed'), false, '已移除的旧 button 也不留按下态');
    assert.equal(harness.pendingTimers(), 0, 'dispose 必须取消本期 timer');
    const next = harness.mod.createDrawerQuickSettingsTask(harness.ctx);
    next.ensure();
    harness.fireDocument('pointerdown', trigger);
    const owner = harness.owner();
    const before = mutations;
    task.dispose();
    for (const callback of queued) callback();
    assert.equal(harness.owner(), owner);
    assert.ok(harness.row.hasAttribute('data-mobile-nav-row-pressed'), '旧期清理不能抹新 owner 按下态');
    assert.equal(mutations, before);
    next.dispose();
  });
}

test('dispose 清本期实际按下的旧行，而不是重新查询到的宿主新行', () => {
  const harness = createHarness();
  harness.row.width = 260;
  const trigger = launcher();
  harness.row.appendChild(trigger);
  const [task] = createTasks(harness, 1);
  task.ensure();
  harness.fireDocument('pointerdown', trigger);
  const replacement = new FakeElement('div');
  replacement.setAttribute('data-mobile-nav-row-pressed', '');
  harness.frame.querySelector = () => replacement;
  task.dispose();
  assert.equal(harness.row.hasAttribute('data-mobile-nav-row-pressed'), false);
  assert.ok(replacement.hasAttribute('data-mobile-nav-row-pressed'), '不能清未被本期按下的新行');
});

test('生成器保留 DSHA_SCROLL_LIFECYCLE_V1 与 400→100 语义的源码块', () => {
  assert.match(generated, /DSHA_SCROLL_LIFECYCLE_V1/);
  assert.match(generated, /__dshaMobileScrollLifecycleV1/);
  assert.match(generated, /scrollLifecycle\.request\?\.cancel\(\)/);
  assert.doesNotMatch(generated, /__dshaMobileGearOwnerV1\?\.owner\?\.ensure/);
});
