import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';

const directory = 'app/src/main/assets/builtin-plugins/dsh-web-mobile/';
const source = readFileSync(directory + 'lib/client.js', 'utf8');
const packageJson = JSON.parse(readFileSync(directory + 'package.json', 'utf8'));

function loadModules() {
  let plugin;
  const window = { __ModuleLoader__: { load(value) { plugin = value; } } };
  const sandbox = { window, console, performance: { now: () => 1000 } };
  vm.runInNewContext(source.replace('var __cache = {};',
    'window.__testModules = __modules; window.__testRequire = __localRequire; var __cache = {};'), sandbox);
  plugin.factory(() => ({}));
  return {modules: window.__testModules};
}

test('3.3.1 固定上游 bundle 保留 DSHA 移动端入口与头部让位', () => {
  assert.equal(packageJson.version, '3.3.1');
  assert.equal(packageJson.dshaUpstream.commit, '4e71eb67de7dac9d8b2572757c81e47aa32a32fb');
  assert.equal(packageJson.dshaUpstream.clientSha256, '7f82fac2100d70daf34c9c68cc402f6adb2302bf9913122d3402ab0926773c75');
  assert.equal(packageJson.dshaUpstream.ref, 'main');
  assert.equal(packageJson.dshaUpstream.releaseTag, 'v3.3.1');
  assert.equal(packageJson.dshaUpstream.releaseCommit, '4e71eb67de7dac9d8b2572757c81e47aa32a32fb');
  assert.equal(packageJson.dshaUpstream.commit, packageJson.dshaUpstream.releaseCommit); // 上游把修好的 3.4.0 内容重编号为 3.3.1，标签指向同一提交
  for (const marker of [
    'DSHA_GEAR_FIT_V1',
    'mobileNavLocales',
    'DSHAFileTransfer',
    'var(--dsha-safe-area-inset-top, env(safe-area-inset-top, 0px))',
    '[data-dsha-session-select]',
    'dsha-preset-header-anchor',
    'data-mobile-nav="file-upload"',
    'data-sidebar-right-expand',
    'dsh-web-mobile-panel-clearance',
    'IconPanelLeftOutlineRegular',
    'IconFolderOpenOutlineRegular',
    'IconDownloadOutlineRegular',
    'IconPaperclipOutlineRegular',
    'pickIcon',
    'icon-compat',
    'ComposerFileButton',
    '-webkit-tap-highlight-color: transparent',
    'filter: brightness(.92)',
    'bottom: -2px !important',
    'transform: rotate(180deg) !important',
    'session-log-downloads',
    'downloadMenu',
    'data-sidebar-right-open',
    '.dsh-pet-root',
    'findFloatingWidget',
  ]) assert.ok(source.includes(marker), marker);
  for (const stale of [
    "'data-mobile-nav': 'files', 'data-dsha-downloads': true",
    'dsh_client_ui_primitives_1.IconPanelLeftOutline16',
    'dsh_client_ui_primitives_1.IconFolderOpenOutline16',
    'dsh_client_ui_primitives_1.IconDownloadOutline16',
    'dsh_client_ui_primitives_1.IconPaperclipOutline16',
    'panel.getBoundingClientRect().left < window.innerWidth',
    'transform: scale(.66)',
    // 3.2.2 代的 DSHA 本地补丁：上游 3.3.1 已接管，或 build336 按「完全追随上游」删除。
    'DSHA_WIDE_TOUCH_ROW_ACTIONS_V1',
    'DSHA_BACKDROP_PRESS_V1',
    'DSHA_SESSION_INPUT_V4',
    'DSHA_FONT_FAMILY_ROW_V1',
    'var(--dsha-mobile-visible-vh, 100vh)',
  ]) assert.equal(source.includes(stale), false, stale);
  // 上游自己的手机搜索隐藏与会话打开流程保持原字节（C 基线不再接管）。
  assert.ok(source.includes('[class*="_searchRow"]'));
  assert.ok(source.includes("// DSHA detail=0 follows the host's keyboard/programmatic open path."));
  assert.equal(packageJson.peerDependencies['@deepseek-ai/dsh-client-runtime'], undefined);
  assert.ok(packageJson.peerDependencies['@deepseek-ai/dsh-client-ui-conversation'].includes('>=0.2.2-rc.1 <0.3.0-0'));
  // react 在宿主运行时树里没有实体：3.4.0/3.3.0 把它当必选 peer 会让依赖门槛判 missing，
  // 上游 3.3.1（c41f65c + be9b7fc）改为 optional，必须保持。
  assert.equal(packageJson.peerDependenciesMeta.react.optional, true);
  assert.equal(packageJson.peerDependencies['@deepseek-ai/dsh-client-ui-sidebar-right'], undefined);
  assert.ok(source.includes('function openFilesPanel'));
});

