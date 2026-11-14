import { testRuntime } from './test-runtime-fixture.mjs';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { mkdtemp, mkdir, readFile, writeFile, rm, symlink, readdir, stat } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { createHash, randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
const runtime = testRuntime('raw');
const require = createRequire(join(runtime, 'package.json'));
const mod = name => import(pathToFileURL(require.resolve(name)));
const [{ SettingsForms }, { ConfigEditor }, { boot, readProfilePatches }, { parse }] = await Promise.all([
  mod('@deepseek-ai/dsh-settings'), mod('@deepseek-ai/dsh-config-editor'), mod('@deepseek-ai/dsh-app-boot'), mod('yaml'),
]);
const { migrate } = createRequire(import.meta.url)('../app/src/main/assets/rc1-settings-migration.cjs');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const json = async file => JSON.parse(await readFile(file, 'utf8'));
const put = (file, value) => writeFile(file, JSON.stringify(value));
const normal = "z.object({model:z.string().default('initial').volatile(),temperature:z.number().min(0).default(1).volatile(),maxTokens:z.number().step(1).min(1).default(1024).volatile(),items:z.array(z.object({label:z.string().required()})).default([]).volatile(),fixed:z.string().default('untouched')})";

// 真实锁定SettingsForms.describe/update及ConfigEditor.edit/文件锁/原子持久化/Loader。
// 只引导本地synthetic插件，不装依赖、不读写真实profile，不伪称设备验收。
async function fixture(document, run, configs = { first: normal }) {
  const root = await mkdtemp(join(tmpdir(), 'dsha-rc1-settings-'));
  let ctx;
  try {
    await symlink(join(runtime, 'node_modules'), join(root, 'node_modules'), process.platform === 'win32' ? 'junction' : 'dir');
    const home = join(root, '.dsh'), dir = join(home, 'profile'), state = join(root, 'state');
    await mkdir(join(dir, 'node_modules'), { recursive: true });
    const bundle = join(root, 'fixture-bundle'); await mkdir(bundle);
    const rows = [];
    for (const [ns, schema] of Object.entries(configs)) {
      const file = join(root, `plugin-${ns}.mjs`);
      await writeFile(file, `import z from '@deepseek-ai/schemastery';export const name=${JSON.stringify(ns)};export const Config=${schema};export function apply(){}`);
      rows.push({ id: ns, name: pathToFileURL(file).href, config: {} });
    }
    await put(join(bundle, 'package.json'), { name: 'fixture-bundle', version: '1.0.0', dsh: { bundle: { patch: 'cordis.patch.yml' } } });
    await put(join(bundle, 'cordis.patch.yml'), [{ insert: rows }]);
    await symlink(bundle, join(dir, 'node_modules', 'fixture-bundle'), process.platform === 'win32' ? 'junction' : 'dir');
    const installAnchor = join(root, 'installation.json');
    await put(installAnchor, { name: '@deepseek-ai/dsh', version: '0.2.2-rc.1' });
    const profile = { name: 'web', dir, home, patchPath: join(dir, 'cordis.patch.yml'), overlays: [], startedBundles: ['fixture-bundle'], installAnchor };
    await put(join(dir, 'package.json'), { name: 'settings-fixture', private: true, dsh: { profile: { bundles: ['fixture-bundle'] } } });
    await writeFile(profile.patchPath, '[]\n'); await writeFile(join(dir, 'cordis.yml'), '[]\n');
    ctx = await boot('dsh', join(dir, 'cordis.yml'), readProfilePatches('dsh', profile), async context => { context.provide('profileContext', profile); });
    await ctx.plugin(ConfigEditor, {}); await ctx.loader.await();
    // 不安装SettingsForms旧importLegacyDocument启动钩子；待测迁移器是唯一导入入口。
    const settings = Object.create(SettingsForms.prototype);
    settings.ownerContext = ctx; settings.revisions = new Map(); settings.presentations = new Map();
    let dispatches = 0;
    const original = settings.update.bind(settings);
    settings.update = async (...args) => { dispatches++; return original(...args); };
    await writeFile(join(home, 'settings.yaml'), document);
    const generation = randomUUID(), folder = join(state, 'generations', generation);
    await mkdir(join(folder, 'snapshots'), { recursive: true });
    await writeFile(join(folder, 'snapshots', '0'), document);
    const sourceSha = hash(document), inputDigest = hash('synthetic-input');
    const current = { version: 2, dshHome: home, generation, startupId: 'synthetic', startupContext: { version: 3, generation, startupId: 'synthetic', inputFingerprint: inputDigest, settingsSha256: sourceSha, mode: 'write-allowed', proofDigest: hash(JSON.stringify({ generation, inputDigest, settingsSha256: sourceSha })) } };
    await put(join(state, 'current.json'), current);
    await put(join(folder, 'prepare.json'), { version: 2, generation, dshHome: home, dataRoot: { path: home, device: 'synthetic', inode: 'synthetic' }, inputs: { 'settings.yaml': sourceSha }, status: 'prepared', protectionComplete: true, inputDigest, sources: [{ kind: 'settings', path: 'settings.yaml', snapshot: 'snapshots/0', sha256: sourceSha }] });
    const ledger = join(folder, 'settings-result.json');
    const api = { settings, ctx, profile, home, root, state, folder, ledger, current, sourceSha, inputDigest,
      get dispatches() { return dispatches; },
      value(ns = 'first') { return settings.describe().find(row => row.ns === ns)?.value; },
      run(aliases = {}) { return migrate(settings, parse, aliases, { stateRoot: state }); },
      async history() { delete current.startupContext; await put(join(state, 'current.json'), current); },
      async ledgerWith(sections, extra = {}) { await put(ledger, { version: 2, generation, sourceSha256: sourceSha, status: Object.values(sections).every(row => row.status === 'verified') ? 'verified' : 'pending', sections, ...extra }); },
      async change(ns, patch) { const row = settings.describe().find(row => row.ns === ns); await original(ns, patch, row.revision); },
      async reopen() {
        await ctx.fiber.dispose();
        ctx = await boot('dsh', join(dir, 'cordis.yml'), readProfilePatches('dsh', profile), async context => { context.provide('profileContext', profile); });
        await ctx.plugin(ConfigEditor, {}); await ctx.loader.await();
        settings.ownerContext = ctx; settings.revisions.clear(); settings.presentations.clear();
      },
    };
    await run(api);
    await assert.rejects(stat(join(dir, 'package.json.lock')), { code: 'ENOENT' });
  } finally {
    if (ctx) await ctx.fiber.dispose();
    assert.ok(root.startsWith(join(tmpdir(), 'dsha-rc1-settings-')));
    await rm(root, { recursive: true, force: true });
  }
}
async function files(folder) {
  const result = {};
  async function walk(dir, prefix = '') {
    for (const entry of await readdir(dir, { withFileTypes: true })) {
      const key = prefix + entry.name, full = join(dir, entry.name);
      if (entry.isDirectory()) await walk(full, key + '/');
      else if (entry.isFile()) result[key] = { bytes: (await readFile(full)).toString('base64'), mtime: (await stat(full)).mtimeMs };
    }
  }
  await walk(folder); return result;
}

test('无secret合法流真实持久化、保留maxTokens及数组索引，重复不覆盖用户后改', () => fixture('first: {model: old, maxTokens: 2048, items: [{label: a}, {label: b}]}\n', async f => {
  const beforeUid = f.settings.describe()[0].schema.uid;
  assert.notEqual(f.settings.describe()[0].schema.uid, beforeUid);
  assert.equal((await f.run()).status, 'verified'); assert.equal(f.dispatches, 1);
  assert.equal(f.value().maxTokens, 2048); assert.deepEqual(f.value().items, [{ label: 'a' }, { label: 'b' }]);
  const disk = parse(await readFile(f.profile.patchPath, 'utf8'));
  assert.equal(disk[0].config.model, 'old'); assert.equal(disk[0].config.maxTokens, 2048);
  await f.reopen(); assert.equal(f.value().model, 'old'); assert.equal(f.value().maxTokens, 2048);
  await f.change('first', { model: 'user-later' }); await f.run();
  assert.equal(f.dispatches, 1); assert.equal(f.value().model, 'user-later');
  assert.deepEqual((await json(f.ledger)).expectedSections, ['first']);
}));
for (const text of ['schema failure', 'invalid value', 'No configurable plugin entry "first"', 'connection lost']) {
  test(`效果已发生后抛${text}：同有效票据总update一次`, () => fixture('first: {model: old}\n', async f => {
    const update = f.settings.update.bind(f.settings);
    f.settings.update = async (...args) => { await update(...args); throw Error(text); };
    assert.equal((await f.run()).status, 'unknown');
    assert.equal(f.value().model, 'old'); assert.match(await readFile(f.profile.patchPath, 'utf8'), /model: old/);
    assert.equal((await f.run()).status, 'unknown'); assert.equal(f.dispatches, 1);
    assert.equal((await json(f.ledger)).sections.first.status, 'unknown');
    const before = await files(f.home), ledger = await readFile(f.ledger, 'utf8');
    await f.history(); await f.run(); assert.deepEqual(await files(f.home), before); assert.equal(await readFile(f.ledger, 'utf8'), ledger); assert.equal(f.dispatches, 1);
  }));
}
test('history-only缺账本不新建、不rename、不调用describe', () => fixture('first: {model: old}\n', async f => {
  await f.history(); const home = await files(f.home), state = await files(f.state);
  f.settings.describe = () => { throw Error('history must not inspect live settings'); };
  assert.equal((await f.run()).reason, 'DSHA_MIGRATION_LEDGER_MISSING');
  assert.deepEqual(await files(f.home), home); assert.deepEqual(await files(f.state), state); assert.equal(f.dispatches, 0);
}));
for (const [label, sections, extra, status] of [
  ['完整旧集合', { first: { status: 'verified' }, second: { status: 'verified' } }, {}, 'verified'],
  ['空伪verified', {}, { status: 'verified' }, 'unknown'],
  ['缺节伪verified', { first: { status: 'verified' } }, { status: 'verified' }, 'unknown'],
  ['声明空集合', { first: { status: 'verified' }, second: { status: 'verified' } }, { expectedSections: [] }, 'unknown'],
  ['声明重复', { first: { status: 'verified' }, second: { status: 'verified' } }, { expectedSections: ['first', 'first'] }, 'unknown'],
  ['pending', { first: { status: 'verified' }, second: { status: 'pending' } }, {}, 'pending'],
  ['writing', { first: { status: 'verified' }, second: { status: 'writing' } }, {}, 'unknown'],
  ['unknown', { first: { status: 'verified' }, second: { status: 'unknown' } }, {}, 'unknown'],
  ['错inputDigest', { first: { status: 'verified' }, second: { status: 'verified' } }, { inputDigest: 'wrong' }, 'unknown'],
]) test(`history-only ${label}按独立源全集只读核对`, () => fixture('first: {model: old}\nsecond: {model: other}\n', async f => {
  await f.ledgerWith(sections, extra); await f.history();
  const beforeHome = await files(f.home), beforeState = await files(f.state);
  assert.equal((await f.run()).status, status); assert.equal(f.dispatches, 0);
  assert.deepEqual(await files(f.home), beforeHome); assert.deepEqual(await files(f.state), beforeState);
}, { first: normal, second: normal }));
for (const [label, mutate, status] of [
  ['匹配', () => {}, 'verified'],
  ['合法rename归一化', c => { c.inputs = { 'settings.yaml.imported': c.settingsSha256 }; }, 'verified'],
  ['空集合', c => { c.settingsSections = []; }, 'unknown'],
  ['空名字', c => { c.settingsSections = ['']; }, 'unknown'],
  ['重复', c => { c.settingsSections = ['first', 'first']; }, 'unknown'],
  ['错版本', c => { c.version = 1; }, 'unknown'],
  ['错代次', c => { c.generation = randomUUID(); }, 'unknown'],
  ['错sourceSha', c => { c.settingsSha256 = hash('wrong'); }, 'unknown'],
  ['错inputDigest', c => { c.inputDigest = hash('wrong'); }, 'unknown'],
  ['错inputs', c => { c.inputs['settings.yaml'] = hash('wrong'); }, 'unknown'],
  ['错dataRoot', c => { c.dataRoot.inode = 'wrong'; }, 'unknown'],
]) test(`history checkpoint ${label}与独立源集合绑定零写`, () => fixture('first: {model: old}\n', async f => {
  await f.ledgerWith({ first: { status: 'verified' } }, { expectedSections: ['first'] });
  const checkpoint = await json(join(f.folder, 'prepare.json'));
  checkpoint.settingsSha256 = f.sourceSha; checkpoint.settingsSections = ['first']; mutate(checkpoint);
  await put(join(f.folder, 'reuse.json'), checkpoint); await f.history();
  const home = await files(f.home), state = await files(f.state);
  assert.equal((await f.run()).status, status); assert.equal(f.dispatches, 0);
  assert.deepEqual(await files(f.home), home); assert.deepEqual(await files(f.state), state);
}));
test('write-allowed checkpoint冲突在任何派发前unknown零写', () => fixture('first: {model: old}\n', async f => {
  const checkpoint = await json(join(f.folder, 'prepare.json'));
  checkpoint.settingsSha256 = f.sourceSha; checkpoint.settingsSections = ['other']; await put(join(f.folder, 'reuse.json'), checkpoint);
  const home = await files(f.home), state = await files(f.state);
  assert.equal((await f.run()).status, 'unknown'); assert.equal(f.dispatches, 0);
  assert.deepEqual(await files(f.home), home); assert.deepEqual(await files(f.state), state);
}));
test('空源节名即便别名合法也不得派发或写expected集合', () => fixture('\"\": {model: old}\n', async f => {
  const home = await files(f.home), state = await files(f.state);
  assert.equal((await f.run({ '': 'first' })).status, 'unknown'); assert.equal(f.dispatches, 0);
  assert.deepEqual(await files(f.home), home); assert.deepEqual(await files(f.state), state);
}));
test('writing残留在write-allowed成为unknown不重放', () => fixture('first: {model: old}\n', async f => {
  await f.ledgerWith({ first: { status: 'writing' } });
  assert.equal((await f.run()).status, 'unknown'); await f.run(); assert.equal(f.dispatches, 0);
}));
for (const [label, schema, document] of [
  ['secret元数据', "z.object({model:z.string().default('initial').volatile(),apiKey:z.string().role('secret').volatile()})", 'first: {model: old, apiKey: incoming-secret}\n'],
  ['动态dict', "z.object({data:z.dict(z.string()).volatile()})", 'first: {data: {x: value}}\n'],
  ['transform回调', "z.object({data:z.transform(z.string(), x=>x).volatile()})", 'first: {data: value}\n'],
  ['未知源字段', normal, 'first: {mystery: incoming-secret}\n'],
  ['非法值', normal, 'first: {temperature: -1}\n'],
]) test(`${label}整源写前拒绝且账本无秘密正文`, () => fixture('safe: {model: old}\n' + document, async f => {
  assert.equal((await f.run()).status, 'pending'); assert.equal(f.dispatches, 0);
  assert.equal(f.value('safe').model, 'initial');
  const ledger = await readFile(f.ledger, 'utf8'); assert.equal(ledger.includes('incoming-secret'), false);
  await f.run(); assert.equal(f.dispatches, 0);
}, { safe: normal, first: schema }));
test('别名多对一及原名冲突整源零派发', () => fixture('legacy: {model: old}\nfirst: {model: other}\n', async f => {
  assert.equal((await f.run({ legacy: 'first' })).status, 'pending'); assert.equal(f.dispatches, 0);
}));
test('refs未知/secret不得被redacted secrets空集合洗白', () => fixture('first: {model: old}\n', async f => {
  const describe = f.settings.describe.bind(f.settings);
  f.settings.describe = () => describe().map(row => ({ ...row, secrets: [], schema: { uid: 999, refs: {} } }));
  assert.equal((await f.run()).status, 'pending'); assert.equal(f.dispatches, 0);
}));
for (const value of [null, false, [], 0]) test(`损坏账本${JSON.stringify(value)}不能新建后派发`, () => fixture('first: {model: old}\n', async f => {
  await put(f.ledger, value); const before = await readFile(f.ledger, 'utf8');
  assert.equal((await f.run()).status, 'unknown'); assert.equal(f.dispatches, 0); assert.equal(await readFile(f.ledger, 'utf8'), before);
}));
for (const [label, change] of [
  ['图中不可达secret', graph => { graph.refs[999999] = { type: 'string', meta: { role: 'secret' } }; }],
  ['未知schema属性', graph => { graph.refs[graph.uid].callback = '() => process.exit()'; }],
  ['缺失child引用', graph => { graph.refs[graph.uid].dict.model = 999999; }],
  ['数组secret不移位', graph => { const array = graph.refs[graph.refs[graph.uid].dict.items]; graph.refs[array.inner].meta.role = 'secret'; }],
]) test(`${label}整源零派发`, () => fixture('first: {model: old}\n', async f => {
  const describe = f.settings.describe.bind(f.settings);
  f.settings.describe = () => describe().map(row => { change(row.schema); return { ...row, secrets: [] }; });
  assert.equal((await f.run()).status, 'pending'); assert.equal(f.dispatches, 0);
}));
test('纯const union兼容', () => fixture('first: {mode: b}\n', async f => {
  assert.equal((await f.run()).status, 'verified'); assert.equal(f.value().mode, 'b');
}, { first: "z.object({mode:z.union(['a','b']).default('a').volatile()})" }));
test('数组读回缺索引不得被子集比较授verified', () => fixture('first: {items: [{label: a}, {label: b}]}\n', async f => {
  const describe = f.settings.describe.bind(f.settings);
  f.settings.describe = () => describe().map(row => f.dispatches ? { ...row, value: { ...row.value, items: [{label: 'b'}] } } : row);
  assert.equal((await f.run()).status, 'unknown'); await f.run(); assert.equal(f.dispatches, 1);
}));
test('读回只含补丁子集但丢失未改字段不得verified', () => fixture('first: {model: old}\n', async f => {
  const describe = f.settings.describe.bind(f.settings);
  f.settings.describe = () => describe().map(row => f.dispatches ? { ...row, value: { model: 'old' } } : row);
  assert.equal((await f.run()).status, 'unknown'); await f.run(); assert.equal(f.dispatches, 1);
}));
for (const [label, mutate] of [
  ['错fingerprint', f => { f.current.startupContext.inputFingerprint = 'wrong'; }],
  ['null设置摘要', f => { f.current.startupContext.settingsSha256 = null; }],
  ['空startup', f => { f.current.startupId = ''; f.current.startupContext.startupId = ''; }],
  ['错proof', f => { f.current.startupContext.proofDigest = 'wrong'; }],
]) test(`${label}只读不派发`, () => fixture('first: {model: old}\n', async f => {
  mutate(f); await put(join(f.state, 'current.json'), f.current);
  const before = await files(f.home);
  assert.equal((await f.run()).status, 'unknown'); assert.equal(f.dispatches, 0); assert.deepEqual(await files(f.home), before);
}));
for (const [key, value] of [['version', 1], ['generation', randomUUID()], ['dshHome', '/wrong']]) {
  test(`prepare ${key}错绑定拒绝`, () => fixture('first: {model: old}\n', async f => {
    const file = join(f.folder, 'prepare.json'), prepared = await json(file); prepared[key] = value; await put(file, prepared);
    await assert.rejects(f.run(), /PROTECTION_REQUIRED/); assert.equal(f.dispatches, 0);
  }));
}
for (const status of ['unknown', 'writing', 'bogus', null]) test(`overall ${status}不能被verified子节洗白`, () => fixture('first: {model: old}\n', async f => {
  await f.ledgerWith({ first: { status: 'verified' } }, { status }); await f.history();
  const before = await files(f.state); assert.equal((await f.run()).status, 'unknown'); assert.deepEqual(await files(f.state), before); assert.equal(f.dispatches, 0);
}));
test('精确生产patch的importLegacyDocument hook进入本迁移器', () => fixture('first: {model: old}\n', async f => {
  const recipe = await json(resolve('app/src/main/assets/rc1-settings-migration-patch.json'));
  const original = await readFile(require.resolve('@deepseek-ai/dsh-settings'), 'utf8');
  let patched = original;
  for (const patch of recipe.patches) { assert.equal(patched.split(patch.before).length, 2); patched = patched.replace(patch.before, patch.after); }
  const fixed = 'file:///root/.dsh/rc1-settings-migration.cjs';
  assert.equal(patched.split(fixed).length, 2, '仅一次私有fixture路径适配');
  const wrapper = join(f.root, 'migration-wrapper.cjs');
  await writeFile(wrapper, `const real=require(${JSON.stringify(resolve('app/src/main/assets/rc1-settings-migration.cjs'))});module.exports={migrate:(settings,parse,aliases)=>real.migrate(settings,parse,aliases,{stateRoot:${JSON.stringify(f.state)}})};`);
  patched = patched.replace(fixed, pathToFileURL(wrapper).href);
  const file = join(f.root, 'settings-patched.mjs'); await writeFile(file, patched);
  const { SettingsForms: Patched } = await import(pathToFileURL(file));
  await Patched.prototype.importLegacyDocument.call(f.settings);
  assert.equal(f.dispatches, 1); assert.equal(f.value().model, 'old'); assert.equal((await json(f.ledger)).status, 'verified');
  await f.history(); const before = await files(f.home), ledger = await readFile(f.ledger, 'utf8');
  await Patched.prototype.importLegacyDocument.call(f.settings);
  assert.equal(f.dispatches, 1); assert.deepEqual(await files(f.home), before); assert.equal(await readFile(f.ledger, 'utf8'), ledger);
}));
test('快照正文变化零派发', () => fixture('first: {model: old}\n', async f => {
  await writeFile(join(f.folder, 'snapshots', '0'), 'changed');
  await assert.rejects(f.run(), /SNAPSHOT_CHANGED/); assert.equal(f.dispatches, 0);
}));
test('活动源正文变化零派发', () => fixture('first: {model: old}\n', async f => {
  await writeFile(join(f.home, 'settings.yaml'), 'first: {model: changed}\n');
  await assert.rejects(f.run(), /SOURCE_CHANGED/); assert.equal(f.dispatches, 0);
}));
test('已有合法settings.yaml.imported仍可导入', () => fixture('first: {model: old}\n', async f => {
  const { rename } = await import('node:fs/promises');
  await rename(join(f.home, 'settings.yaml'), join(f.home, 'settings.yaml.imported'));
  assert.equal((await f.run()).status, 'verified'); assert.equal(f.dispatches, 1); assert.equal(f.value().model, 'old');
}));

// 等Python队友冻结后与其真实prepare/finalize联调；此测试仍使用隔离目录。
test('当前Python prepare票据接真实SettingsForms/ConfigEditor且普通重启只读', () => fixture('first: {model: old}\n', async f => {
  await rm(f.state, { recursive: true, force: true });
  await put(join(f.home, 'runtime-patches.json'), { schema: 1, dshVersion: '0.2.2-rc.1' });
  const run = command => spawnSync(process.env.DSHA_PYTHON || 'python', ['app/src/main/assets/rc1-migration.py', command, '--root', f.root, '--state-root', f.state, '--startup-id', 'python-integration'], { encoding: 'utf8' });
  let p = run('prepare'); assert.equal(p.status, 0, p.stderr + p.stdout);
  assert.equal((await f.run()).status, 'verified'); assert.equal(f.dispatches, 1);
  p = run('finalize'); assert.equal(p.status, 0, p.stderr + p.stdout);
  await f.change('first', { model: 'user-later' });
  p = run('prepare'); assert.equal(p.status, 0, p.stderr + p.stdout);
  const current = await json(join(f.state, 'current.json')), ledger = join(f.state, 'generations', current.generation, 'settings-result.json');
  const before = await readFile(ledger, 'utf8'), home = await files(f.home);
  await f.run(); assert.equal(f.dispatches, 1); assert.equal(f.value().model, 'user-later');
  assert.equal(await readFile(ledger, 'utf8'), before); assert.deepEqual(await files(f.home), home);
  // 真实finalize发布的合法checkpoint被损坏后，各层不得继续授历史成功。
  const reuse = join(f.state, 'generations', current.generation, 'reuse.json');
  const checkpoint = await json(reuse); assert.deepEqual(checkpoint.settingsSections, ['first']);
  checkpoint.settingsSections = ['other']; await put(reuse, checkpoint);
  const state = await files(f.state);
  assert.equal((await f.run()).status, 'unknown'); assert.equal(f.dispatches, 1); assert.deepEqual(await files(f.state), state);
}));
