#!/usr/bin/env python3
"""Run production Java admission for the web-search recipe and optional exact APKs."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import zipfile
import runtime_stable_patches

ROOT = Path(__file__).resolve().parents[1]
ASSET = 'deepseek-web-search-headers-patch.json'
MODULE = '@deepseek-ai/dsh-web-search-deepseek/lib/index.js'
OLD_APK_SHA = 'ba5fd1e19c5043fe000f02a3ffb56f125939a11b87bad0988d1677c73334cb26'
OLD_MODULE_SHA = 'fc13dd4eaba3c9298af41a4d0559a1b8bb61614462fe18ba6e76d8c598c332bd'
OLD_MODULE = ROOT / 'tools/fixtures/rc2-upgrade-source/dsh-web-search-deepseek.js'
JAVA = r'''
import com.deepseekharness.app.util.ManagedPatchChain;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
public final class WebSearchChainFixture {
  static void require(boolean condition,String reason) {if(!condition)throw new AssertionError(reason);}
  static List<ManagedPatchChain.Recipe> chain(Path root) throws Exception {
    var result=new ArrayList<ManagedPatchChain.Recipe>();
    for(int i=0;i<Integer.parseInt(Files.readString(root.resolve("count.txt")));i++) {
      Path entry=root.resolve(Integer.toString(i));var patches=new ArrayList<ManagedPatchChain.Patch>();
      for(int j=0;j<Integer.parseInt(Files.readString(entry.resolve("count.txt")));j++)
        patches.add(new ManagedPatchChain.Patch(Files.readString(entry.resolve(j+"-before.txt")),Files.readString(entry.resolve(j+"-after.txt"))));
      result.add(new ManagedPatchChain.Recipe(Files.readString(entry.resolve("id.txt")),patches,Files.readAllLines(entry.resolve("markers.txt"))));
    }
    return result;
  }
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]);var chain=chain(root.resolve("chain"));String expected=Files.readString(root.resolve("expected.js"));
    for(String input:List.of("official.js","pre.js","expected.js")) {
      String result=ManagedPatchChain.apply(Files.readString(root.resolve(input)),chain,()->{throw new AssertionError(input+" unexpectedly restored canonical");});
      require(result.equals(expected),input+" differs");
      System.out.println("PASS_WEB_SEARCH_CHAIN "+input);
    }
    var restores=new java.util.concurrent.atomic.AtomicInteger();
    String canonical=Files.readString(root.resolve("official.js"));
    require(ManagedPatchChain.apply("unknown historical source",chain,()->{restores.incrementAndGet();return canonical;}).equals(expected),"canonical recovery differs");
    require(restores.get()==1,"canonical recovery must rebuild exactly once");
    System.out.println("PASS_WEB_SEARCH_CHAIN guarded canonical recovery exactly once");
    for(String invalidCanonical:Arrays.asList(null,"unknown canonical source")) {
      restores.set(0);
      try {ManagedPatchChain.apply("unknown historical source",chain,()->{restores.incrementAndGet();return invalidCanonical;});throw new AssertionError("invalid canonical accepted");}
      catch(IOException error) {require(error.getMessage().contains(invalidCanonical==null?"PATCH_CANONICAL_MISSING":"PATCH_CHAIN_MISMATCH"),"wrong guarded error");}
      require(restores.get()==1,"invalid canonical must be read exactly once");
    }
    System.out.println("PASS_WEB_SEARCH_CHAIN missing and invalid canonical fail closed");
    String old=Files.readString(root.resolve("old-module.js"));
    restores.set(0);
    String upgraded=ManagedPatchChain.apply(old,chain,()->{restores.incrementAndGet();return canonical;});
    Files.writeString(root.resolve("old-module-result.js"),upgraded);
    Files.writeString(root.resolve("old-module-restores.txt"),Integer.toString(restores.get()));
    System.out.println("WEB_SEARCH_OLD_MODULE restores="+restores.get()+" equalsCurrent="+upgraded.equals(expected));
    require(restores.get()==0||restores.get()==1,"old module canonical restored more than once");
    require(upgraded.equals(expected),"old module differs from complete current module");
    require(ManagedPatchChain.apply(upgraded,chain,()->{throw new AssertionError("old upgrade repeat restored canonical");}).equals(expected),"old upgrade repeat differs");
    System.out.println("PASS_WEB_SEARCH_CHAIN historical module upgrade/repeat");
    if(Files.exists(root.resolve("apk.js"))) {
      String exact=Files.readString(root.resolve("apk.js"));require(exact.equals(expected),"exact APK module differs");
      require(ManagedPatchChain.apply(exact,chain,()->{throw new AssertionError("exact APK restore");}).equals(expected),"exact APK repeat differs");
      System.out.println("PASS_WEB_SEARCH_CHAIN exact final APK/repeat");
    }
  }
}
'''


def sha(data):
    return hashlib.sha256(data).hexdigest()


def module_from_apk(apk):
    with zipfile.ZipFile(apk) as package:
        with tarfile.open(fileobj=io.BytesIO(package.read('assets/dsh-runtime.bin')), mode='r:*') as runtime:
            matches = [row for row in runtime.getmembers() if row.isfile() and row.name.endswith('/node_modules/' + MODULE)]
            assert len(matches) == 1, 'APK_SEARCH_MODULE_UNIQUENESS'
            return runtime.extractfile(matches[0]).read()


def write_chain(folder, chain):
    folder.mkdir()
    (folder / 'count.txt').write_bytes(str(len(chain)).encode())
    for index, (name, patches, markers) in enumerate(chain):
        target = folder / str(index)
        target.mkdir()
        (target / 'id.txt').write_bytes(name.encode())
        (target / 'count.txt').write_bytes(str(len(patches)).encode())
        (target / 'markers.txt').write_bytes('\n'.join(markers).encode())
        for number, (before, after) in enumerate(patches):
            (target / (str(number) + '-before.txt')).write_bytes(before.encode())
            (target / (str(number) + '-after.txt')).write_bytes(after.encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path)
    parser.add_argument('--old-apk', type=Path, help='read-only exact retained build191/1640 Standard APK')
    parser.add_argument('--apk', type=Path, help='read-only exact final build192 Standard APK')
    args = parser.parse_args()
    if __debug__ is False:
        raise RuntimeError('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    assets = ROOT / 'app/src/main/assets'
    recipe = json.loads((assets / ASSET).read_text(encoding='utf8'))
    chains, _ = runtime_stable_patches.load_chains(assets, json.loads((ROOT / 'tools/dsh-runtime/package.json').read_text(encoding='utf8'))['dependencies']['@deepseek-ai/dsh'])
    chain = chains.get(MODULE, [])
    assert any(name == ASSET for name, _, _ in chain), 'WEB_SEARCH_RECIPE_MUST_BE_REGISTERED'
    pointer = json.loads((ROOT / 'app/build/test-runtimes/current.json').read_text(encoding='utf8'))
    canonical = (Path(pointer['raw']) / 'node_modules' / MODULE).read_bytes()
    assert sha(canonical) == recipe['sourceSha256'], 'LOCKED_SEARCH_MODULE_IDENTITY'
    expected = runtime_stable_patches.apply(canonical, chain)
    assert sha(expected) == recipe['patchedSha256'], 'FINAL_SEARCH_MODULE_IDENTITY'
    prior = [row for row in chain if row[0] != ASSET]
    pre = runtime_stable_patches.apply(canonical, prior)
    assert runtime_stable_patches.apply(pre, chain) == expected, 'CURRENT_PRE_CHAIN_IDENTITY'
    assert runtime_stable_patches.apply(expected, chain) == expected, 'CURRENT_REPEAT_IDENTITY'
    old = OLD_MODULE.read_bytes()
    assert sha(old) == OLD_MODULE_SHA, 'RETAINED_RC2_SEARCH_MODULE_IDENTITY'
    if args.old_apk:
        assert sha(args.old_apk.read_bytes()) == OLD_APK_SHA, 'OLD_191_APK_IDENTITY'
        old = module_from_apk(args.old_apk)
    assert sha(old) == OLD_MODULE_SHA, 'OLD_191_SEARCH_MODULE_IDENTITY'
    owned = Path(tempfile.mkdtemp(prefix='web-search-java-chain-', dir=ROOT / 'app/build'))
    for name, data in [('official.js', canonical), ('pre.js', pre), ('old-module.js', old), ('expected.js', expected)]:
        (owned / name).write_bytes(data)
    if args.apk:
        (owned / 'apk.js').write_bytes(module_from_apk(args.apk))
    write_chain(owned / 'chain', chain)
    (owned / 'WebSearchChainFixture.java').write_bytes(JAVA.encode())
    jdk = args.jdk or (Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None)
    suffix = '.exe' if os.name == 'nt' else ''
    javac = str(jdk / 'bin' / ('javac' + suffix)) if jdk else shutil.which('javac')
    java = str(jdk / 'bin' / ('java' + suffix)) if jdk else shutil.which('java')
    assert javac and java, 'JDK17_REQUIRED'
    production = ROOT / 'app/src/main/java/com/deepseekharness/app/util'
    classes = owned / 'classes'
    classes.mkdir()
    command = [javac, '-encoding', 'UTF-8', '--release', '17', '-d', str(classes), str(production / 'ManagedPatchChain.java'), str(production / 'ExactTextPatch.java'), str(ROOT / 'tools/fixtures/documents-parent/com/deepseekharness/app/util/UiText.java'), str(owned / 'WebSearchChainFixture.java')]
    compiled = subprocess.run(command, capture_output=True, timeout=60)
    (owned / 'compile.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, 'JAVA_COMPILE_FAILED: ' + str(owned)
    ran = subprocess.run([java, '-cp', str(classes), 'WebSearchChainFixture', str(owned)], capture_output=True, timeout=60)
    (owned / 'java.log').write_bytes(ran.stdout + ran.stderr)
    result = dict(status='PASS' if ran.returncode == 0 else 'FAIL', exitCode=ran.returncode, module=MODULE,
                  sourceSha256=sha(canonical), preModuleSha256=sha(pre), patchedSha256=sha(expected), oldModuleSha256=sha(old),
                   oldModuleResultSha256=sha((owned / 'old-module-result.js').read_bytes()) if (owned / 'old-module-result.js').exists() else None,
                   oldModuleCanonicalRestores=int((owned / 'old-module-restores.txt').read_text()) if (owned / 'old-module-restores.txt').exists() else None,
                  oldApkSha256=OLD_APK_SHA if args.old_apk else None,
                  historicalInput='actual retained build191/1640 APK' if args.old_apk else 'retained official rc2 npm module verified against historical lock; no APK supplied',
                  apkSha256=sha(args.apk.read_bytes()) if args.apk else None,
                  recipes=[name for name, _, _ in chain], productionAlgorithmSha256={name:sha((production / name).read_bytes()) for name in ['ManagedPatchChain.java','ExactTextPatch.java']},
                  deviceValidation=False, output=str(owned))
    (owned / 'result.json').write_bytes((json.dumps(result, indent=2) + '\n').encode())
    print(json.dumps(result))
    if ran.returncode:
        print((ran.stdout + ran.stderr).decode('utf8', errors='replace'))
        raise SystemExit(ran.returncode)


if __name__ == '__main__':
    main()
