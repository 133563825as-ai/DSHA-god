// 从当前锁定 DSH 的实际前端获取 React/DOM/Primitives；不使用另一个版本的渲染器冒充。
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const assetPrefix='/__fixture_assets/';
const maxAssetBytes=8*1024*1024;
const mimeTypes={'.png':'image/png','.svg':'image/svg+xml','.jpg':'image/jpeg','.jpeg':'image/jpeg',
  '.webp':'image/webp','.woff':'font/woff','.woff2':'font/woff2'};
const frontendMimeTypes={...mimeTypes,'.js':'text/javascript','.css':'text/css'};
// 两端使用同一规则；只解码一次，残留编码、路径分隔符编码与点段均不接受。
function fixtureAssetPath(value,moduleId) {
  if(typeof value!=='string'||!value||value.length>2048||/%(?:2f|5c)/i.test(value))throw Error('Invalid fixture asset path');
  let decoded;
  try { decoded=decodeURIComponent(value); } catch { throw Error('Invalid fixture asset encoding'); }
  const parts=decoded.split('/');
  if(parts.some(part=>!part||part==='.'||part==='..'||!/^[A-Za-z0-9_@.+-]+$/.test(part))
    ||parts.length<5||parts[0]!=='plugins'||parts[1]!=='@deepseek-ai'||parts[3]!=='assets'
    ||!/^[a-z0-9][a-z0-9._-]*$/.test(parts[2])||(moduleId!==undefined&&moduleId!=='@deepseek-ai/'+parts[2]))
    throw Error('Invalid fixture asset package/path');
  return decoded;
}
function checkedFile(root,relative,types=mimeTypes) {
  const file=fs.realpathSync(path.join(root,relative));
  if(!file.startsWith(root+path.sep))throw Error('Fixture asset escapes root');
  const stat=fs.statSync(file);
  if(!stat.isFile()||stat.size>maxAssetBytes||!types[path.extname(file).toLowerCase()])
    throw Error('Unsupported fixture asset type/size');
  return file;
}
function readAsset(file) {
  const fd=fs.openSync(file,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW);
  try {
    const stat=fs.fstatSync(fd);
    if(!stat.isFile()||stat.size>maxAssetBytes)throw Error('Fixture asset size changed');
    const buffer=Buffer.alloc(stat.size+1);
    let size=0,count;
    while(size<buffer.length&&(count=fs.readSync(fd,buffer,size,buffer.length-size,null)))size+=count;
    if(size>stat.size)throw Error('Fixture asset size changed');
    return buffer.subarray(0,size);
  } finally { fs.closeSync(fd); }
}
function collectAssets(runtime,name) {
  // 包级链接（如 pnpm）可合法指向 store；lib 与 assets 仍须留在该 canonical 包内。
  const packageRoot=fs.realpathSync(path.resolve(runtime,'node_modules/@deepseek-ai',name));
  const lib=fs.realpathSync(path.join(packageRoot,'lib'));
  if(!lib.startsWith(packageRoot+path.sep))throw Error('Fixture lib escapes package root');
  const assets=path.join(lib,'assets'),files=new Map();
  if(!fs.existsSync(assets))return {root:assets,files};
  const root=fs.realpathSync(assets);
  if(root!==assets||!root.startsWith(packageRoot+path.sep))throw Error('Fixture assets root is a symlink');
  function visit(directory) {
    for(const entry of fs.readdirSync(directory,{withFileTypes:true})) {
      const file=path.join(directory,entry.name);
      if(entry.isDirectory())visit(file);
      else {
        const relative=path.relative(root,file).split(path.sep).join('/');
        const key='plugins/@deepseek-ai/'+name+'/assets/'+relative;
        try { fixtureAssetPath(key,'@deepseek-ai/'+name);checkedFile(root,relative);files.set(key,relative); }
        catch { /* 不登记缺失、逃逸、非静态类型或超限资源。 */ }
      }
    }
  }
  visit(root);
  return {root,files};
}
export async function browserFixture(runtime=process.env.DSHA_TEST_RUNTIME||JSON.parse(fs.readFileSync('app/build/test-runtimes/current.json','utf8')).raw, frontendRecipes=[]) {
  const root=path.resolve(runtime,'node_modules/@deepseek-ai/dsh-web-frontend/dist');
  const html=fs.readFileSync(path.join(root,'index.html'),'utf8');
  const entry=html.match(/src="\.\/(assets\/index-[^"]+\.js)"/)[1];
  let bootstrap=fs.readFileSync(path.join(root,entry),'utf8');
  for(const recipe of frontendRecipes)for(const patch of recipe.patches){
    if(recipe.dshVersion!==JSON.parse(fs.readFileSync(path.resolve(runtime,'node_modules/@deepseek-ai/dsh/package.json'),'utf8')).version
      ||recipe.module!=='@deepseek-ai/dsh-web-frontend/dist/'+entry
      ||bootstrap.split(patch.before).length!==2)throw Error('current frontend patch version/module/anchor changed');
    bootstrap=bootstrap.replace(patch.before,patch.after);
  }
  const moduleMatch=bootstrap.match(/function ([A-Za-z_$][\w$]*)\(\)\{return\{react:/);
  if(!moduleMatch)throw Error('current frontend static module anchor changed');
  // Rollup changes the local identifier in each release (rc2 uses `fo`).
  // Match the semantic boot boundary once rather than older minified names;
  // the prefix still contains this frontend's actual React and Primitives.
  const boundaries=[...bootstrap.matchAll(/\bconst\s+[A-Za-z_$][\w$]*=globalThis\.dshDesktopBoot\b/g)];
  if(boundaries.length!==1||boundaries[0].index<=moduleMatch.index)
    throw Error('current frontend boot boundary changed');
  const boundaryIndex=boundaries[0].index;
  bootstrap=bootstrap.slice(0,boundaryIndex)+
    `globalThis.auditModules=${moduleMatch[1]}();globalThis.auditExports={};globalThis.auditAssetFiles={};globalThis.auditLoadErrors={};
    const fixtureAssetPath=${fixtureAssetPath.toString()};
    window.__ModuleLoader__={load:({id,factory})=>{
      const require=name=>{if(!(name in auditModules))throw Error('Missing static module '+name);return auditModules[name]};
      require.asset=value=>{
        if(!Object.hasOwn(auditAssetFiles,id))throw Error('Unknown fixture asset module '+id);
        const key=fixtureAssetPath(value,id);
        if(!auditAssetFiles[id].includes(key))throw Error('Missing fixture asset '+key);
        return ${JSON.stringify(assetPrefix)}+key.split('/').map(encodeURIComponent).join('/');
      };
      try { auditExports[id]=factory(require); }
      catch(error) { auditLoadErrors[id]=String(error);throw error; }
    }};`;
  const loadedAssets=new Map(),frontendRoot=fs.realpathSync(root);
  const server=http.createServer((req,res)=>{
    try {
      if(req.method!=='GET'&&req.method!=='HEAD'){res.writeHead(405,{'Allow':'GET, HEAD'});res.end();return;}
      res.setHeader('X-Content-Type-Options','nosniff');
      if(req.url==='/'){res.setHeader('Content-Type','text/html');res.end(req.method==='HEAD'?undefined:'<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body><div id="root"></div><script type="module" src="/assets/__audit.js"></script></body></html>');return;}
      if(req.url==='/assets/__audit.js'){res.setHeader('Content-Type','text/javascript');res.end(req.method==='HEAD'?undefined:bootstrap);return;}
      let file;
      if(req.url.startsWith(assetPrefix)) {
        const raw=req.url.slice(assetPrefix.length);
        let key;
        try { key=fixtureAssetPath(raw); } catch { res.writeHead(400);res.end();return; }
        const record=loadedAssets.get('@deepseek-ai/'+key.split('/')[2]);
        if(!record?.files.has(key)){res.writeHead(404);res.end();return;}
        file=checkedFile(record.root,record.files.get(key));
      } else {
        // 仅开放真实 frontend 的 assets 静态文件，不成为任意文件服务器。
        const raw=req.url.split('?')[0];
        if(!raw.startsWith('/assets/')||/%(?:2f|5c)/i.test(raw)){res.writeHead(404);res.end();return;}
        const relative=decodeURIComponent(raw.slice(1));
        if(relative.split('/').some(part=>!part||part==='.'||part==='..'||!/^[A-Za-z0-9_@.+-]+$/.test(part))) {
          res.writeHead(400);res.end();return;
        }
        file=checkedFile(frontendRoot,relative,frontendMimeTypes);
      }
      const bytes=readAsset(file);
      res.setHeader('Content-Type',frontendMimeTypes[path.extname(file).toLowerCase()]);
      res.setHeader('Content-Length',bytes.length);
      res.end(req.method==='HEAD'?undefined:bytes);
    } catch {
      // 包括 malformed URI、缺文件与 symlink 变化；不能让一次坏请求结束 fixture 进程。
      if(!res.headersSent)res.writeHead(404);
      res.end();
    }
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  let playwright, browser;
  try {
  try { playwright=require(process.env.DSHA_PLAYWRIGHT||'playwright'); }
  catch { playwright=require(path.join(process.env.USERPROFILE||'', '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')); }
  const engine=process.env.DSHA_BROWSER || 'chromium';
  if (!['chromium','firefox'].includes(engine)) throw Error('Unsupported fixture browser: '+engine);
  browser=await playwright[engine].launch({headless:true,...engine==='firefox'?{firefoxUserPrefs:{'ui.primaryPointerCapabilities':1,'ui.allPointerCapabilities':1}}:{}});
  const page=await browser.newPage({viewport:{width:360,height:800},deviceScaleFactor:1,hasTouch:true});
  const errors=[];page.on('pageerror',e=>errors.push(String(e)));
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(()=>globalThis.auditModules);
  return {page,browser,errors,async close(){await browser.close();await new Promise(resolve=>server.close(resolve));},
    async load(name,recipes=[],extra='') {
      if(typeof name!=='string'||!/^[a-z0-9][a-z0-9._-]*$/.test(name)||name==='.'||name==='..')throw Error('Invalid fixture module package');
      const id='@deepseek-ai/'+name;
      const record=collectAssets(runtime,name);
      let source=fs.readFileSync(path.resolve(runtime,'node_modules/@deepseek-ai',name,'lib/client.js'),'utf8');
      loadedAssets.set(id,record);
      await page.evaluate(({id,files})=>{auditAssetFiles[id]=files;delete auditExports[id];delete auditLoadErrors[id];},{id,files:[...record.files.keys()]});
      for(const recipe of recipes)for(const patch of recipe.patches){
        if(source.split(patch.before).length!==2)throw Error('Patch anchor: '+patch.before.slice(0,80));
        source=source.replace(patch.before,(patch.prependAsset?fs.readFileSync('app/src/main/assets/'+patch.prependAsset,'utf8')+'\n':'')+patch.after);
      }
      source=source.replace('return module.exports;',extra+'\nreturn module.exports;');
      const errorStart=errors.length;
      await page.addScriptTag({content:source});
      // addScriptTag 对 factory 内未捕获异常仍可能 resolve；同步结果不依赖 pageerror 事件送达时序。
      const outcome=await page.evaluate(id=>({error:auditLoadErrors[id],exported:Object.hasOwn(auditExports,id)}),id);
      if(outcome.error||!outcome.exported||errors.length>errorStart)throw Error('BROWSER_FIXTURE_MODULE_LOAD_FAILED '+id+': '+
        (outcome.error||(!outcome.exported?'Missing fixture module export':errors.slice(errorStart).join('\n'))));
      const css=path.resolve(runtime,'node_modules/@deepseek-ai',name,'lib/client.css');
      if(fs.existsSync(css))await page.addStyleTag({content:fs.readFileSync(css,'utf8')});
    }};
  } catch (error) {
    const cleanupErrors=[];
    try { if (browser) await browser.close(); } catch (cleanupError) { cleanupErrors.push(cleanupError); }
    try { await new Promise((resolve,reject)=>server.close(error=>error?reject(error):resolve())); }
    catch (cleanupError) { cleanupErrors.push(cleanupError); }
    if (cleanupErrors.length) throw new AggregateError([error,...cleanupErrors],
      'BROWSER_FIXTURE_INITIALIZATION_AND_CLEANUP_FAILED',{cause:error});
    throw error;
  }
}
