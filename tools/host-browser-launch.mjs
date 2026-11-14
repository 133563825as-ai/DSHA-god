// 仅宿主测试使用；不修改生产浏览器、设备权限或业务断言。
import fs from 'node:fs';
import path from 'node:path';

export function hostBrowserConfig(env = process.env, requiredEngine) {
  const engines = ['chromium', 'firefox'];
  const legacy = env.DSHA_BROWSER;
  const legacyEngine = engines.includes(legacy) ? legacy : undefined;
  const legacyExecutable = legacy && !legacyEngine ? legacy : undefined;
  const engine = env.DSHA_BROWSER_ENGINE ?? legacyEngine ?? 'chromium';
  if (!engines.includes(engine) || requiredEngine && engine !== requiredEngine) throw Error('HOST_BROWSER_ENGINE_INVALID');
  if (legacyEngine && legacyEngine !== engine) throw Error('HOST_BROWSER_ENGINE_CONFLICT');
  const candidates = [env.DSHA_BROWSER_EXECUTABLE, legacyExecutable, env.DSHA_CHROME].filter(value => value !== undefined);
  if (candidates.some(value => !value || !path.isAbsolute(value))) throw Error('HOST_BROWSER_EXECUTABLE_ABSOLUTE_REQUIRED');
  if (new Set(candidates.map(value => path.resolve(value))).size > 1) throw Error('HOST_BROWSER_EXECUTABLE_CONFLICT');
  const executable = candidates[0];
  if (executable && !fs.statSync(executable).isFile()) throw Error('HOST_BROWSER_EXECUTABLE_FILE_REQUIRED');
  if (env.DSHA_BROWSER_ARGS !== undefined || env.DSHA_BROWSER_LAUNCH_ARGS !== undefined) throw Error('HOST_BROWSER_ARBITRARY_ARGS_UNSUPPORTED');
  const profile = env.DSHA_HOST_BROWSER_PROFILE ?? 'default';
  if (!['default', 'disable-gpu'].includes(profile) || profile === 'disable-gpu' && engine !== 'chromium') throw Error('HOST_BROWSER_PROFILE_INVALID');
  const options = { headless: true };
  if (executable) options.executablePath = executable;
  // 显式opt-in的唯一兼容参数；不开放任意args/ignoreDefaultArgs/关闭Web安全。
  if (profile === 'disable-gpu') options.args = ['--disable-gpu'];
  if (engine === 'firefox') options.firefoxUserPrefs = { 'ui.primaryPointerCapabilities': 1, 'ui.allPointerCapabilities': 1 };
  return { engine, profile, options };
}

export async function launchHostBrowser(playwright, { env = process.env, requiredEngine } = {}) {
  const { engine, options } = hostBrowserConfig(env, requiredEngine);
  return playwright[engine].launch(options);
}
