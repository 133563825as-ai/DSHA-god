'use strict';
// Preparation only, future isolated Linux host. No registry calls, no historical index/config/scripts.
// node seed-project-cache.cjs <frozen package-lock> <project cache> <NEW private cache> <real npm-cli.js>
const fs = require('node:fs'); const p = require('node:path'); const crypto = require('node:crypto');
const { createRequire } = require('node:module');
function regular(x) { if (!fs.lstatSync(x).isFile()) throw Error('not regular: '+x); }
function noLinks(x) { for (let cur=x;;cur=p.dirname(cur)) { try { if (fs.lstatSync(cur).isSymbolicLink()) throw Error('symlink: '+cur); } catch (e) { if (e.code!=='ENOENT') throw e; } if (cur===p.dirname(cur)) break; } }
async function main() {
  if (process.argv.length!==6 || process.platform!=='linux' || process.arch!=='x64' || process.versions.node.split('.')[0]!=='24') throw Error('Linux x64 Node 24 / four path arguments required');
  const [lock,source,dest,npm]=process.argv.slice(2).map(s=>p.resolve(s));
  [lock,source,dest,npm].forEach(noLinks); regular(lock); regular(npm);
  if (p.basename(npm)!=='npm-cli.js' || !(fs.statSync(source).isDirectory()) || dest.startsWith(source+'/') || source.startsWith(dest+'/') || source===dest) throw Error('separate cache required');
  const fromNpm=createRequire(npm); const npmRoot=p.dirname(p.dirname(npm));
  if (JSON.parse(fs.readFileSync(p.join(npmRoot,'package.json'))).version.split('.')[0]!=='11') throw Error('npm 11 required');
  const ccPath=fromNpm.resolve('cacache'); if (!fs.realpathSync(ccPath).startsWith(fs.realpathSync(npmRoot)+'/node_modules/')) throw Error('not npm bundled cacache');
  const cc=fromNpm(ccPath); const packages=JSON.parse(fs.readFileSync(lock)).packages; const seen=new Map(), missing=[]; let count=0,bytes=0;
  fs.mkdirSync(dest,{mode:0o700}); // EEXIST fails, never reuse partial output
  const output=p.join(dest,'_cacache');
  for(const {resolved:url,integrity} of Object.values(packages)) {
    if(!url)continue; if(!/^https:\/\/registry\.npmjs\.org\//.test(url)||!/^sha512-[A-Za-z0-9+/]{86}==$/.test(integrity))throw Error('invalid locked key');
    if(seen.has(url)){if(seen.get(url)!==integrity)throw Error('conflicting key'); continue;} seen.set(url,integrity);
    const h=Buffer.from(integrity.slice(7),'base64').toString('hex'); const file=p.join(source,'_cacache/content-v2/sha512',h.slice(0,2),h.slice(2,4),h.slice(4)); noLinks(file);
    try{regular(file);}catch(e){if(e.code==='ENOENT'){missing.push(url); continue;}throw e;}
    const b=fs.readFileSync(file); if(crypto.createHash('sha512').update(b).digest('hex')!==h)throw Error('SRI mismatch');
    const key='make-fetch-happen:request-cache:'+url;
    await cc.put(output,key,b,{integrity,algorithms:['sha512'],metadata:{url,reqHeaders:{},resHeaders:{'content-type':'application/octet-stream'},options:{compress:true}}});
    const result=await cc.get(output,key); if(String(result.integrity)!==integrity||!b.equals(result.data))throw Error('cache readback');count++;bytes+=b.length;
  }
  console.log(JSON.stringify({label:'INTERNAL_LOCAL_CACHE_SEED_NOT_INSTALL',lockSha256:crypto.createHash('sha256').update(fs.readFileSync(lock)).digest('hex'),count,bytes,missing,completeForAllURLs:missing.length===0}));
}
main().catch(e=>{console.error(e.message);process.exitCode=1;});
