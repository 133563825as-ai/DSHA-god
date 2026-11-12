#!/usr/bin/env node
// 当前 raw 的真实前端/资源与隔离 synthetic module；不调用 BrowserLogin/apply、账号或设备。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import {createHash} from 'node:crypto';
import {browserFixture} from './rc1-browser-fixture.mjs';

const runtime=path.resolve(process.env.DSHA_TEST_RUNTIME||JSON.parse(fs.readFileSync('app/build/test-runtimes/current.json','utf8')).raw);
const base=fs.mkdtempSync(path.join(os.tmpdir(),'dsha-fixture-assets-'));
const originalHome=process.env.HOME;
process.env.HOME=path.join(base,'home');
fs.mkdirSync(process.env.HOME);
const scoped=path.join(base,'runtime/node_modules/@deepseek-ai');
fs.mkdirSync(scoped,{recursive:true});
const name='dsh-client-ui-settings-account',id='@deepseek-ai/'+name;
const whaleName='running-whale@2x-06738fa29b2f.png';
const whalePath=path.join(runtime,'node_modules/@deepseek-ai/dsh-client-ui-chat/lib/assets',whaleName);
const whale=fs.readFileSync(whalePath);
const rawAssets=path.join(runtime,'node_modules',id,'lib/assets');
const welcome='plugins/'+id+'/assets/onboarding-welcome-8bee260fc9a4.webp';
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const results=[];
async function test(name,run){await run();results.push({name,status:'PASS'});}
// 保留请求原始编码；fetch/URL 会在发出前归一化点段，不能作为 HTTP 穿越负控。
function request(origin,pathname,method='GET') {
  return new Promise((resolve,reject)=>{
    const req=http.request(origin,{path:pathname,method},res=>{
      const chunks=[];res.on('data',chunk=>chunks.push(chunk));
      res.on('end',()=>resolve({status:res.statusCode,headers:res.headers,body:Buffer.concat(chunks)}));
    });
    req.on('error',reject);req.end();
  });
}
let fixture;
try {
  for(const pkg of ['dsh-web-frontend','dsh'])fs.symlinkSync(path.join(runtime,'node_modules/@deepseek-ai',pkg),path.join(scoped,pkg),'dir');
  const synthetic=path.join(scoped,'fixture-assets/lib'),assets=path.join(synthetic,'assets');
  fs.mkdirSync(assets,{recursive:true});
  fs.copyFileSync(whalePath,path.join(assets,whaleName));
  fs.copyFileSync(whalePath,path.join(assets,'vanish.png'));
  fs.copyFileSync(whalePath,path.join(assets,'swap.png'));
  const outside=path.join(base,'outside.png');fs.copyFileSync(whalePath,outside);
  fs.symlinkSync(outside,path.join(assets,'escape.png'));
  fs.symlinkSync(base,path.join(assets,'escape-dir'),'dir');
  fs.writeFileSync(path.join(assets,'unsupported.txt'),'not an asset');
  fs.writeFileSync(path.join(assets,'large.png'),Buffer.alloc(8*1024*1024+1));
  fs.writeFileSync(path.join(synthetic,'client.js'),`window.__ModuleLoader__.load({id:'@deepseek-ai/fixture-assets',factory:require=>{var module={exports:{asset:require.asset}};return module.exports;}});`);
  fixture=await browserFixture(path.join(base,'runtime'));
  const {page}=fixture,origin=new URL(page.url()).origin;
  const external=[];
  await page.route('**/*',async route=>{
    if(new URL(route.request().url()).origin!==origin){external.push(route.request().url());await route.abort();}
    else await route.continue();
  });
  await test('未加载包与未知包 HTTP 资源不可访问',async()=>{
    assert.equal((await request(origin,'/__fixture_assets/'+welcome)).status,404);
    assert.equal((await request(origin,'/__fixture_assets/plugins/@deepseek-ai/unknown/assets/x.png')).status,404);
    await assert.rejects(fixture.load('../escape'),/Invalid fixture module/);
  });
  await test('既有内置 mobile 直接 addScriptTag 无资源登记仍正常 factory(require)',async()=>{
    const mobileId='dsh-web-mobile',before=fixture.errors.length;
    assert.equal(await page.evaluate(id=>Object.hasOwn(auditAssetFiles,id),mobileId),false);
    await page.addScriptTag({content:fs.readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js','utf8')
      .replace('var __cache = {};','window.mobileRequire = __localRequire; var __cache = {};')});
    assert.deepEqual(await page.evaluate(id=>({apply:typeof auditExports[id]?.apply,
      controls:typeof mobileRequire('./components/file-share-controls.js').FilesRowShare}),mobileId),
      {apply:'function',controls:'function'});
    assert.equal(fixture.errors.length,before);
    const denied=await page.evaluate(()=>{
      let message;
      window.__ModuleLoader__.load({id:'@deepseek-ai/unregistered',factory:require=>{
        try { require.asset('plugins/@deepseek-ai/unregistered/assets/x.png'); } catch(error) { message=error.message; }
        return {react:typeof require('react').createElement};
      }});
      return {message,react:auditExports['@deepseek-ai/unregistered'].react};
    });
    assert.deepEqual(denied,{message:'Unknown fixture asset module @deepseek-ai/unregistered',react:'function'});
  });
  await test('lib symlink 逃出 canonical package 被拒，HTTP 仍无资源登记',async()=>{
    const pkg=path.join(scoped,'fixture-lib-escape'),lib=path.join(base,'outside-lib');fs.mkdirSync(pkg);
    fs.mkdirSync(path.join(lib,'assets'),{recursive:true});
    fs.copyFileSync(whalePath,path.join(lib,'assets',whaleName));
    fs.writeFileSync(path.join(lib,'client.js'),`window.__ModuleLoader__.load({id:'@deepseek-ai/fixture-lib-escape',factory:require=>({asset:require.asset})});`);
    fs.symlinkSync(lib,path.join(pkg,'lib'),'dir');
    await assert.rejects(fixture.load('fixture-lib-escape'),/Fixture lib escapes package root/);
    assert.equal((await request(origin,'/__fixture_assets/plugins/@deepseek-ai/fixture-lib-escape/assets/'+encodeURIComponent(whaleName))).status,404);
  });
  await test('合法包级 symlink 保留 canonical package 内的真实 assets',async()=>{
    const pkg=path.join(base,'pnpm-store/fixture-package-link'),lib=path.join(pkg,'lib');
    fs.mkdirSync(path.join(lib,'assets'),{recursive:true});
    fs.copyFileSync(whalePath,path.join(lib,'assets',whaleName));
    fs.writeFileSync(path.join(lib,'client.js'),`window.__ModuleLoader__.load({id:'@deepseek-ai/fixture-package-link',factory:require=>({asset:require.asset})});`);
    fs.symlinkSync(pkg,path.join(scoped,'fixture-package-link'),'dir');
    await fixture.load('fixture-package-link');
    const url=await page.evaluate(file=>auditExports['@deepseek-ai/fixture-package-link'].asset('plugins/@deepseek-ai/fixture-package-link/assets/'+file),encodeURIComponent(whaleName));
    const reply=await request(origin,url);
    assert.equal(reply.status,200);assert.equal(reply.headers['content-type'],'image/png');assert.equal(sha(reply.body),sha(whale));
  });
  await test('assets 根目录 symlink 不能扩大静态读取范围',async()=>{
    const lib=path.join(scoped,'fixture-linked/lib');fs.mkdirSync(lib,{recursive:true});
    fs.writeFileSync(path.join(lib,'client.js'),`window.__ModuleLoader__.load({id:'@deepseek-ai/fixture-linked',factory:()=>({})});`);
    fs.symlinkSync(assets,path.join(lib,'assets'),'dir');
    await assert.rejects(fixture.load('fixture-linked'),/Fixture assets root is a symlink/);
    assert.equal((await request(origin,'/__fixture_assets/plugins/@deepseek-ai/fixture-linked/assets/'+encodeURIComponent(whaleName))).status,404);
  });
  await test('synthetic module 的真实 PNG 经一次 decode URL 返回原始字节',async()=>{
    await fixture.load('fixture-assets');
    const url=await page.evaluate(name=>auditExports['@deepseek-ai/fixture-assets'].asset('plugins/@deepseek-ai/fixture-assets/assets/'+name),encodeURIComponent(whaleName));
    assert.match(url,/%40/);assert.notEqual(url,'/');
    const reply=await request(origin,url);assert.equal(reply.status,200);
    assert.equal(reply.headers['content-type'],'image/png');assert.equal(sha(reply.body),sha(whale));
    assert.equal(reply.headers['x-content-type-options'],'nosniff');
    const image=await page.evaluate(async url=>{const img=new Image();img.src=url;await img.decode();return [img.naturalWidth,img.naturalHeight];},url);
    assert(image.every(value=>value>0));
    assert.equal((await request(origin,url,'HEAD')).body.length,0);
    assert.equal((await request(origin,url,'POST')).status,405);
  });
  await test('require.asset 拒绝空/绝对/穿越/多次编码/错包/缺文件/逃逸/类型与大小',async()=>{
    const prefix='plugins/@deepseek-ai/fixture-assets/assets/';
    const bad=[undefined,null,'','/etc/passwd','https://example.invalid/x.png',prefix,prefix+'../'+whaleName,
      prefix+'%2e%2e/'+whaleName,prefix+'%252e%252e/'+whaleName,prefix+'%2fetc',prefix+'%5cetc',prefix+'%00.png',
      prefix+'bad\\name.png',prefix+'a//b.png',prefix+'%',prefix+'missing.png',prefix+'escape.png',prefix+'escape-dir/outside.png',
      prefix+'unsupported.txt',prefix+'large.png',welcome,'plugins/@another/fixture-assets/assets/x.png',
      'plugins/@deepseek-ai/unknown/assets/x.png'];
    const rejected=await page.evaluate(values=>values.map(value=>{try{auditExports['@deepseek-ai/fixture-assets'].asset(value);return false;}catch{return true;}}),bad);
    assert(rejected.every(Boolean));
    assert.throws(()=>fs.realpathSync(path.join(assets,'missing.png')));
  });
  await test('HTTP 坏 URI/穿越/错包/缺文件返回4xx且随后仍可读取',async()=>{
    const prefix='/__fixture_assets/plugins/@deepseek-ai/fixture-assets/assets/';
    for(const suffix of ['', '../'+whaleName,'%2e%2e/'+whaleName,'%252e%252e/'+whaleName,'%2fetc','%5cetc','%00.png','bad\\name.png','a//b.png','%','missing.png','escape.png','escape-dir/outside.png','unsupported.txt','large.png']) {
      const reply=await request(origin,prefix+suffix);assert(reply.status>=400&&reply.status<500,suffix);
    }
    for(const pathname of ['/assets/%','/assets/%2e%2e/index.html','/etc/passwd','/index.html','/__fixture_assets/plugins/@another/fixture-assets/assets/x.png']) {
      const reply=await request(origin,pathname);assert(reply.status>=400&&reply.status<500,pathname);
    }
    fs.unlinkSync(path.join(assets,'vanish.png'));
    fs.unlinkSync(path.join(assets,'swap.png'));fs.symlinkSync(outside,path.join(assets,'swap.png'));
    assert.equal((await request(origin,prefix+'vanish.png')).status,404);
    assert.equal((await request(origin,prefix+'swap.png')).status,404);
    assert.equal((await request(origin,prefix+encodeURIComponent(whaleName))).status,200);
  });
  await test('factory 缺失 require.asset/资源与错包会令 fixture.load 立即明确失败',async()=>{
    const original=fs.readFileSync(path.join(synthetic,'client.js'),'utf8');
    const before='return module.exports;';
    const missingApi={patches:[{before,after:`require.asset=undefined;require.asset('unused');${before}`}]};
    await assert.rejects(fixture.load('fixture-assets',[missingApi]),/BROWSER_FIXTURE_MODULE_LOAD_FAILED.*require.asset is not a function/);
    const recipe={patches:[{before,after:`require.asset('plugins/@deepseek-ai/fixture-assets/assets/missing.png');${before}`}]};
    await assert.rejects(fixture.load('fixture-assets',[recipe]),/BROWSER_FIXTURE_MODULE_LOAD_FAILED.*Missing fixture asset/);
    const wrong={patches:[{before,after:`require.asset(${JSON.stringify(welcome)});${before}`}]};
    await assert.rejects(fixture.load('fixture-assets',[wrong]),/BROWSER_FIXTURE_MODULE_LOAD_FAILED.*Invalid fixture asset package/);
    await fixture.load('fixture-assets');
    const noLoad={patches:[{before:'window.__ModuleLoader__.load(',after:'void ('}]};
    await assert.rejects(fixture.load('fixture-assets',[noLoad]),/BROWSER_FIXTURE_MODULE_LOAD_FAILED.*Missing fixture module export/);
    assert.equal(fs.readFileSync(path.join(synthetic,'client.js'),'utf8'),original);
  });
  await test('旧预期 pageerror 不污染后续真实 account factory，真实资产均匹配字节/MIME',async()=>{
    const event=page.waitForEvent('pageerror',{predicate:error=>error.message==='EXPECTED_OLD_PAGEERROR'});
    await page.evaluate(()=>{setTimeout(()=>{throw Error('EXPECTED_OLD_PAGEERROR');},0);});await event;
    const oldCount=fixture.errors.length;assert(oldCount>=3);
    fs.symlinkSync(path.join(runtime,'node_modules',id),path.join(scoped,name),'dir');
    await fixture.load(name,[],'module.exports.audit={BrowserLogin};');
    assert.equal(fixture.errors.length,oldCount);
    assert.deepEqual(await page.evaluate(id=>({type:typeof auditExports[id],apply:typeof auditExports[id].apply,
      browserLogin:typeof auditExports[id].audit.BrowserLogin}),id),{type:'object',apply:'function',browserLogin:'function'});
    for(const file of fs.readdirSync(rawAssets)) {
      const url=await page.evaluate(({id,file})=>{
        let url;window.__ModuleLoader__.load({id,factory:require=>{url=require.asset('plugins/'+id+'/assets/'+encodeURIComponent(file));return auditExports[id];}});return url;
      },{id,file});
      const reply=await request(origin,url);assert.equal(reply.status,200);
      assert.equal(sha(reply.body),sha(fs.readFileSync(path.join(rawAssets,file))));
      assert.equal(reply.headers['content-type'],file.endsWith('.svg')?'image/svg+xml':'image/webp');
    }
    const decoded=await page.evaluate(async asset=>{const img=new Image();img.src='/__fixture_assets/'+asset;await img.decode();return img.naturalWidth>0;},welcome);
    assert.equal(decoded,true);assert.deepEqual(external,[]);
  });
  console.log(JSON.stringify({scope:'RAW_ASSETS_LOCAL_HTTP_CHROMIUM_FACTORY_ONLY',runtime,cases:results.length,results,
    syntheticAssetSha256:sha(whale),userCredentialsUsed:false,publicNetworkUsed:false,deviceUsed:false},null,2));
} finally {
  try { if(fixture)await fixture.close(); }
  finally {
    if(originalHome===undefined)delete process.env.HOME;else process.env.HOME=originalHome;
    // 仅删除本测试刚创建的随机临时目录；不触碰 raw runtime/marker。
    assert.equal(path.dirname(base),fs.realpathSync(os.tmpdir()));
    assert(path.basename(base).startsWith('dsha-fixture-assets-'));
    fs.rmSync(base,{recursive:true,force:true});
  }
}
