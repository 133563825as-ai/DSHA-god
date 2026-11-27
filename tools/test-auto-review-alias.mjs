import {testRuntime} from './test-runtime-fixture.mjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';

const raw=testRuntime('raw'),managed=testRuntime('managed');
const requireRuntime=createRequire(path.join(raw,'package.json'));
const load=name=>import(pathToFileURL(requireRuntime.resolve(name)));
const {PluginManager}=await import(pathToFileURL(path.join(managed,'node_modules/@deepseek-ai/dsh-plugin-manager/lib/index.js')));
const {loadProfileDirectory,composeEntries}=await load('@deepseek-ai/dsh-app-boot');
const {Context}=await load('@deepseek-ai/cordis');
const managerContext=new Context();managerContext.provide('loader',{entries:()=>[]});
const aliases=['dsh-auto-review','@deepseek-ai/dsh-experimental-auto-review'];
const owned=fs.mkdtempSync(path.join(os.tmpdir(),'dsha-auto-alias-'));
const anchor=path.join(raw,'node_modules/@deepseek-ai/dsh/package.json');
const makeProfile=(name,bundles)=>{
  const dir=path.join(owned,name);fs.mkdirSync(path.join(dir,'node_modules'),{recursive:true});
  fs.symlinkSync(path.resolve('app/src/main/assets/builtin-plugins/dsh-auto-review'),path.join(dir,'node_modules',aliases[0]),process.platform==='win32'?'junction':'dir');
  fs.writeFileSync(path.join(dir,'package.json'),JSON.stringify({name:'isolated-auto-review',private:true,dependencies:{[aliases[0]]:'link:'+path.resolve('app/src/main/assets/builtin-plugins/dsh-auto-review')},dsh:{profile:{bundles,patchReload:'startup'}}}));
  fs.writeFileSync(path.join(dir,'cordis.patch.yml'),'[]\n');
  return dir;
};
const manifest=dir=>JSON.parse(fs.readFileSync(path.join(dir,'package.json'),'utf8'));
const selected=dir=>manifest(dir).dsh.profile.bundles;
const rows=dir=>{
  const profile=loadProfileDirectory('dsh',dir,anchor);
  return composeEntries([...profile.layers.map(layer=>layer.patches),profile.patches]).filter(row=>row.name===aliases[1]);
};
const makeManager=dir=>{
  const manager=Object.create(PluginManager.prototype);
  const ctx=managerContext;
  Object.defineProperty(manager,'ctx',{value:ctx});
  Object.assign(manager,{profile:{name:path.basename(dir),dir,home:owned,patchPath:path.join(dir,'cordis.patch.yml'),overlays:[],startedBundles:[],installAnchor:anchor},ownerContext:ctx,managementBundles:new Set(),installedFrom:new Map(),packageOperations:new Set(),installs:new Map(),abort:new AbortController(),lockWaitMs:1000});
  return manager;
};
try {
  const a=makeProfile('web',[aliases[0]]),b=makeProfile('other',[aliases[0]]),manager=makeManager(a);
  assert.equal(rows(a).length,1,'aliases must compose one actual official row');
  let cards=await manager.listBundles();
  assert.equal(cards.filter(card=>aliases.includes(card.name)).length,1,'one canonical Auto card');
  assert.equal(cards.find(card=>card.name===aliases[1]).enabled,true,'legacy selected wrapper makes official card ON');
  assert.equal(cards.find(card=>card.name===aliases[1]).rows.length,1);
  const historical=manifest(a);historical.dsh.profile.bundles=[...aliases];
  fs.writeFileSync(path.join(a,'package.json'),JSON.stringify(historical));
  assert.equal(rows(a).length,2,'historical dual selection reproduces the duplicate owner');
  await manager.setBundleEnabled(aliases[1],true);
  assert.deepEqual(selected(a),[aliases[1]]);assert.equal(rows(a).length,1,'explicit ON normalizes legacy dual selection');
  const off=await manager.setBundleEnabled(aliases[1],false);
  assert.equal(off.application,'restart-required','startup profiles must disclose pending runtime restart');
  assert.equal(selected(a).some(name=>aliases.includes(name)),false);
  assert.equal(rows(a).length,0);
  assert.equal(rows(b).length,1,'another profile must retain its selection');
  const marker=path.join(a,'node_modules',aliases[0]+'.disabled');
  assert.equal(fs.readFileSync(marker,'utf8'),'DSHA_WEB_AUTO_REVIEW_DISABLED_V1\n');
  cards=await manager.listBundles();assert.equal(cards.find(card=>card.name===aliases[1]).enabled,false);
  const on=await manager.setBundleEnabled(aliases[1],true);
  assert.equal(on.application,'restart-required');
  assert.deepEqual(selected(a),[aliases[1]]);assert.equal(rows(a).length,1);assert.equal(fs.existsSync(marker),false);
  for(const content of ['','DSHA_SAFE_MODE','unknown historical marker']) {
    fs.writeFileSync(marker,content);
    const refused=await manager.setBundleEnabled(aliases[1],true);
    assert.equal(refused.application,'failed');assert.equal(fs.readFileSync(marker,'utf8'),content,'native/unknown marker must survive');
    await manager.setBundleEnabled(aliases[1],false);assert.equal(fs.readFileSync(marker,'utf8'),content);
  }
  fs.rmSync(marker);
  const officialMarker=path.join(a,'node_modules',aliases[1]+'.disabled');
  fs.mkdirSync(path.dirname(officialMarker),{recursive:true});fs.writeFileSync(officialMarker,'native historical marker');
  assert.equal((await manager.setBundleEnabled(aliases[1],true)).application,'failed');
  assert.equal(fs.readFileSync(officialMarker,'utf8'),'native historical marker');
  console.log('PASS: actual Host list/toggle + real profile composition use one canonical row; both aliases turn OFF; web ON clears only its own marker; independent profiles are unchanged.');

  const {default:Permissions}=await load('@deepseek-ai/dsh-permission-presets');
  const official=await load(aliases[1]);
  const {default:Projections}=await load('@deepseek-ai/dsh-session-projection');
  const {default:Agents}=await load('@deepseek-ai/dsh-agent');
  const {Session}=await load('@deepseek-ai/dsh-session');
  const {default:Tools}=await load('@deepseek-ai/dsh-tools');
  const {default:SystemPrompt}=await load('@deepseek-ai/dsh-system-prompt');
  const {default:LocalFS}=await load('@deepseek-ai/dsh-fs-local');
  const {default:WorkingDirectory}=await load('@deepseek-ai/dsh-working-directory');
  const ctx=new Context();let reviews=0,next=0,reviewReply='{"risk":"low","decision":"allow"}';
  const sessions=[];
  ctx.provide('shell',{sandboxMode:'danger-full-access'});
  ctx.provide('approval',{config:{policy:'never'},overrideOf:()=> 'never'});
  ctx.provide('sessions',{list:()=>sessions});
  ctx.provide('llm',{async *stream(){reviews++;yield {type:'text-delta',index:0,text:reviewReply};yield {type:'finish',reason:{kind:'stop'}};}});
  try {
    // 使用真实注册表与投影句柄；缺硬依赖时 await fiber 本身不保证已激活。
    for(const [plugin,config] of [[Projections,{}],[Agents,{}],[SystemPrompt,{}],[Tools,{}],
      [LocalFS,{cwd:owned}],[WorkingDirectory,{defaultDirectory:owned}],[Permissions,{defaultPreset:'danger-full-access'}]]) {
      const fiber=ctx.plugin(plugin,config);await fiber;assert.equal(fiber.state,2,plugin.name+' must activate with its required services');
    }
    assert.equal(ctx.permissionPresets.names.includes('auto'),false);
    const session=Session.create('owned-auto-review-session');
    session.append('permission/preset',{preset:'danger-full-access'});
    session.append('sandbox/mode',{mode:'danger-full-access'});
    session.append('approval/policy',{policy:'never'});
    session.append('request/header',{reason:'initial',header:{config:{provider:'fixture',model:'fixture-model'},
      tools:[{name:'fixture-read',description:'read an isolated fixture',parameters:{type:'object'}}]}});
    session.append('turn/start',{turn:1});session.append('step/start',{turn:1,step:1});
    session.append('assistant/message',{turn:1,step:1,stream:[],message:{id:'owned-assistant',role:'assistant',
      source:{kind:'model',provider:'fixture',model:'fixture-model'},content:[{type:'tool-call',id:'call',name:'fixture-read',arguments:'{}'}]}},{surfaceOp:'append'});
    session.append('tool/call',{turn:1,step:1,callId:'call',name:'fixture-read',arguments:'{}'});
    assert.throws(()=>ctx.permissionPresets.set(session,'auto'),/unknown preset/,'Auto cannot be selected without its integration');
    const exec={agent:{session,ctx},signal:new AbortController().signal,name:'fixture-read',arguments:{},callId:'call',rootCallId:'call'};
    const run=()=>ctx.waterfall(null,'tools/pre-execute',exec,async()=>{next++;return {kind:'allow'};});
    let fiber=ctx.plugin(official);await fiber;assert.equal(fiber.state,2);
    assert.equal(ctx.permissionPresets.names.includes('auto'),true);
    assert.equal(ctx.permissionPresets.defaultPreset,'danger-full-access');
    assert.equal(ctx.permissionPresets.current(session),'danger-full-access');
    await run();assert.equal(reviews,0,'registering Auto must not select it or review ordinary mode');
    ctx.permissionPresets.set(session,'auto');
    assert.equal(ctx.permissionPresets.current(session),'auto');
    assert.equal((await run()).kind,'allow');assert.equal(reviews,1);assert.equal(next,2);
    reviewReply='{"risk":"high","decision":"deny"}';
    const denied=await run();assert.equal(denied.kind,'deny');assert.equal(denied.info.code,'AUTO_REVIEW_DENIED');
    assert.equal(reviews,2);assert.equal(next,2,'review denial must not execute the tool body');
    reviewReply='invalid reviewer output';
    const failed=await run();assert.equal(failed.kind,'deny');assert.equal(failed.info.code,'AUTO_REVIEW_FAILED');
    assert.equal(reviews,3);assert.equal(next,2,'invalid reviewer output must fail closed');
    sessions.push(session);await fiber.dispose();
    assert.equal(ctx.permissionPresets.names.includes('auto'),false);
    assert.equal(ctx.permissionPresets.current(session),'danger-full-access','this is the unchanged official disposal behavior, confined to the synthetic fixture');
    assert.throws(()=>ctx.permissionPresets.set(session,'auto'),/unknown preset/,'disposed Auto must not remain selectable');
    fiber=ctx.plugin(official);await fiber;assert.equal(fiber.state,2);
    assert.equal(ctx.permissionPresets.current(session),'danger-full-access','re-enabling the plugin must not select Auto');
    assert.equal(ctx.permissionPresets.defaultPreset,'danger-full-access');
    await run();assert.equal(reviews,3);assert.equal(next,3);await fiber.dispose();
    console.log('PASS: real official Cordis + PermissionPresets register/dispose Auto; current-Auto gates allow, deny, and fail closed; ordinary mode and plugin re-enable do not select Auto.');
  } finally {await ctx.fiber.dispose();}
} finally {await managerContext.fiber.dispose();fs.rmSync(owned,{recursive:true,force:true});}