test('3.3.1 侧栏与文件面板手势使用同一方向/速度门槛', () => {
  const {modules} = loadModules();
  const swipe = {};
  modules['effects/sidebar-swipe.js'](() => ({}), {}, swipe);
  assert.equal(swipe.startZonePxFor(390), 176);
  assert.equal(swipe.classifySwipe({lockPx: 8, drawerOpen: false, viewportWidthPx: 390,
    openDistanceRatio: .16, openVelocity: .45}, {dx: 70, dy: 3, velX: 0}, false), 'open');
  assert.equal(swipe.classifySwipe({lockPx: 8, drawerOpen: true, viewportWidthPx: 390,
    closeDistanceRatio: .13, closeVelocity: .45}, {dx: -60, dy: 2, velX: 0}, false), 'close');
  assert.equal(swipe.classifyFilesSwipe({lockPx: 8, panelOpen: false, drawerOpen: false,
    viewportWidthPx: 390, distanceRatio: .16, velocity: .45}, {dx: -70, dy: 2, velX: 0}, false), 'files');
  assert.equal(swipe.slidingVelocity([{x: 0, t: 900}, {x: 20, t: 940}, {x: 50, t: 1000}], 60, 1000), .5);
});

test('3.3.1 拖动让位与水平滚动容器判定仍由纯函数负责', () => {
  const {modules} = loadModules();
  const swipe = {};
  modules['effects/sidebar-swipe.js'](() => ({}), {}, swipe);
  assert.equal(swipe.hitTestStart(20, 390, false, {startZonePx: 176}), true);
  assert.equal(swipe.hitTestStart(200, 390, false, {startZonePx: 176}), false);
});

test('3.3.1 DOM reconciler 按 dirty scope 合并到一帧', () => {
  const {modules} = loadModules();
  const reconciler = {};
  modules['core/reconciler-core.js'](() => ({}), {}, reconciler);
  let frames = 0, all = 0, classes = 0, queued = null;
  const core = reconciler.createReconcilerCore({requestFrame(run) {
    frames++; queued = run; return () => { queued = null; };
  }});
  core.register({name: 'all', ensure: () => { all++; }, dispose: () => {}});
  core.register({name: 'class', scopes: ['class'], ensure: () => { classes++; }, dispose: () => {}});
  core.activate();
  assert.equal(all, 1); assert.equal(classes, 1);
  core.note(['text']);
  queued();
  assert.equal(frames, 1); assert.equal(all, 2); assert.equal(classes, 1);
  core.note(['class', 'style']);
  queued();
  assert.equal(frames, 2); assert.equal(all, 3); assert.equal(classes, 2);
  core.deactivate();
});

// 验证实际部署 bundle 中的新缩放模块，不以下载到的源码代替产物。
test('3.3.1 原图缩放保持触点锚定，缩回原尺寸必须清除平移', () => {
  const {modules} = loadModules();
  const zoom = {};
  modules['core/image-zoom-core.js'](() => ({}), {}, zoom);
  const before = {scale:1.5, x:12, y:-8}, anchor = {x:40, y:-24};
  const local = {x:(anchor.x-before.x)/before.scale, y:(anchor.y-before.y)/before.scale};
  const after = zoom.zoomAround(before, 1.6, anchor);
  assert.ok(Math.abs(after.x + after.scale*local.x - anchor.x) < 1e-9);
  assert.ok(Math.abs(after.y + after.scale*local.y - anchor.y) < 1e-9);
  const reset = zoom.zoomAround(after, .01, anchor);
  assert.equal(reset.scale, 1); assert.equal(reset.x, 0); assert.equal(reset.y, 0);
  const invalid = zoom.zoomAround(before, Number.NaN, anchor);
  assert.equal(invalid.scale, before.scale); assert.equal(invalid.x, before.x); assert.equal(invalid.y, before.y);
});

test('3.3.1 缩放平移受实际可见边界限制，双击第二次返回原图', () => {
  const {modules} = loadModules();
  const zoom = {};
  modules['core/image-zoom-core.js'](() => ({}), {}, zoom);
  const clamped = zoom.clampTranslation({scale:4, x:9999, y:-9999}, {width:300,height:600});
  assert.equal(clamped.x, 450); assert.equal(clamped.y, -900);
  assert.equal(zoom.clampScale(Number.NaN), 1); assert.equal(zoom.clampScale(99), 4);
  assert.equal(zoom.pinchFactor({x:0,y:0}, {x:10,y:10}), 1);
  const doubleTap = zoom.doubleTapTarget(zoom.identityZoom());
  assert.equal(doubleTap.scale, 2.5);
  const reset = zoom.doubleTapTarget(doubleTap);
  assert.equal(reset.scale, 1); assert.equal(reset.x, 0); assert.equal(reset.y, 0);
});
