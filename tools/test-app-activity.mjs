import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { knownIdle, confirmedIdle } from '../app/src/main/assets/app-integration/activity.js';
import { runtimePlugins, pluginSessionIdentity, pluginActivityProjection } from '../app/src/main/assets/app-integration/runtime-plugins.js';

function context({status='idle', queued=[], jobs=[], active=[], missing=false}={}) {
  const agent={status,inbox:{nextTurn:queued,nextStep:[]},whenIdle:async()=>{}};
  return {agents:{list:()=>[agent]},sessions:{list:()=>[{}]},get:()=>({list:()=>jobs}),
    sessionProjections:{stateOf:()=>missing?undefined:{active}}};
}
test('生成、排队、后台作业和定时任务均不可当作空闲',()=>{
  assert.equal(knownIdle(context()),true);
  for (const state of [{status:'running'},{status:'unknown'},{queued:[{}]},
    {jobs:[{status:'running'}]},{jobs:[{status:'stopping'}]},{active:[{}]},{missing:true}])
    assert.equal(knownIdle(context(state)),false);
});
test('公开 idle 仍在维护时不会撤销保活',async()=>{
  const ctx=context();
  assert.equal(await confirmedIdle(ctx),true);
  ctx.agents.list()[0].whenIdle=()=>new Promise(()=>{});
  assert.equal(await confirmedIdle(ctx,10),false);
  ctx.agents.list()[0].whenIdle=async()=>{ctx.agents.list()[0].status='running'};
  assert.equal(await confirmedIdle(ctx),false);
});
test('动态插件摘要只含公开身份，不保存源码或授权细节',()=>{
  const rows=runtimePlugins([{id:'test-session'}],{listPlugins:()=>[{
    pluginId:'plugin-id',name:'测试插件',currentPackageId:'v1',activeRun:{pluginRunId:'run'},
    hostCode:'sensitive-code',clientCode:'private-client',latestRun:{status:'running',secret:'not-exported'},
  }]});
  assert.equal(rows.length,1);assert.equal(rows[0].active,true);
  assert.equal(rows[0].sessionId,'test-session');
  assert.equal(JSON.stringify(rows).includes('sensitive-code'),false);
  assert.equal(JSON.stringify(rows).includes('not-exported'),false);
  assert.equal(runtimePlugins([],undefined),null);
  assert.equal(runtimePlugins([{id:'session'}],{listPlugins(){throw new Error('unavailable');}}),null);
  assert.deepEqual(runtimePlugins([{id:'session'}],{listPlugins:()=>[]}),[]);
});
test('心跳绑定启动 instance，并同时输出动态与持久插件摘要', async () => {
  const writes = [], evidenceWrites = [], moves = [], cleanups = [];
  const activityFile = '/root/.dsha-web-activity.json';
  const evidenceFile = '/root/.dsha-plugin-startup-evidence.json';
  let tick;
  const persistent = [{ name: 'installed', version: '1.0.0', profile: 'web', state: 'pending' }];
  const samplingContext = {
    agents: { list: () => [{ id: 'session' }] },
    inject(services, callback) {
      if (services[0] === 'dynamicCordisRunner') callback({
        dynamicCordisRunner: { listPlugins: () => [{ pluginId: 'dynamic', name: 'session-plugin', activeRun: {} }] },
        effect(fn) { cleanups.push(fn()); },
      });
    },
    effect(fn) { cleanups.push(fn()); },
  };
  const ctx = {
    inject(services, callback) {
      if (services[0] === 'agents') callback(samplingContext);
    },
  };
  const environment = { DSHA_WEB_GENERATION: 'web-generation', DSH_TASK_INSTANCE: 'launch-instance', DSHA_STARTUP_PROFILE: 'web' };
  const source = readFileSync(new URL('../app/src/main/assets/app-integration/index.js', import.meta.url), 'utf8')
    .replace(/^import .*;\n/gm, '').replace('export const inject', 'const inject').replace('export function apply', 'function apply')
    .replaceAll('import.meta.url', JSON.stringify(import.meta.url));
  vm.runInNewContext(source + '\napply(ctx);', {
    ctx, process: { env: environment, pid: 123 }, confirmedIdle: async context => {
      assert.equal(context,samplingContext); return true;
    }, runtimePlugins, pluginSessionIdentity, pluginActivityProjection,
    installedPluginSampler(context, identity) {
      assert.equal(context, samplingContext);
      assert.deepEqual(JSON.parse(JSON.stringify(identity)), { generation: 'web-generation', instance: 'launch-instance', profile: 'web' });
      return at => persistent.map(row => ({ ...row, identity: pluginSessionIdentity(identity, at) }));
    },
    writeFileSync(file, json, options) {
      assert.equal(options.mode, 0o600);
      if (file === activityFile + '.123.tmp') writes.push(JSON.parse(json));
      else {
        assert.equal(file, evidenceFile + '.123.tmp');
        evidenceWrites.push(JSON.parse(json));
      }
    },
    renameSync(from, to) { moves.push([from, to]); }, unlinkSync() {},
    setInterval(callback, ms) { tick = callback; assert.equal(ms, 2000); return { unref() {} }; }, clearInterval() {},
  });
  await new Promise(setImmediate);
  environment.DSH_TASK_INSTANCE = 'later-mutated-instance';
  await tick();
  assert.equal(writes.length, 2);
  assert.equal(evidenceWrites.length, 2);
  for (const [i, item] of writes.entries()) {
    assert.equal(item.generation, 'web-generation');
    assert.equal(item.instance, 'launch-instance');
    assert.equal(item.idle, true);
    assert.deepEqual(item.installedPlugins.map(({ name, version, profile, state }) => ({ name, version, profile, state })), persistent);
    const evidence = evidenceWrites[i];
    assert.equal(evidence.observedAt, item.at);
    assert.equal(evidence.generation, item.generation);
    assert.equal(evidence.instance, item.instance);
    assert.equal(evidence.identitySource, 'OBSERVER_SESSION');
    assert.equal(evidence.startupId, 'unknown');
    assert.deepEqual(evidence.installedPlugins, item.installedPlugins);
    assert.equal(item.plugins[0].id, 'dynamic');
    assert.equal(item.plugins[0].sessionId, 'session');
    assert.equal(item.installedPluginsComplete, undefined);
  }
  const expectedMoves = [[activityFile + '.123.tmp', activityFile], [evidenceFile + '.123.tmp', evidenceFile]];
  assert.deepEqual(moves, [...expectedMoves, ...expectedMoves]);
  for (const cleanup of cleanups.reverse()) cleanup?.();
  await tick();
  assert.equal(writes.length, 2, 'effect 已释放后不再写旧代心跳');
  assert.equal(evidenceWrites.length, 2, 'effect 已释放后不再写旧代旁证');
  assert.deepEqual(moves, [...expectedMoves, ...expectedMoves]);
});

test('任务结束恢复空闲，接口失效保持保活',()=>{
  assert.equal(knownIdle(context({jobs:[{status:'completed'},{status:'failed'},{status:'killed'}]})),true);
  assert.equal(knownIdle({}),false);
  assert.equal(knownIdle(context({jobs:[{status:'new-upstream-state'}]})),false);
});
