// 精确随包函数在受控DOM/RAF内执行；不是近似实现，不启动浏览器或读取用户配置。
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import vm from 'node:vm';
const bundlePath=new URL('../app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js',import.meta.url);
const bundle=readFileSync(bundlePath,'utf8');
function exactScroll(source){
  const start='const SWAP_RESTORE_MS = 10_000',end='function apply(ctx) {';
  assert.equal(source.split(start).length,2,'scroll start must be unique');
  assert.equal(source.split(end).length,2,'client apply boundary must be unique');
  const begin=source.indexOf(start),finish=source.indexOf(end);
  assert.ok(finish>begin);
  const slice=source.slice(begin,finish);
  assert.equal(slice.split('function rememberConversationScroll()').length,2);
  assert.equal(slice.split('function restoreConversationScroll()').length,2);
  return slice;
}
const current=exactScroll(bundle);
function fixture(source=current){
  let now=1000,next=0,elements=[],queries=0,writes=0;
  const pending=new Map(),callbacks=new Map(),cancelled=[];
  const window={};const location={href:'https://fixture.invalid/session'};
  const document={querySelectorAll(){queries++;return elements;},querySelector(){queries++;return elements[0]??null;}};
  const globals={window,document,location,WeakMap,Set,Date:{now:()=>now},
    requestAnimationFrame(fn){const id=++next;pending.set(id,fn);callbacks.set(id,fn);return id;},
    cancelAnimationFrame(id){cancelled.push(id);pending.delete(id);}};
  function load(code=source){
    const ctx=vm.createContext({...globals});
    return vm.runInContext(code+'\n;({remember:rememberConversationScroll,restore:restoreConversationScroll})',ctx);
  }
  function node(top=0,height=2000,client=120){
    let value=top;return {isConnected:true,scrollHeight:height,clientHeight:client,explicit:false,matches(){return this.explicit;},nodeType:1,
      get scrollTop(){return value;},set scrollTop(v){writes++;value=v;}};
  }
  function frame(){const batch=[...pending];pending.clear();for(const [,cb] of batch)cb();}
  const api=load();
  return {api,load,node,window,location,pending,callbacks,cancelled,frame,
    set nodes(value){elements=value;},set now(value){now=value;},
    get writes(){return writes;},get queries(){return queries;},
    seed(top=400){const el=node(top);elements=[el];api.remember();el.scrollTop=0;return el;}};
}
function noLateEffects(f,callbacks){const writes=f.writes;const count=f.pending.size;for(const cb of callbacks)cb();assert.equal(f.writes,writes);assert.equal(f.pending.size,count);}

test('精确旧prebuilt反例：400旧请求污染原100 remount；新函数一帧/多帧均100',()=>{
  const upstream=readFileSync(new URL('./mobile-plugin-upstream/lib/client.js',import.meta.url),'utf8');
  const lock=JSON.parse(readFileSync(new URL('./mobile-plugin-upstream.lock.json',import.meta.url)));
  assert.equal(createHash('sha256').update(upstream).digest('hex'),lock.files['lib/client.js'].sha256);
  function sequence(source,extraFrames){
    const f=fixture(source);const el=f.seed();const b=f.load();b.restore();f.frame();assert.equal(el.scrollTop,400);
    b.remember();el.scrollTop=100;b.restore();f.frame();assert.equal(el.scrollTop,100);
    b.remember();const remounted=f.node();f.nodes=[remounted];b.restore();f.frame();
    for(let i=0;i<extraFrames;i++)f.frame();return remounted.scrollTop;
  }
  assert.equal(sequence(exactScroll(upstream),0),400,'固定旧source必须真实重现失败，不造近似');
  assert.equal(sequence(current,0),100);assert.equal(sequence(current,3),100);
});

test('完成restore后的owner disposer仍可保存新非零位置',()=>{
  const f=fixture();const el=f.seed();const dispose=f.api.restore();f.frame();assert.equal(el.scrollTop,400);
  assert.equal(f.pending.size,0);el.scrollTop=175;dispose();
  assert.equal(f.window.__dshaMobileLastScroll.top,175);
  const b=f.load();const remount=f.node();f.nodes=[remount];b.restore();f.frame();assert.equal(remount.scrollTop,175);
});

test('B成为owner后A迟到cleanup不改新saved/current、不取消B',()=>{
  const f=fixture();f.seed();const disposeA=f.api.restore();
  const b=f.load();const disposeB=b.restore();const before={...f.window};const queued=[...f.pending.keys()];
  const lifecycle=f.window.__dshaMobileScrollLifecycleV1,owner=lifecycle.owner,request=lifecycle.request;
  f.api.remember();disposeA();assert.deepEqual(Object.keys(f.window),Object.keys(before));
  for(const key of Object.keys(before))assert.equal(f.window[key],before[key],key);
  assert.equal(lifecycle.owner,owner);assert.equal(lifecycle.request,request);assert.equal(request.active,true);
  assert.deepEqual([...f.pending.keys()],queued);f.frame();disposeB();
});

