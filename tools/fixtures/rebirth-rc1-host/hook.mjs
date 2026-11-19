// 仅fixture连接边界：真实锁定SettingsForms/ConfigEditor和精确生产hook；不替代迁移器。
import { testRuntime } from '../../test-runtime-fixture.mjs';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { readFile, writeFile, mkdir, symlink, lstat } from 'node:fs/promises';
import { join, resolve } from 'node:path';
const [checkout, root, state, expected, scenario = 'retained'] = process.argv.slice(2);
assert.ok(['retained', 'missing-ledger'].includes(scenario));
assert.ok(expected === '0' || expected === '1');
if (scenario === 'missing-ledger') assert.equal(expected, '0');
const runtime = testRuntime('raw');
const require = createRequire(join(runtime, 'package.json'));
const mod = name => import(pathToFileURL(require.resolve(name)));
const [{ ConfigEditor }, { boot, readProfilePatches }] = await Promise.all([
  mod('@deepseek-ai/dsh-config-editor'), mod('@deepseek-ai/dsh-app-boot'),
]);
const put = (file, value) => writeFile(file, JSON.stringify(value));
const home = join(root, '.dsh'), dir = join(home, 'profile');
try { await lstat(join(root, 'node_modules')); } catch (error) {
  if (error.code !== 'ENOENT') throw error;
  await symlink(join(runtime, 'node_modules'), join(root, 'node_modules'), 'dir');
}
await mkdir(join(dir, 'node_modules'), { recursive: true });
const bundle = join(root, 'fixture-bundle');
try { await lstat(bundle); } catch (error) {
  if (error.code !== 'ENOENT') throw error;
  await mkdir(bundle);
  const plugin = join(root, 'first.mjs');
  await writeFile(plugin, "import z from '@deepseek-ai/schemastery';export const name='first';export const Config=z.object({model:z.string().default('initial').volatile(),maxTokens:z.number().min(1).default(1024).volatile()});export function apply(){}");
  await put(join(bundle, 'package.json'), { name: 'fixture-bundle', version: '1.0.0', dsh: { bundle: { patch: 'cordis.patch.yml' } } });
  await put(join(bundle, 'cordis.patch.yml'), [{ insert: [{ id: 'first', name: pathToFileURL(plugin).href, config: {} }] }]);
  await symlink(bundle, join(dir, 'node_modules', 'fixture-bundle'), 'dir');
  await put(join(dir, 'package.json'), { name: 'fixture-profile', private: true, dsh: { profile: { bundles: ['fixture-bundle'] } } });
  await writeFile(join(dir, 'cordis.patch.yml'), '[]\n');
  await writeFile(join(dir, 'cordis.yml'), '[]\n');
}
const installAnchor = join(root, 'installation.json');
await put(installAnchor, { name: '@deepseek-ai/dsh', version: '0.2.2-rc.1' });
const profile = { name: 'web', dir, home, patchPath: join(dir, 'cordis.patch.yml'), overlays: [], startedBundles: ['fixture-bundle'], installAnchor };
// 逐个验证before唯一命中；只映射生产固定guest模块URL与stateRoot选项。
const recipe = JSON.parse(await readFile(join(checkout, 'app/src/main/assets/rc1-settings-migration-patch.json'), 'utf8'));
let patched = await readFile(require.resolve('@deepseek-ai/dsh-settings'), 'utf8');
for (const patch of recipe.patches) {
  assert.equal(patched.split(patch.before).length, 2);
  patched = patched.replace(patch.before, patch.after);
}
const fixed = 'file:///root/.dsh/rc1-settings-migration.cjs';
assert.equal(patched.split(fixed).length, 2);
const wrapper = join(root, 'hook-wrapper.cjs');
await writeFile(wrapper, `const real=require(${JSON.stringify(resolve(checkout,'app/src/main/assets/rc1-settings-migration.cjs'))});module.exports={migrate:async(s,p,a)=>{const result=await real.migrate(s,p,a,{stateRoot:${JSON.stringify(state)}});globalThis.__rc1HostMigrationResult=result;return result;}};`);
patched = patched.replace(fixed, pathToFileURL(wrapper).href);
const module = join(root, 'settings-patched.mjs'); await writeFile(module, patched);
const { SettingsForms } = await import(pathToFileURL(module));
const ctx = await boot('dsh', join(dir, 'cordis.yml'), readProfilePatches('dsh', profile), async c => { c.provide('profileContext', profile); });
try {
  await ctx.plugin(ConfigEditor, {}); await ctx.loader.await();
  const settings = Object.create(SettingsForms.prototype);
  settings.ownerContext = ctx; settings.revisions = new Map(); settings.presentations = new Map();
  let updates = 0;
  const update = settings.update.bind(settings);
  settings.update = async (...args) => { updates++; return update(...args); };
  // 第二次独立进程先模拟用户后改；该update不计作迁移派发。
  if (expected === '0') {
    const row = settings.describe().find(row => row.ns === 'first');
    await update('first', { model: 'user-later' }, row.revision);
    assert.equal(settings.describe().find(row => row.ns === 'first').value.model, 'user-later');
  }
  delete globalThis.__rc1HostMigrationResult;
  await SettingsForms.prototype.importLegacyDocument.call(settings);
  assert.equal(updates, Number(expected));
  assert.equal(settings.describe().find(row => row.ns === 'first').value.model,
    expected === '0' ? 'user-later' : 'old');
  const current = JSON.parse(await readFile(join(state, 'current.json'), 'utf8'));
  const ledgerPath = join(state, 'generations', current.generation, 'settings-result.json');
  if (scenario === 'missing-ledger') {
    assert.equal(current.startupContext.mode, 'history-only');
    assert.equal(globalThis.__rc1HostMigrationResult?.status, 'unknown');
    assert.equal(globalThis.__rc1HostMigrationResult?.reason, 'DSHA_MIGRATION_LEDGER_MISSING');
    await assert.rejects(readFile(ledgerPath), { code: 'ENOENT' });
  } else {
    const ledger = JSON.parse(await readFile(ledgerPath, 'utf8'));
    assert.equal(ledger.status, 'verified');
  }
  console.log('HOOK_PASS updates=' + updates + ' mode=' + current.startupContext.mode + ' scenario=' + scenario);
} finally { await ctx.fiber.dispose(); }
