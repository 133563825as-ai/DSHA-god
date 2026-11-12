// Differential check: the native inventory and enable path must describe the
// very same bundle that the locked current loader will load when package names clash.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
import {testRuntime,requireNativeHost} from './test-runtime-fixture.mjs';
const runtime=testRuntime('raw');requireNativeHost(runtime);
const {resolveBundleDir,loadProfileDirectory}=await import(pathToFileURL(path.join(runtime,'node_modules/@deepseek-ai/dsh-app-boot/lib/index.js')));
const root=path.resolve(import.meta.dirname,'..');
const temporary=fs.mkdtempSync(path.join(root,'app/build/plugin-resolution-order-'));
const name='@author/optional-fixture';
const install=path.join(temporary,'usr/local/lib/node_modules/@deepseek-ai/dsh');
const profile=path.join(temporary,'root/.dsh/profiles/web');
const user=path.join(profile,'node_modules',name),provided=path.join(install,'node_modules',name);
const put=(file,value)=>{fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,typeof value==='string'?value:JSON.stringify(value));};
try {
  put(path.join(install,'package.json'),{name:'@deepseek-ai/dsh',version:'0.2.2-rc.1'});
  for(const [directory,version]of [[provided,'0.2.0'],[user,'9.0.0-user-edit']]){
    put(path.join(directory,'package.json'),{name,version,dsh:{bundle:{patch:'cordis.patch.yml'}}});
    put(path.join(directory,'cordis.patch.yml'),'[]\n');
  }
  put(path.join(user,'user-edit.js'),'// Keep the user-owned source when a runtime bundle wins.\n');
  put(path.join(profile,'package.json'),{name:'fixture-profile',version:'1.0.0',dependencies:{[name]:'9.0.0-user-edit'},dsh:{profile:{bundles:[name]}}});
  const original=fs.readFileSync(path.join(user,'user-edit.js'));
  const anchor=path.join(install,'package.json');
  const actual=resolveBundleDir('dsh',name,anchor,profile);
  const loaded=loadProfileDirectory('dsh',profile,anchor);
  assert.equal(fs.realpathSync(actual),fs.realpathSync(provided));
  assert.equal(loaded.skippedBundles.length,0);
  assert.equal(fs.realpathSync(loaded.layers[0].packageDir),fs.realpathSync(provided));
  const script=`import importlib.util,json,os
spec=importlib.util.spec_from_file_location('manager',os.path.join(os.environ['DSHA_RESOLUTION_ASSETS'],'plugin-manager.py'))
manager=importlib.util.module_from_spec(spec);spec.loader.exec_module(manager)
captured={}
def result(status,message,**extra): captured.update(extra)
manager.result=result
name=os.environ['DSHA_RESOLUTION_NAME']
if os.environ.get('DSHA_RESOLUTION_PROOF')=='create':
 print(manager.builtin.package_content_proof(manager.builtin.runtime_bundle_dir(name)));raise SystemExit
manager.cmd_list()
item=next(row for row in captured['items'] if row['name']==name)
print(json.dumps(dict(resolve=manager.resolve_plugin_dir(name),entity=manager.local(manager.builtin.entity_dir(name)),version=item['version'],runtimeProvided=item['runtimeProvided'],deletable=item['deletable'])))
`;
  const python=process.env.DSHA_PYTHON||'python';
  // 合成启动器绑定证明只验证reader与字节匹配，不冒充Android信任链验收。
  const env={...process.env,DSHA_TEST_ROOT:temporary,DSH_HOME:'/root/.dsh',DSHA_RESOLUTION_ASSETS:path.join(root,'app/src/main/assets'),DSHA_RESOLUTION_NAME:name};
  delete env.DSHA_MANAGED_PACKAGE_PROOFS_SHA256;
  const call=(extra={})=>{const r=spawnSync(python,['-B','-c',script],{cwd:root,encoding:'utf8',env:{...env,...extra},timeout:30000});assert.equal(r.status,0,r.stderr||r.error?.message);return r.stdout.trim();};
  const check=(native,managed)=>{assert.equal(fs.realpathSync(native.resolve),fs.realpathSync(actual));assert.equal(fs.realpathSync(native.entity),fs.realpathSync(actual));assert.equal(native.version,'0.2.0');assert.equal(native.runtimeProvided,managed);assert.equal(native.deletable,false);};
  const missing=JSON.parse(call());check(missing,false);
  const digest=call({DSHA_RESOLUTION_PROOF:'create'});assert.match(digest,/^[a-f0-9]{64}$/);
  const proofFile=path.join(temporary,'root/.dsh/managed-package-proofs.json');put(proofFile,{version:1,packages:{[name]:digest}});
  const proofHash=createHash('sha256').update(fs.readFileSync(proofFile)).digest('hex');
  const verified=JSON.parse(call({DSHA_MANAGED_PACKAGE_PROOFS_SHA256:proofHash}));check(verified,true);
  const unboundProof=JSON.parse(call());check(unboundProof,false);
  const invalid=JSON.parse(call({DSHA_MANAGED_PACKAGE_PROOFS_SHA256:'0'.repeat(64)}));check(invalid,false);
  put(path.join(provided,'cordis.patch.yml'),'# modified after proof\n[]\n');
  const changed=JSON.parse(call({DSHA_MANAGED_PACKAGE_PROOFS_SHA256:proofHash}));check(changed,false);
  assert.equal(fs.realpathSync(resolveBundleDir('dsh',name,anchor,profile)),fs.realpathSync(actual));
  assert.equal(loadProfileDirectory('dsh',profile,anchor).skippedBundles.length,0);
  console.log(JSON.stringify({missing,verified,unboundProof,invalidProof:invalid,packageChanged:changed}));
  assert.deepEqual(fs.readFileSync(path.join(user,'user-edit.js')),original);
  assert.equal(JSON.parse(fs.readFileSync(path.join(user,'package.json'),'utf8')).version,'9.0.0-user-edit');
  console.log('PASS: actual current app-boot, native inventory and enable resolution select the same installation bundle; same-name user source is preserved.');
} finally {
  fs.rmSync(temporary,{recursive:true,force:true});
}