for(const mode of ['zero','missing'])test('remember '+mode+'退休旧请求且已cancel callback手调零写零续',()=>{
  const f=fixture();const el=f.seed();f.api.restore();const old=[...f.callbacks.values()];
  if(mode==='zero')el.scrollTop=0;else f.nodes=[];
  const seed=f.window.__dshaMobileLastScroll;
  f.api.remember();assert.equal(f.pending.size,0);noLateEffects(f,old);
  if(mode==='missing'){
    assert.equal(f.window.__dshaMobileLastScroll,seed,'missing DOM retires request, not last seed');
    const remount=f.node();f.nodes=[remount];f.load().restore();f.frame();assert.equal(remount.scrollTop,400);
  }
});

test('新restore退休旧request，即使手调取消的旧callback也不访问/写/续帧',()=>{
  const f=fixture();f.seed();f.api.restore();const old=[...f.callbacks.values()];f.api.restore();
  const reads=f.queries;noLateEffects(f,old);assert.equal(f.queries,reads);
});

test('宿主非0成为终态，后续归0也不能重启旧请求',()=>{
  const f=fixture();const el=f.seed();f.api.restore();el.scrollTop=100;f.frame();
  assert.equal(el.scrollTop,100);assert.equal(f.pending.size,0);el.scrollTop=0;
  noLateEffects(f,[...f.callbacks.values()]);assert.equal(el.scrollTop,0);
});

for(const mode of ['url','expired','disconnected'])test('restore边界 '+mode+' 零写',()=>{
  const f=fixture();const el=f.seed();f.api.restore();
  if(mode==='url')f.location.href='https://fixture.invalid/other';
  if(mode==='expired')f.now=11001;
  if(mode==='disconnected')el.isConnected=false;
  const writes=f.writes;f.frame();assert.equal(f.writes,writes);assert.equal(el.scrollTop,0);
});

test('不足高度不越界，布局在下一帧就绪才恢复且退休请求',()=>{
  const f=fixture();const el=f.seed();el.scrollHeight=300;f.api.restore();f.frame();assert.equal(el.scrollTop,0);
  el.scrollHeight=2000;f.frame();assert.equal(el.scrollTop,400);assert.equal(f.pending.size,0);
  noLateEffects(f,[...f.callbacks.values()]);
});

test('10秒边界仍合法，超过30帧上限绝不自续',()=>{
  const f=fixture();const el=f.seed();f.now=11000;f.api.restore();f.frame();assert.equal(el.scrollTop,400);
  const cap=fixture();cap.seed();cap.nodes=[];cap.api.restore();
  for(let i=0;i<30;i++)cap.frame();assert.equal(cap.pending.size,0);assert.ok(cap.callbacks.size<=30);
  noLateEffects(cap,[...cap.callbacks.values()]);
});

test('同module旧disposer不替新owner保存或取消，显式dispose后手调旧帧也静默',()=>{
  const f=fixture();const el=f.seed();const oldDispose=f.api.restore();
  const currentDispose=f.api.restore();const state=f.window.__dshaMobileScrollLifecycleV1;
  const owner=state.owner,request=state.request;oldDispose();
  assert.equal(state.owner,owner);assert.equal(state.request,request);assert.equal(request.active,true);
  const callbacks=[...f.pending.values()];el.scrollTop=75;currentDispose();
  assert.equal(f.window.__dshaMobileLastScroll.top,75);assert.equal(f.pending.size,0);noLateEffects(f,callbacks);
});

test('唯一显式conversation目标优先于其他scrollBody，多显式目标fail closed',()=>{
  const f=fixture();const el=f.seed();const side=f.node();el.explicit=true;f.nodes=[el,side];
  f.api.restore();f.frame();assert.equal(el.scrollTop,400);assert.equal(side.scrollTop,0);
  const g=fixture();g.seed();const a=g.node(),b=g.node();a.explicit=b.explicit=true;g.nodes=[a,b];
  g.api.restore();g.frame();assert.equal(a.scrollTop,0);assert.equal(b.scrollTop,0);
});

test('browser生命周期局部cleanup四组合保持主错与失败退出',async()=>{
  const source=readFileSync(new URL('./test-mobile-refresh.mjs',import.meta.url),'utf8');
  const start='  } catch(error) {\n    lifecyclePrimary=error;throw error;';
  assert.equal(source.split(start).length,2);
  const suffix=source.slice(source.indexOf(start),source.indexOf("  phase='SESSION_LOG';",source.indexOf(start)));
  for(const mainFail of [false,true])for(const cleanupFail of [false,true]){
    const primary=new Error('synthetic primary'),cleanup=new Error('synthetic cleanup');let restored=0;
    const context=vm.createContext({primary,cleanup,mainFail,AggregateError,
      page:{async evaluate(){restored++;if(cleanupFail)throw cleanup;}}});
    const run=vm.runInContext('(async()=>{let lifecyclePrimary;try {if(mainFail)throw primary;\n'+suffix+'})()',context);
    if(mainFail||cleanupFail)await assert.rejects(run,error=>mainFail&&cleanupFail
      ?error.cause===primary&&error.errors[0]===primary&&error.errors[1]===cleanup
      :error===(mainFail?primary:cleanup));
    else await run;
    assert.equal(restored,1);
  }
});

test('多候选滚动区不能以last seed猜目标写回',()=>{
  const f=fixture();f.seed();const a=f.node(),b=f.node();f.nodes=[a,b];f.api.restore();f.frame();
  assert.equal(a.scrollTop,0);assert.equal(b.scrollTop,0);
});
