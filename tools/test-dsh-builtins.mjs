// 新版消息工厂参与的设备引导回归；模拟准入与已提交事件，不调用模型。
import test, { beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { cp, readFile, mkdtemp, rm, mkdir, writeFile, symlink } from 'node:fs/promises';
import fs from 'node:fs';
import { createRequire, syncBuiltinESMExports } from 'node:module';
import { resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
const runtime = resolve(process.env.DSHA_TEST_RUNTIME || 'app/build/dsh-alpha15/host-runtime');
const directory = resolve(runtime, 'node_modules/dsh-device-shell-guide');
if (!process.env.DSHA_TEST_GUIDE) {
  if (!runtime.startsWith(resolve('app/build'))) throw Error('测试运行时必须位于 app/build');
  await cp('app/src/main/assets/builtin-plugins/dsh-device-shell-guide', directory, {recursive:true});
}
const guide = await import(pathToFileURL(process.env.DSHA_TEST_GUIDE || resolve(directory, 'lib/index.js')));

function fixture(standard = false, history = []) {
  const handlers = new Map(), sections = [];
  guide.apply({on:(type,fn)=>handlers.set(type,fn),inject(names,apply){
    if (standard) apply({systemPrompt:{section:section=>sections.push(section)},effect:fn=>fn()});
  }});
  const session = { snapshotEvents:()=>history }, original = {kind:'enter', messages:[{id:'human'}]};
  return {sections, original, session, handlers,
    step: (decision=original, extra={})=>handlers.get('agent/pre-step')({agent:{session},step:1,signal:new AbortController().signal,...extra},async()=>decision)};
}
let io;
beforeEach(async()=>{io=await isolateOverlayIO(41822);await io.negativeControls();});
afterEach(async()=>{await io.restore();});

test('标准模式只注入一份系统引导', async()=>{
  const f=fixture(true); assert.equal(f.sections.length,1);
  assert.equal(await f.step(), f.original);
});
test('极简模式必须等消息提交后才判重，失败准入不会吞掉引导', async()=>{
  const f=fixture(); const first=await f.step();
  assert.equal(first.messages[0].role,'user'); assert.equal(first.messages[0].source.kind,'plugin');
  assert.equal(first.messages[0].source.plugin,guide.name); assert.ok(first.messages[0].id);
  assert.equal((await f.step()).messages.length,2);
  f.handlers.get('session/event')(f.session,{type:'user/message',data:first.messages[0]});
  assert.equal(await f.step(),f.original);
});
test('恢复会话仅刷新旧引导，当前引导、拒绝准入、取消步骤和非首步不重复注入', async()=>{
  const restored=fixture(false,[{type:'user/message',data:{source:{kind:'plugin',plugin:guide.name}}}]);
  assert.equal((await restored.step()).messages.length,2,'old Chinese-only built-in snapshots need a current language default');
  const current=(await fixture().step()).messages[0];
  const restoredCurrent=fixture(false,[{type:'user/message',data:current}]);
  assert.equal(await restoredCurrent.step(),restoredCurrent.original);
  const f=fixture(), decision={kind:'reject'};
  assert.equal(await f.step(decision),decision);
  assert.equal(await f.step(f.original,{signal:{aborted:true}}),f.original);
  assert.equal(await f.step(f.original,{step:2}),f.original);
});


// Exercise the actual upstream admission policy. A signed wrapper is subject to
// exactly the same no-exemption version gate as a third-party bundle.
const runtimeRequire = createRequire(resolve(runtime, 'package.json'));
const { satisfies } = runtimeRequire('semver');
const { evaluatePluginCompatibility, getDshRuntimeVersion } = await import(
  pathToFileURL(runtimeRequire.resolve('@deepseek-ai/dsh-app-boot')));
const builtinRegistry = JSON.parse(await readFile('app/src/main/assets/builtin-plugins.json','utf8'));
const officialLock = JSON.parse(await readFile('tools/dsh-runtime/package-lock.json','utf8'));
const officialManifest = JSON.parse(await readFile('tools/dsh-runtime/package.json','utf8'));
const runtimeVersion = getDshRuntimeVersion();
const bundledManifest = async plugin => JSON.parse(await readFile(plugin.name === 'dsh-app-integration'
  ? 'app/src/main/assets/app-integration/package.json'
  : resolve('app/src/main/assets/builtin-plugins', plugin.name, 'package.json'),'utf8'));
const migratedPeers = {
  'dsh-auto-review': ['@deepseek-ai/dsh-experimental-auto-review'],
  'dsh-computer-use-android': ['@deepseek-ai/dsh-computer-use','@deepseek-ai/dsh-mcp-client'],
  'dsh-device-shell-guide': ['@deepseek-ai/dsh-system-prompt','@deepseek-ai/dsh-llm'],
};
test('all signed builtin manifests pass the actual upstream version gate without exemptions',async()=>{
  assert.equal(runtimeVersion,officialManifest.dependencies['@deepseek-ai/dsh']);
  assert.equal(builtinRegistry.plugins.length,11);
  for (const plugin of builtinRegistry.plugins) {
    const manifest = await bundledManifest(plugin);
    assert.equal(evaluatePluginCompatibility(manifest,{},runtimeVersion),undefined,
      plugin.name+' must pass the unmodified upstream version gate');
  }
});
test('migrated exact peers match actual installed official modules and npm lock',async()=>{
  for (const [name,peers] of Object.entries(migratedPeers)) {
    const manifest = await bundledManifest({name});
    for (const peer of peers) {
      const installed=JSON.parse(await readFile(resolve(runtime,'node_modules',peer,'package.json'),'utf8'));
      assert.equal(installed.name,peer);
      assert.equal(installed.version,runtimeVersion);
      assert.ok(satisfies(installed.version, manifest.peerDependencies[peer]), `${name} peer ${peer} must accept ${installed.version}`);
      assert.ok(satisfies(installed.version, officialManifest.dependencies[peer]), `official dependency ${peer} must accept ${installed.version}`);
      assert.equal(officialLock.packages['node_modules/'+peer].version,installed.version);
    }
  }
});
test('the upstream version gate still rejects each original stale wrapper declaration',async()=>{
  for (const [name,peers] of Object.entries(migratedPeers)) {
    const original=await bundledManifest({name});
    for(const peer of peers) original.peerDependencies[peer]='0.2.0-rc.2';
    const issue=evaluatePluginCompatibility(original,{},runtimeVersion);
    assert.equal(issue.name,name);assert.equal(issue.runtimeVersion,runtimeVersion);
    assert.equal(issue.exempted,false);
    assert.deepEqual(issue.requirements,Object.fromEntries(peers.map(peer=>[peer,'0.2.0-rc.2'])));
  }
});


// 全测试共用护栏；生产吞掉 IO 异常，所以除 throw 外还必须独立计数。
async function isolateOverlayIO(port) {
  const oldEnv=Object.fromEntries(['HOME','DSHA_BRIDGE_PORT','DSHA_WEB_GENERATION','DSHA_BUILTIN_TEST_RUNTIME']
    .map(key=>[key,process.env[key]]));
  const home=await mkdtemp(resolve('app/build/builtin-overlay-home-'));
  assert.ok(home.startsWith(resolve('app/build')+sep));
  const originals=new Map(),traffic=[],pending=new Set(),tokenFDs=new Set(),violations=[];
  const checked=fn=>{try{return fn();}catch(error){violations.push(error.message);throw error;}};
  const assertClean=()=>assert.deepEqual(violations,[],'被生产 catch 吞掉的 IO 违规也必须失败');
  const fixtureToken='owned-fixture-token';let nextFD=1_000_000,tokenAvailable=true,hold=false;
  const held=[],requests=[];
  const originalFetch=globalThis.fetch,expectedOrigin='http://127.0.0.1:'+port;
  const originalTimeout=globalThis.setTimeout,originalClear=globalThis.clearTimeout,timers=new Map();
  let stopping=false,cancelled=0;
  globalThis.setTimeout=(callback,delay,...args)=>{
    const timer=originalTimeout(()=>{timers.delete(timer);callback(...args);},delay);
    if(stopping){originalClear(timer);cancelled++;}
    else timers.set(timer,{delay,overlay:new Error().stack.includes('/dsh-status-overlay/')});
    return timer;
  };
  globalThis.clearTimeout=timer=>{timers.delete(timer);return originalClear(timer);};
  let tokenReads=0,portStats=0,blockedFiles=0,blockedDestinations=0,closed=false;
  const rejectFile=()=>checked(()=>{blockedFiles++;throw Error('FIXTURE_REAL_CREDENTIAL_IO');});
  const protectedFile=file=>/\/(?:\.dsh|\.ssh|\.android)(?:\/|$)/.test(String(file));
  // lstat 故意模拟端口文件不存在，读端口只能使用本 fixture 的显式 fake 值。
  for(const method of ['readFileSync','lstatSync','statSync','openSync','fstatSync','readSync','closeSync']) {
    originals.set(method,fs[method]);
    fs[method]=function(file,...args) {
      if(method==='readFileSync'&&file==='/root/.dsh/.bridge_token') {
        checked(()=>assert.equal(args[0],'utf8'));tokenReads++;return tokenAvailable?fixtureToken:'';
      }
      if(method==='openSync'&&file==='/root/.dsh/.bridge_token') {
        tokenReads++;const fd=nextFD++;tokenFDs.add(fd);return fd;
      }
      if(tokenFDs.has(file)) {
        if(method==='fstatSync')return {isFile:()=>true,size:fixtureToken.length,mode:0o100600};
        if(method==='readSync')return Buffer.from(fixtureToken).copy(args[0],args[1],args[3]??0,args[2]);
        if(method==='closeSync'){tokenFDs.delete(file);return;}
      }
      if(method==='lstatSync'&&file==='/root/.dsh/.bridge_port') {
        portStats++;throw Object.assign(Error('isolated fixture'),{code:'ENOENT'});
      }
      if(protectedFile(file))return rejectFile();
      return originals.get(method).call(this,file,...args);
    };
  }
  const asyncOriginals=new Map();
  for(const method of ['readFile','lstat','stat','open']) {
    asyncOriginals.set(method,fs.promises[method]);
    fs.promises[method]=async function(file,...args) {
      if(protectedFile(file))return rejectFile();
      return asyncOriginals.get(method).call(this,file,...args);
    };
  }
  process.env.HOME=home;process.env.DSHA_BRIDGE_PORT=String(port);
  process.env.DSHA_BUILTIN_TEST_RUNTIME=runtime;
  delete process.env.DSHA_WEB_GENERATION;
  syncBuiltinESMExports();
  globalThis.fetch=(input,options)=>{
    const url=checked(()=>{
      const url=new URL(input);
      if(closed||url.origin!==expectedOrigin||!['/app/overlay','/app/plugins','/app/ui/task-end','/app/version'].includes(url.pathname)) {
        blockedDestinations++;throw Error('FIXTURE_NON_FAKE_DESTINATION');
      }
      assert.equal(url.searchParams.has('token'),false,'token query 禁止出现');
      assert.equal(options.headers['X-Token'],fixtureToken,'X-Token 必须来自 fake 凭据');
      assert.ok(options.signal instanceof AbortSignal,'请求必须带 AbortSignal');
      return url;
    });
    traffic.push(url);
    requests.push({url,options});
    // 可故意忽略 abort 并迟到完成，用来证明旧响应不会改变新 owner。
    const request=new Promise(done=>{
      const finish=(body='OK')=>done(new Response(JSON.stringify({result:body})));
      if(hold)held.push(finish);else setImmediate(finish);
    });
    pending.add(request);request.then(()=>pending.delete(request),()=>pending.delete(request));
    return request;
  };
  return {traffic,requests,get tokenReads(){return tokenReads;},get portStats(){return portStats;},assertClean,
    tokenAvailable(value){tokenAvailable=value;},hold(){hold=true;},
    settle(body='OK'){hold=false;for(const finish of held.splice(0))finish(body);},
    overlayTimers(){return [...timers.values()].filter(timer=>timer.overlay).map(timer=>timer.delay);},
    assertDisposed(session,from){
      // 先取证，不能等 120ms timer 自然触发后再把残留当作已清理。
      const remaining=[...timers.values()].filter(timer=>timer.overlay).map(timer=>timer.delay);
      const clear=traffic.slice(from).some(url=>url.pathname==='/app/overlay'
        &&url.searchParams.get('kind')==='clear'&&url.searchParams.get('session')===session);
      const evidence={overlayTimers:remaining,clear};
      console.log('REAL_DISPOSE_EVIDENCE '+JSON.stringify(evidence));
      assertClean();
      assert.deepEqual(evidence,{overlayTimers:[],clear:true},'真实 Cordis dispose 必须清 timer 并发送 clear');
    },
    async drain(){
      await new Promise(done=>setImmediate(done));
      while(pending.size)await Promise.all([...pending]);
      await new Promise(done=>setImmediate(done));
      assert.equal(pending.size,0);
    },
    async negativeControls(){
      // 故障注入只打 mock，绝不把原始 fs/fetch 当作“验证”调用。
      assert.throws(()=>fs.readFileSync('/root/.dsh/.bridge_headers','utf8'),/FIXTURE_REAL_CREDENTIAL_IO/);
      assert.throws(()=>fs.statSync('/root/.dsh/.bridge_port'),/FIXTURE_REAL_CREDENTIAL_IO/);
      await assert.rejects(fs.promises.readFile('/root/.dsh/.bridge_token','utf8'),/FIXTURE_REAL_CREDENTIAL_IO/);
      assert.throws(()=>globalThis.fetch('http://127.0.0.1:1/app/overlay'),/FIXTURE_NON_FAKE_DESTINATION/);
      assert.throws(()=>globalThis.fetch('https://fixture.invalid/app/overlay'),/FIXTURE_NON_FAKE_DESTINATION/);
      assert.equal(blockedFiles,3);assert.equal(blockedDestinations,2);
      // 模拟生产 catch 吞异常：每个校验仍须在独立主链断言中可见。
      const swallowed=fn=>{try{fn();}catch{}};
      const valid={headers:{'X-Token':fixtureToken},signal:new AbortController().signal};
      for(const probe of [
        ()=>globalThis.fetch('not a URL',valid),
        ()=>globalThis.fetch(expectedOrigin+'/app/overlay'),
        ()=>globalThis.fetch(expectedOrigin+'/app/overlay?token=fixture',valid),
        ()=>globalThis.fetch(expectedOrigin+'/app/overlay',{...valid,headers:{'X-Token':'wrong'}}),
        ()=>globalThis.fetch(expectedOrigin+'/app/overlay',{...valid,signal:undefined}),
        ()=>fs.readFileSync('/root/.dsh/.bridge_token','buffer'),
      ]) {
        const before=violations.length;swallowed(probe);
        assert.equal(violations.length,before+1);
        assert.throws(assertClean,/IO 违规/,'吞错不得使护栏假绿');
      }
      assert.equal(violations.length,11);assert.equal(traffic.length,0);
      violations.length=0;blockedFiles=0;blockedDestinations=0;
    },
    async restore(){
      const errors=[];
      const attempt=async fn=>{try{await fn();}catch(error){errors.push(error);}};
      const cancelTimers=()=>{for(const timer of timers.keys()){originalClear(timer);cancelled++;}timers.clear();};
      // 只在验收结束后兜底。先停调度，fake IO 内 drain；即使 cleanup 报错也继续收尾。
      stopping=true;cancelTimers();this.settle();
      try {
        await attempt(()=>this.drain());
        cancelTimers();
        await attempt(()=>this.drain());
        await attempt(()=>{
          assert.equal(pending.size,0,'dispose 后必须先 drain 再恢复真实 IO');
          assert.equal(timers.size,0,'兜底后不能遗留异步 timer');
          assert.equal(tokenFDs.size,0,'fake 凭据 fd 必须关闭');
          assert.equal(blockedFiles,0,'生产路径不能读取未 mock 的凭据');
          assert.equal(blockedDestinations,0,'生产吞掉的非法目的地同样必须使测试失败');
          assertClean();
        });
        closed=true;
        console.log('FAKE_IO_CLEANUP '+JSON.stringify({pending:pending.size,timers:timers.size,tokenFDs:tokenFDs.size,
          violations:violations.length,cancelled}));
      } finally {
        for(const [method,original]of originals)fs[method]=original;
        for(const [method,original]of asyncOriginals)fs.promises[method]=original;
        syncBuiltinESMExports();globalThis.fetch=originalFetch;
        for(const timer of timers.keys())originalClear(timer);
        globalThis.setTimeout=originalTimeout;globalThis.clearTimeout=originalClear;
        for(const [key,value]of Object.entries(oldEnv)) {
          if(value===undefined)delete process.env[key];else process.env[key]=value;
        }
        await attempt(()=>rm(home,{recursive:true,force:true}));
        await attempt(()=>assert.equal(fs.existsSync(home),false,'隔离 HOME 必须删除'));
        for(const [key,value]of Object.entries(oldEnv))
          await attempt(()=>assert.equal(process.env[key],value,key+' 必须恢复'));
      }
      await attempt(()=>{
        assert.equal(globalThis.fetch,originalFetch);assert.equal(globalThis.setTimeout,originalTimeout);
        assert.equal(globalThis.clearTimeout,originalClear);
        for(const [method,original]of originals)assert.equal(fs[method],original);
        for(const [method,original]of asyncOriginals)assert.equal(fs.promises[method],original);
      });
      console.log('FAKE_IO_RESTORED '+JSON.stringify({homeRemoved:!fs.existsSync(home),errors:errors.length}));
      if(errors.length)throw new AggregateError(errors,'fake IO 收尾失败（全局已恢复）');
    },
  };
}

test('隔离负控：异步 drain 报错仍停止后续调度并恢复 fake IO 外层',async()=>{
  const saved={fetch:globalThis.fetch,setTimeout:globalThis.setTimeout,clearTimeout:globalThis.clearTimeout,
    readFileSync:fs.readFileSync,readFile:fs.promises.readFile,home:process.env.HOME,port:process.env.DSHA_BRIDGE_PORT};
  const nested=await isolateOverlayIO(41824),drain=nested.drain.bind(nested);
  let drains=0;
  nested.drain=async()=>{await drain();if(++drains===1)throw Error('FIXTURE_ASYNC_CLEANUP_FAILURE');};
  const pending=globalThis.fetch('http://127.0.0.1:41824/app/plugins',
    {headers:{'X-Token':'owned-fixture-token'},signal:new AbortController().signal});
  void pending.then(()=>setTimeout(()=>assert.fail('兜底后不允许执行重排 timer'),30000));
  setTimeout(()=>assert.fail('兜底应取消遗留 timer'),120);
  await assert.rejects(nested.restore(),error=>{
    assert.ok(error instanceof AggregateError);
    assert.match(error.errors[0].message,/FIXTURE_ASYNC_CLEANUP_FAILURE/);return true;
  });
  assert.equal(drains,2,'首次异步 cleanup 失败后仍须再次 drain');
  assert.deepEqual({fetch:globalThis.fetch,setTimeout:globalThis.setTimeout,clearTimeout:globalThis.clearTimeout,
    readFileSync:fs.readFileSync,readFile:fs.promises.readFile,home:process.env.HOME,port:process.env.DSHA_BRIDGE_PORT},saved);
});

test('all eleven signed wrappers activate with real 0.2.2 services; actual MCP and Auto lifecycles dispose cleanly',async()=>{
  await import('./fixtures/builtin-host-hooks.mjs');
  const load = async name => import(pathToFileURL(runtimeRequire.resolve(name)));
  const {Context} = await load('@deepseek-ai/cordis');
  const {default:SystemPrompt} = await load('@deepseek-ai/dsh-system-prompt');
  const {default:ToolRuntime} = await load('@deepseek-ai/dsh-tools');
  const NativeExecutor = await load('@deepseek-ai/dsh-tool-executor-native');
  const {default:ComputerUse} = await load('@deepseek-ai/dsh-computer-use');
  const {default:Agents} = await load('@deepseek-ai/dsh-agent');
  const {default:Projections} = await load('@deepseek-ai/dsh-session-projection');
  const {default:Permissions} = await load('@deepseek-ai/dsh-permission-presets');
  const {default:LocalFS} = await load('@deepseek-ai/dsh-fs-local');
  const {default:WorkingDirectory} = await load('@deepseek-ai/dsh-working-directory');
  const {default:WebServer} = await load('@deepseek-ai/dsh-host-webserver');
  const {HostConnectionService:Connection} = await load('@deepseek-ai/dsh-client-connection');
  for (const method of ['register']) assert.equal(typeof WebServer.prototype[method],'function');
  for (const method of ['requestRejection','authorizeIndex']) assert.equal(typeof Connection.prototype[method],'function');
  const owned = await mkdtemp(resolve('app/build/builtin-runtime-activation-'));
  assert.ok(owned.startsWith(resolve('app/build')+sep),'owned cleanup must remain below app/build');
  const ctx = new Context(), routes = [], fibers = [];
  let llmRequests = 0;
  try {
    // Controlled host carriers own no socket, model credentials, or user Session.
    ctx.provide('webServer',{port:41821,register(route){routes.push(route);return()=>{routes.splice(routes.indexOf(route),1);};}});
    ctx.provide('connection',{requestRejection(){return 401;},authorizeIndex(_req,res){res.writeHead(401);res.end();return false;}});
    ctx.provide('sessions',{list(){return [];}});
    ctx.provide('shell',{sandboxMode:'danger-full-access'});
    ctx.provide('approval',{config:{policy:'never'},overrideOf(){return 'never';}});
    ctx.provide('llm',{stream(){llmRequests++;throw Error('No model request is permitted by this fixture');}});
    for (const [plugin,config] of [[SystemPrompt,{}],[ToolRuntime,{}],[NativeExecutor,{}],[ComputerUse,{}],
      [Projections,{}],[Agents,{}],[LocalFS,{cwd:owned}],[WorkingDirectory,{defaultDirectory:owned}],
      [Permissions,{defaultPreset:'danger-full-access'}]]) {
      const fiber=ctx.plugin(plugin,config);await fiber;assert.equal(fiber.state,2);fibers.push(fiber);
    }
    assert.equal(ctx.permissionPresets.defaultPreset,'danger-full-access');
    assert.equal(ctx.permissionPresets.names.includes('auto'),false);
    const mounted = [];let overlayFiber;
    for (const plugin of builtinRegistry.plugins) {
      const filename = plugin.name==='dsh-app-integration' ? resolve('app/src/main/assets/app-integration/index.js')
        : resolve('app/src/main/assets/builtin-plugins',plugin.name,plugin.entrypoint);
      const module = await import(pathToFileURL(filename));
      assert.equal(typeof module.apply,'function',plugin.name+' apply export');
      const fiber=ctx.plugin(module);await fiber;assert.equal(fiber.state,2,plugin.name+' must activate');mounted.push(fiber);
      if(plugin.name==='dsh-status-overlay')overlayFiber=fiber;
    }
    // The wrapper's cordis.patch inserts this actual official integration.
    const auto=ctx.plugin(await load('@deepseek-ai/dsh-experimental-auto-review'));await auto;
    assert.equal(auto.state,2);assert.equal(ctx.permissionPresets.names.includes('auto'),true);
    assert.equal(ctx.permissionPresets.defaultPreset,'danger-full-access','Auto is offered, never selected by this bundle');
    let next=0;
    const passthrough=await ctx.waterfall(null,'tools/pre-execute',{name:'fixture',signal:new AbortController().signal},async()=>{next++;return {kind:'allow'};});
    assert.equal(passthrough.kind,'allow');assert.equal(next,1);assert.equal(llmRequests,0);
    const schemas=ctx.tools.schemas().map(tool=>tool.name);
    assert.deepEqual(schemas.filter(name=>name.startsWith('mcp__dsha-android__')).sort(),[
      'android_control','android_get_state','android_history','android_node','android_screenshot','android_read_image',
      'android_click','android_double_click','android_long_press','android_type','android_key','android_swipe',
    ].map(name=>'mcp__dsha-android__'+name).sort(),'physical-phone tools must match the complete capability list');
    const historySchema=ctx.tools.schemas().find(tool=>tool.name==='mcp__dsha-android__android_history').parameters;
    assert.deepEqual(historySchema.required,[]);
    assert.equal(historySchema.additionalProperties,false);
    assert.deepEqual(historySchema.properties,{
      since:{type:'integer',minimum:0,maximum:9007199254740991},limit:{type:'integer',minimum:1,maximum:100},
    });
    assert(schemas.includes('mcp__dsha-android__android_read_image'),'shared-storage image reads are exposed as a separate physical-phone tool');
    assert(schemas.includes('mcp__dsha-android__android_node'),'physical-screen semantic actions must be exposed to ordinary conversations');
    assert(schemas.includes('mcp__dsha-android__android_control'),'ordinary conversations need explicit task begin/finish without changing chats');
    assert.equal(schemas.filter(name=>name.startsWith('mcp__dsha-vscreen__')).length,15);
    assert.equal(ctx.computerUse.providerName,'dsha-android');
    const assembly=await ctx.systemPrompt.assemble();
    assert.ok(assembly.sections.some(section=>section.name==='dsh:device-shell-guide'));
    const phoneInstructions=assembly.sections.find(section=>section.name==='dsha:android-mcp')?.text;
    assert(phoneInstructions?.includes('先调用 android_control begin'),'actual MCP initialization must explain task begin');
    assert(phoneInstructions?.includes('调用一次 android_control finish'),'actual MCP initialization must explain task cleanup');
    assert.ok(routes.some(route=>route.path==='/api/mobile-nav.session.delete'));
    const restore=routes.find(route=>route.path==='/__dsha/browser-origin-restore');assert.ok(restore);
    let denied;
    await restore.handler({method:'GET'},{writeHead(code){denied=code;},end(){}});assert.equal(denied,401);
    // 原 all-wrappers 路径也必须实际执行启动上报 timer，不能靠快速 dispose 避开 IO。
    await new Promise(done=>setTimeout(done,1100));await io.drain();
    assert.ok(io.traffic.some(url=>url.pathname==='/app/plugins'));
    assert.ok(io.tokenReads>0);assert.ok(io.portStats>0);
    await auto.dispose();assert.equal(ctx.permissionPresets.names.includes('auto'),false);
    // 在 overlay 自身卸载后立即验收，不能等其它 MCP dispose 时让短 timer 自然跑完。
    const cleanupSession='owned-all-wrappers-cleanup';
    ctx.emit('agent/assistant-stream',{agent:{session:{id:cleanupSession}},
      frame:{type:'chunk',chunk:{type:'text-delta',text:'pending all-wrappers cleanup'}}});
    const from=io.traffic.length,disposalErrors=[];
    assert.ok(overlayFiber);
    await overlayFiber.dispose();
    try {io.assertDisposed(cleanupSession,from);}catch(error){disposalErrors.push(error);}
    for (const fiber of mounted.reverse())if(fiber!==overlayFiber)await fiber.dispose();
    await io.drain();io.assertClean();
    assert.equal(ctx.tools.schemas().length,0);assert.equal(ctx.computerUse.providerName,undefined);
    assert.equal(routes.length,0);assert.equal(llmRequests,0);
    assert.equal(disposalErrors.length,0,'all-wrappers 的真实 overlay dispose 残留必须失败');
  } finally {
    try { await ctx.fiber.dispose(); }
    finally { await rm(owned,{recursive:true,force:true}); }
  }
});



test('android history validates capability arguments and identity; bridge failures and UNKNOWN remain errors without replay',async()=>{
  const server=createRequire(import.meta.url)(resolve('app/src/main/assets/builtin-plugins/dsh-computer-use-android/lib/server.cjs'));
  const {default:fs}=await import('node:fs');
  const originalStat=fs.lstatSync,originalPort=process.env.DSHA_BRIDGE_PORT;
  const signal=new AbortController().signal,task={sessionId:'owned-history-session',turnId:'2',completedTurnId:'1'};
  let reads=0,requests=0,reply=JSON.stringify({events:[],frames:[],nextSince:7});
  const dependencies={fs:{async readFile(file,encoding){assert.equal(file,'/root/.dsh/.bridge_token');assert.equal(encoding,'utf8');reads++;return 'owned-history-token';}},
    async fetch(url,options){
      requests++;assert.equal(url.origin,'http://127.0.0.1:41823');assert.equal(url.pathname,'/app/ui/history');
      assert.equal(url.searchParams.get('taskSessionId'),task.sessionId);assert.equal(url.searchParams.get('turnId'),'2');
      assert.equal(url.searchParams.get('completedTurnId'),'1');assert.equal(url.searchParams.has('token'),false);
      assert.equal(url.searchParams.has('observe'),false);assert.equal(url.searchParams.get('since'),'7');assert.equal(url.searchParams.get('limit'),'3');
      assert.equal(options.headers['X-Token'],'owned-history-token');assert.equal(options.signal,signal);
      return new Response(JSON.stringify({result:reply}));
    }};
  try {
    fs.lstatSync=function(file,...args){
      if(file==='/root/.dsh/.bridge_port')throw Object.assign(Error('isolated fixture'),{code:'ENOENT'});
      return originalStat.call(this,file,...args);
    };
    process.env.DSHA_BRIDGE_PORT='41823';
    for(const [args,code] of [[{since:-1},'INVALID_since'],[{since:1.5},'INVALID_since'],[{since:Number.MAX_SAFE_INTEGER+1},'INVALID_since'],
      [{limit:0},'INVALID_limit'],[{limit:101},'INVALID_limit'],[{limit:'3'},'INVALID_limit'],[{observe:true},'UNKNOWN_ARGUMENT']])
      await assert.rejects(server.call('android_history',args,signal,dependencies,task),new RegExp(code));
    await assert.rejects(server.call('android_history',{},signal,dependencies),/SCREEN_TASK_IDENTITY_REQUIRED/);
    await assert.rejects(server.call('android_history',{},signal,dependencies,{...task,completedTurnId:'2'}),/SCREEN_TURN_INVALID/);
    await assert.rejects(server.call('android_unknown',{},signal,dependencies,task),/UNKNOWN_TOOL/);
    assert.equal(reads,0);assert.equal(requests,0,'invalid calls must not reach credentials or bridge');
    const result=await server.call('android_history',{since:7,limit:3},signal,dependencies,task);
    assert.equal(result.isError,false);assert.deepEqual(JSON.parse(result.content[0].text),{events:[],frames:[],nextSince:7});
    for(const failure of ['DISABLED','NO_PERMISSION','UNAUTHORIZED','STOPPED',
      JSON.stringify({ok:false,state:'UNKNOWN',error:'EXECUTION_UNKNOWN',code:'EXECUTION_UNKNOWN'})]) {
      reply=failure;const before=requests;
      const denied=await server.call('android_history',{since:7,limit:3},signal,dependencies,task);
      assert.equal(denied.isError,true);assert.equal(denied.content[0].text,failure);
      assert.equal(requests,before+1,'a bridge denial or UNKNOWN must never replay');
    }
    assert.equal(reads,requests);
  } finally {
    fs.lstatSync=originalStat;
    if(originalPort===undefined)delete process.env.DSHA_BRIDGE_PORT;else process.env.DSHA_BRIDGE_PORT=originalPort;
  }
});


test('status overlay actual apply forwards current stream and tool events through bounded authenticated bridge IO',async()=>{
  let ctx;
  try {
    const {Context} = await import(pathToFileURL(runtimeRequire.resolve('@deepseek-ai/cordis')));
    ctx = new Context();
    const {traffic}=io;
    // 仍运行实际插件、定时器、聚合和请求构建，仅隔离凭据及网络边界。
    const overlay=await import(pathToFileURL(resolve('app/src/main/assets/builtin-plugins/dsh-status-overlay/lib/index.js')).href+'?builtin-activation-stream');
    const mounted=ctx.plugin(overlay);await mounted;assert.equal(mounted.state,2);
    const session={id:'owned-overlay-session'};
    ctx.emit('session/event',session,{type:'turn/start',data:{}});
    ctx.emit('agent/assistant-stream',{agent:{session},frame:{type:'start'}});
    ctx.emit('agent/assistant-stream',{agent:{session},frame:{type:'chunk',chunk:{type:'reasoning-delta',text:'fixture thought'}}});
    await new Promise(done=>setTimeout(done,160));
    ctx.emit('agent/assistant-stream',{agent:{session},frame:{type:'chunk',chunk:{type:'text-delta',text:'fixture reply'}}});
    await new Promise(done=>setTimeout(done,160));
    ctx.emit('session/event',session,{type:'tool/call',data:{name:'bash',arguments:JSON.stringify({command:'printf fixture'})}});
    ctx.emit('session/event',session,{type:'turn/end',data:{reason:{kind:'completed'}}});
    await io.drain();
    const overlayRequests=traffic.filter(url=>url.pathname==='/app/overlay');
    for(const [kind,text] of [['reasoning','fixture thought'],['text','fixture reply'],['tool','printf fixture']])
      assert.ok(overlayRequests.some(url=>url.searchParams.get('kind')===kind&&url.searchParams.get('text').includes(text)),kind+' current rc2 event must forward');
    assert.ok(overlayRequests.some(url=>url.searchParams.get('kind')==='done'));
    assert.ok(io.tokenReads>0);assert.ok(io.portStats>0);
    // 留一个尚未 flush 的桶，卸载必须取消 timer 并异步发送 clear。
    ctx.emit('agent/assistant-stream',{agent:{session},frame:{type:'chunk',chunk:{type:'text-delta',text:'pending cleanup'}}});
    const from=traffic.length;
    await mounted.dispose();
    io.assertDisposed(session.id,from);
    await io.drain();io.assertClean();
    assert.ok(traffic.slice(from).some(url=>url.searchParams.get('kind')==='clear'));
    const settled=traffic.length;
    await new Promise(done=>setTimeout(done,160));await io.drain();
    assert.equal(traffic.length,settled,'真实卸载后不能再 flush stream 或上报');
  } finally {
    if(ctx)await ctx.fiber.dispose();
  }
});


test('overlay 真实卸载隔离 pending IO、旧回调、重挂及同 session 多 owner',async()=>{
  const {Context}=await import(pathToFileURL(runtimeRequire.resolve('@deepseek-ai/cordis')));
  const overlay=await import(pathToFileURL(resolve('app/src/main/assets/builtin-plugins/dsh-status-overlay/lib/index.js')));
  const roots=[new Context(),new Context()],callbacks=[];
  const mount=async(root,capture=false)=>{
    const fiber=root.plugin({name:'owned-overlay-lifecycle',apply(ctx){
      overlay.apply({registry:ctx.registry,effect:ctx.effect.bind(ctx),inject:ctx.inject.bind(ctx),
        on(event,fn){if(capture)callbacks.push({event,fn});return ctx.on(event,fn);}});
    }});await fiber;assert.equal(fiber.state,2);return fiber;
  };
  const session={id:'owned-lifecycle-shared'},chunk=(root,text)=>root.emit('agent/assistant-stream',
    {agent:{session},frame:{type:'chunk',chunk:{type:'text-delta',text}}});
  const wait=()=>new Promise(done=>setTimeout(done,160));
  try {
    const first=await mount(roots[0],true);io.hold();
    roots[0].emit('session/event',session,{type:'tool/call',data:{name:'bash',arguments:'{}'}});
    roots[0].emit('session/event',session,{type:'turn/end',data:{turn:1}});
    chunk(roots[0],'old pending');
    const stale=io.requests.slice(),before=io.traffic.length;
    await first.dispose();
    io.assertDisposed(session.id,before);
    assert.ok(stale.some(request=>request.url.pathname==='/app/ui/task-end'));
    for(const request of stale)assert.equal(request.options.signal.aborted,true,'卸载必须取消旧业务 IO');
    const clear=io.requests.at(-1);assert.equal(clear.url.searchParams.get('kind'),'clear');
    assert.equal(clear.options.signal.aborted,false,'clear 不复用已取消业务 signal');
    await first.dispose();assert.equal(io.traffic.length,before+1,'真实 dispose 幂等');
    const second=await mount(roots[0]);
    for(const {event,fn}of callbacks){
      if(event==='session/event')fn(session,{type:'turn/end',data:{turn:2}});
      if(event==='agent/assistant-stream')fn({agent:{session},frame:{type:'start'}});
      if(event==='internal/status')fn();
    }
    assert.equal(io.traffic.length,before+1,'保留的旧回调不得发业务请求');
    io.settle('DISABLED');await io.drain();
    chunk(roots[0],'new owner');await wait();await io.drain();
    assert.ok(io.traffic.some(url=>url.searchParams.get('text')==='new owner'),'迟到 DISABLED 不得冷却新挂载');
    const third=await mount(roots[1]);chunk(roots[1],'other owner');await wait();await io.drain();
    assert.ok(io.traffic.some(url=>url.searchParams.get('text')==='other owner'),'同 session 缓冲不共享');
    const from=io.traffic.length;await second.dispose();
    assert.equal(io.traffic.slice(from).some(url=>url.searchParams.get('kind')==='clear'),false,'不能清除仍存活 owner');
    chunk(roots[1],' survives');await wait();await io.drain();
    assert.ok(io.traffic.some(url=>url.searchParams.get('text')==='other owner survives'));
    io.hold();roots[1].emit('session/event',session,{type:'tool/call',data:{name:'read',arguments:'{}'}});
    io.settle('NO_PERMISSION');await io.drain();
    const last=io.traffic.length;await third.dispose();io.assertDisposed(session.id,last);await io.drain();
    const settled=io.traffic.length;await wait();await io.drain();assert.equal(io.traffic.length,settled);
  } finally {io.settle();await Promise.all(roots.map(root=>root.fiber.dispose()));await io.drain();}
});

test('session adapter 真实卸载注销路由与环境，取消 pending 查询并拒绝旧 handler',async()=>{
  const {Context}=await import(pathToFileURL(runtimeRequire.resolve('@deepseek-ai/cordis')));
  const {installSessionAdapter}=await import(pathToFileURL(resolve('app/src/main/assets/builtin-plugins/dsh-status-overlay/lib/session-adapter.js')));
  const {Readable}=await import('node:stream');
  const ctx=new Context(),routes=[],environments=[];let settle,signal,calls=0;
  ctx.provide('webServer',{register(route){routes.push(route);return()=>routes.splice(routes.indexOf(route),1);}});
  ctx.provide('connection',{requestRejection(){return undefined;}});
  ctx.provide('agents',{get(){return undefined;}});
  ctx.provide('sessionController',{list(_args,active){calls++;signal=active;return new Promise(done=>settle=done);}});
  ctx.provide('shellEnv',{register(env){environments.push(env);return()=>environments.splice(environments.indexOf(env),1);}});
  const request=async handler=>{
    const req=Readable.from([Buffer.from(JSON.stringify({method:'sessions.list',args:{}}))]);
    req.method='POST';req.headers={host:'127.0.0.1:41822'};
    let status,body;
    await handler(req,{writeHead(value){status=value;},end(bytes){body=JSON.parse(String(bytes));}});
    return {status,body};
  };
  try {
    const fiber=ctx.plugin({name:'owned-adapter-lifecycle',apply:scope=>installSessionAdapter(scope,()=> '')});await fiber;
    assert.equal(routes.length,1);assert.equal(environments.length,1);
    const oldRoute=routes[0],oldEnv=environments[0],pending=request(oldRoute.handler);
    await new Promise(done=>setImmediate(done));assert.equal(calls,1);
    await fiber.dispose();assert.equal(routes.length,0);assert.equal(environments.length,0);
    assert.equal(signal.aborted,true);assert.deepEqual(oldEnv.resolve({}),{});
    settle({items:[]});const result=await pending;
    assert.equal(result.status,409);assert.equal(result.body.ok,false,'迟到成功不得发布为新 owner 的有效结果');
    const denied=await request(oldRoute.handler);assert.equal(denied.status,409);assert.equal(calls,1,'旧 handler 不得继续调用业务服务');
    const replacement=ctx.plugin({name:'owned-adapter-remount',apply:scope=>installSessionAdapter(scope,()=> '')});await replacement;
    assert.equal(routes.length,1);assert.equal(environments.length,1);
    await replacement.dispose();assert.equal(routes.length,0);assert.equal(environments.length,0);
  } finally {await ctx.fiber.dispose();}
});

test('overlay 无 token 时真实挂载与卸载不请求桥且不留 timer',async()=>{
  const {Context}=await import(pathToFileURL(runtimeRequire.resolve('@deepseek-ai/cordis')));
  const overlay=await import(pathToFileURL(resolve('app/src/main/assets/builtin-plugins/dsh-status-overlay/lib/index.js')));
  const ctx=new Context();io.tokenAvailable(false);
  try {
    const fiber=ctx.plugin(overlay);await fiber;
    ctx.emit('agent/assistant-stream',{agent:{session:{id:'owned-no-token'}},frame:{type:'start'}});
    ctx.emit('session/event',{id:'owned-no-token'},{type:'turn/end',data:{turn:1}});
    await fiber.dispose();await io.drain();
    assert.equal(io.traffic.length,0);assert.deepEqual(io.overlayTimers(),[]);
  } finally {await ctx.fiber.dispose();}
});

test('actual official base/Web and all signed bundle patches compose with every intended plugin enabled',async()=>{
  const {loadProfileDirectory,composeEntries} = await import(pathToFileURL(runtimeRequire.resolve('@deepseek-ai/dsh-app-boot')));
  const owned=await mkdtemp(resolve('app/build/builtin-profile-composition-'));
  assert.ok(owned.startsWith(resolve('app/build')+sep),'owned cleanup must remain below app/build');
  try {
    await mkdir(resolve(owned,'node_modules'));
    for(const plugin of builtinRegistry.plugins) {
      const directory=plugin.name==='dsh-app-integration' ? resolve('app/src/main/assets/app-integration')
        : resolve('app/src/main/assets/builtin-plugins',plugin.name);
      await symlink(directory,resolve(owned,'node_modules',plugin.name),process.platform==='win32'?'junction':'dir');
    }
    await writeFile(resolve(owned,'package.json'),JSON.stringify({name:'owned-builtin-activation-profile',version:'1.0.0',
      dsh:{profile:{bundles:['@deepseek-ai/dsh-base','@deepseek-ai/dsh-web-app',...builtinRegistry.plugins.map(plugin=>plugin.name)]}}}));
    await writeFile(resolve(owned,'cordis.patch.yml'),'[]\n');
    const profile=loadProfileDirectory('dsh',owned,resolve(runtime,'node_modules/@deepseek-ai/dsh/package.json'));
    assert.deepEqual(profile.skippedBundles,[]);assert.equal(profile.layers.length,13);
    const entries=composeEntries([...profile.layers.map(layer=>layer.patches),profile.patches]);
    for(const name of ['dsh-device-shell-guide','dsh-task-notifier','dsh-status-overlay','dsh-web-mobile',
      '@deepseek-ai/dsh-computer-use','dsh-computer-use-android','@deepseek-ai/dsh-experimental-auto-review',
      'dsh-tool-vscreen','dsh-launch-target','dsh-fs-read-hint','dsh-fs-search-scope','dsh-app-integration']) {
      const rows=entries.filter(row=>row.name===name);assert.equal(rows.length,1,name+' must be composed exactly once');
      assert.notEqual(rows[0].disabled,true,name+' must not be accidentally disabled');
    }
    for(const name of ['@deepseek-ai/dsh-working-directory','@deepseek-ai/dsh-permission-presets',
      '@deepseek-ai/dsh-agent','@deepseek-ai/dsh-session-projection','@deepseek-ai/dsh-tools',
      '@deepseek-ai/dsh-host-webserver','@deepseek-ai/dsh-client-connection'])
      assert.ok(entries.some(row=>row.name===name),name+' required service provider must be in official profile');
  } finally {await rm(owned,{recursive:true,force:true});}
});
