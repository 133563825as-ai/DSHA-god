// 纯配置/假launch负控，不启动浏览器、不下载或操作设备。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { hostBrowserConfig, launchHostBrowser } from './host-browser-launch.mjs';

test('默认headless与独立engine/路径，旧变量不再双义', () => {
  assert.deepEqual(hostBrowserConfig({}), { engine: 'chromium', profile: 'default', options: { headless: true } });
  const executable = process.execPath;
  const selected = hostBrowserConfig({ DSHA_BROWSER_ENGINE: 'chromium', DSHA_BROWSER_EXECUTABLE: executable });
  assert.equal(selected.options.executablePath, executable);
  assert.deepEqual(hostBrowserConfig({ DSHA_BROWSER: executable }), selected);
  assert.deepEqual(hostBrowserConfig({ DSHA_CHROME: executable }), selected);
  assert.equal(hostBrowserConfig({ DSHA_BROWSER: 'firefox' }).engine, 'firefox');
});
test('engine/path冲突和非绝对路径fail closed', () => {
  assert.throws(() => hostBrowserConfig({ DSHA_BROWSER_ENGINE: 'webkit' }), /ENGINE_INVALID/);
  assert.throws(() => hostBrowserConfig({ DSHA_BROWSER: 'firefox', DSHA_BROWSER_ENGINE: 'chromium' }), /ENGINE_CONFLICT/);
  assert.throws(() => hostBrowserConfig({ DSHA_BROWSER_EXECUTABLE: 'chromium' }), /ABSOLUTE_REQUIRED/);
  assert.throws(() => hostBrowserConfig({ DSHA_BROWSER: '/legacy/browser', DSHA_BROWSER_EXECUTABLE: process.execPath }), /EXECUTABLE_CONFLICT/);
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'host-browser-config-'));
  try { assert.throws(() => hostBrowserConfig({ DSHA_BROWSER_EXECUTABLE: folder }), /FILE_REQUIRED/); }
  finally { assert.equal(path.dirname(folder), fs.realpathSync(os.tmpdir())); fs.rmdirSync(folder); }
});
test('仅显式chromium disable-gpu profile；不开放任意args或忽略默认保护', () => {
  assert.deepEqual(hostBrowserConfig({ DSHA_HOST_BROWSER_PROFILE: 'disable-gpu' }).options, { headless: true, args: ['--disable-gpu'] });
  for (const profile of ['--disable-web-security', 'in-process-gpu', '', 'no-sandbox']) assert.throws(() => hostBrowserConfig({ DSHA_HOST_BROWSER_PROFILE: profile }), /PROFILE_INVALID/);
  assert.throws(() => hostBrowserConfig({ DSHA_BROWSER_ENGINE: 'firefox', DSHA_HOST_BROWSER_PROFILE: 'disable-gpu' }), /PROFILE_INVALID/);
  for (const key of ['DSHA_BROWSER_ARGS', 'DSHA_BROWSER_LAUNCH_ARGS']) assert.throws(() => hostBrowserConfig({ [key]: '--disable-web-security' }), /ARBITRARY_ARGS_UNSUPPORTED/);
  assert.throws(() => hostBrowserConfig({ DSHA_BROWSER_ENGINE: 'firefox' }, 'chromium'), /ENGINE_INVALID/);
});
test('真实入口只向所选engine传审查后的配置，不重试失败launch', async () => {
  let count = 0;
  const playwright = { chromium: { async launch(options) { count++; assert.deepEqual(options, { headless: true, args: ['--disable-gpu'] }); throw Error('fixture-launch-failed'); } } };
  await assert.rejects(launchHostBrowser(playwright, { env: { DSHA_HOST_BROWSER_PROFILE: 'disable-gpu' } }), /fixture-launch-failed/);
  assert.equal(count, 1);
});
