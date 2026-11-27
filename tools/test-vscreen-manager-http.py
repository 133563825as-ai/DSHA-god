"""Compile current Java and exercise its real loopback HTTP concurrency; no phone/Gradle/APK."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def release_resource_inputs():
    # These are the actual AGP source-set inputs, including generated resValues.
    # Never treat merged/packaged outputs as new source inputs.
    path_map = ROOT / 'app/build/intermediates/android_res_source_set_path_map/standardRelease/mapStandardReleaseSourceSetPaths/file-map.txt'
    if not path_map.is_file():
        raise RuntimeError('CURRENT_STANDARD_RELEASE_RESOURCE_INPUTS_REQUIRED')
    roots = [ROOT / ('app/src/' + name + '/res')
             for name in ('main', 'release', 'standard', 'standardRelease')]
    generated = (ROOT / 'app/build/generated').resolve()
    for line in path_map.read_text(encoding='utf-8').splitlines():
        path = Path(line.split(' ', 1)[1]).resolve()
        if path.is_relative_to(generated):
            roots.append(path)
    files = sorted({path.resolve() for folder in roots for path in folder.rglob('*')
                    if path.is_file()}, key=str)
    if not files:
        raise RuntimeError('CURRENT_STANDARD_RELEASE_RESOURCE_INPUTS_REQUIRED')
    controls = [path_map, ROOT / 'build.gradle', ROOT / 'app/build.gradle', ROOT / 'gradle.properties']
    return files, {str(path.relative_to(ROOT)): digest(path) for path in files + controls}


def canonical_symbol(resource_type, name, array=False):
    # 仅统一符号拼写并保留数组身份；子项索引由额外的结构校验负责。
    return (resource_type, name.replace('.', '_').replace(':', '_'),
            'array' if array else 'scalar')


def resource_symbols(text, kind, metadata=None):
    if kind not in ('aapt', 'definitions', 'compile'):
        raise RuntimeError('UNSUPPORTED_CURRENT_RESOURCE_SYMBOL_KIND:' + kind)
    # 不改变四源 set 契约；调用方可同时取得有序 children、数组长度和索引。
    structure = {'children': {}, 'lengths': {}, 'indices': {}}
    resource_types = set(('anim animator array attr bool color dimen drawable font fraction id '
                          'integer interpolator layout menu mipmap navigation plurals raw string '
                          'style styleable transition xml').split())
    symbols = set()
    for line in text.splitlines():
        words = line.split()
        if not words:
            continue
        if kind == 'definitions' and line in (
                'R_DEF: Internal format may change without notice', 'local'):
            continue
        if kind == 'aapt':
            # aapt2 compile --output-text-symbols: <visibility> <type> <name>.
            if (len(words) != 4 or words[0] not in ('default', 'public', 'private')
                    or words[2] not in resource_types):
                raise RuntimeError('UNSUPPORTED_CURRENT_RESOURCE_SYMBOL: ' + line)
            if words[1] == 'int':
                symbols.add(canonical_symbol(words[2], words[3]))
            elif words[1] == 'int[]' and words[2] == 'styleable':
                symbols.add(canonical_symbol('styleable', words[3], array=True))
            else:
                raise RuntimeError('UNSUPPORTED_CURRENT_RESOURCE_SYMBOL: ' + line)
        elif kind == 'definitions':
            # R-def.txt lists a styleable's children inline; expand them to the
            # generated R.styleable.<Name>_<child> fields the other sources carry.
            if words[0] == 'styleable' and len(words) >= 2:
                name = canonical_symbol('styleable', words[1], array=True)[1]
                children = tuple(canonical_symbol('styleable', words[1] + '_' + child)[1]
                                 for child in words[2:])
                if name in structure['children'] or len(set(children)) != len(children):
                    raise RuntimeError('UNSUPPORTED_CURRENT_RESOURCE_SYMBOL: ' + line)
                structure['children'][name] = children
                symbols.add(canonical_symbol('styleable', words[1], array=True))
                for child in children:
                    symbols.add(canonical_symbol('styleable', child))
            elif words[0] == 'attr?' and len(words) == 2:
                # An attr declared only inside a styleable is tagged attr? here but
                # still becomes an R.attr field.
                symbols.add(canonical_symbol('attr', words[1]))
            elif len(words) == 2 and words[0] in resource_types:
                symbols.add(canonical_symbol(words[0], words[1]))
            else:
                raise RuntimeError('UNSUPPORTED_CURRENT_RESOURCE_SYMBOL: ' + line)
        else:
            # compile R.txt: IDs are zero; styleable children hold array indices.
            if words[0] == 'int' and len(words) == 4 and words[1] in resource_types:
                resource_type, name, value = words[1:4]
                if resource_type == 'styleable':
                    name = canonical_symbol(resource_type, name)[1]
                    if not re.fullmatch(r'0|[1-9][0-9]*', value) or name in structure['indices']:
                        raise RuntimeError('CURRENT_COMPILE_R_SYMBOL_FORMAT: ' + line)
                    structure['indices'][name] = int(value)
                elif not re.fullmatch(r'0|0x0+', value):
                    raise RuntimeError('CURRENT_COMPILE_R_SYMBOL_FORMAT: ' + line)
                symbols.add(canonical_symbol(resource_type, name))
            elif words[0] == 'int[]':
                array = re.fullmatch(r'int\[\] styleable (\w+) \{\s*(.*?)\s*\}', line)
                if not array:
                    raise RuntimeError('CURRENT_COMPILE_R_SYMBOL_FORMAT: ' + line)
                values = array[2].split(',') if array[2] else []
                name = canonical_symbol('styleable', array[1], array=True)[1]
                if (name in structure['lengths']
                        or any(not re.fullmatch(r'0|0x0+', value.strip()) for value in values)):
                    raise RuntimeError('CURRENT_COMPILE_R_SYMBOL_FORMAT: ' + line)
                structure['lengths'][name] = len(values)
                symbols.add(canonical_symbol('styleable', name, array=True))
            else:
                raise RuntimeError('CURRENT_COMPILE_R_SYMBOL_FORMAT: ' + line)
    if metadata is not None:
        metadata.update(structure)
    return symbols


def javap_symbols(text, metadata=None):
    actual = set()
    indices = {}
    resource_type = None
    for line in text.splitlines():
        if not line.strip() or re.fullmatch(r'Compiled from "[^"]+"', line):
            continue
        match = re.fullmatch(r'public final class com\.deepseekharness\.app\.R\$(\w+) \{', line)
        if match and resource_type is None:
            resource_type = match[1]
        elif line == '}' and resource_type is not None:
            resource_type = None
        elif line.strip() == 'public static {};' and resource_type is not None:
            continue
        else:
            field = re.fullmatch(r'  public static int (\w+) = (0|[1-9][0-9]*);', line)
            if field and resource_type is not None:
                name, value = field[1], field[2]
                if resource_type == 'styleable':
                    name = canonical_symbol(resource_type, name)[1]
                    if name in indices:
                        raise RuntimeError('CURRENT_COMPILE_R_CLASS_FORMAT: ' + line)
                    indices[name] = int(value)
                elif value != '0':
                    raise RuntimeError('CURRENT_COMPILE_R_CLASS_FORMAT: ' + line)
                actual.add(canonical_symbol(resource_type, name))
                continue
            array = re.fullmatch(r'  public static int\[\] (\w+);', line)
            if array and resource_type == 'styleable':
                actual.add(canonical_symbol('styleable', array[1], array=True))
                continue
            raise RuntimeError('CURRENT_COMPILE_R_CLASS_FORMAT: ' + line)
    if resource_type is not None:
        raise RuntimeError('CURRENT_COMPILE_R_CLASS_FORMAT: unclosed class')
    if metadata is not None:
        metadata['indices'] = indices
    return actual


def require_styleable_structure(definitions, compile_txt, classes):
    # R-def 保留声明顺序，但 Android attrs 的排列不能机械地当成数组索引。
    # 这里核验显式 children/数量、完整索引排列，以及 R.txt 与类常量的一致性。
    children = definitions['children']
    indices = compile_txt['indices']
    expected = [child for group in children.values() for child in group]
    if (set(children) != set(compile_txt['lengths'])
            or len(set(expected)) != len(expected) or set(expected) != set(indices)):
        raise RuntimeError('STALE_STANDARD_RELEASE_STYLEABLE_STRUCTURE:R-def/R.txt children')
    for name, group in children.items():
        if (compile_txt['lengths'][name] != len(group)
                or sorted(indices[child] for child in group) != list(range(len(group)))):
            raise RuntimeError('STALE_STANDARD_RELEASE_STYLEABLE_STRUCTURE:R.txt:' + name)
    if classes['indices'] != indices:
        raise RuntimeError('STALE_STANDARD_RELEASE_STYLEABLE_STRUCTURE:R.txt/R.jar indices')


def require_same_symbols(actual, expected, stage):
    if actual != expected:
        raise RuntimeError('STALE_STANDARD_RELEASE_RESOURCE_SYMBOLS:' + stage
                           + ':missing=' + repr(sorted(expected - actual)[:8])
                           + ':extra=' + repr(sorted(actual - expected)[:8]))


def current_agp_aapt2(cache):
    agp = re.search(r"id 'com.android.application' version '([^']+)'", (ROOT / 'build.gradle').read_text(encoding='utf-8'))
    if not agp:
        raise RuntimeError('CURRENT_AAPT2_VERSION_REQUIRED')
    wrapper = (ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text(encoding='utf-8')
    gradle = re.search(r'^distributionUrl=.*/gradle-([0-9]+(?:\.[0-9]+)+)-(?:bin|all)\.zip$', wrapper, re.MULTILINE)
    if not gradle:
        raise RuntimeError('CURRENT_GRADLE_VERSION_REQUIRED')
    classifier = {'linux': 'linux', 'darwin': 'osx', 'win32': 'windows'}.get(sys.platform)
    if classifier is None:
        raise RuntimeError('UNSUPPORTED_AAPT2_PLATFORM:' + sys.platform)
    executable = 'aapt2.exe' if classifier == 'windows' else 'aapt2'
    candidates = sorted(path for path in cache.glob(
        'caches/' + gradle[1] + '/transforms/**/aapt2-' + agp[1] + '-*-' + classifier + '/' + executable)
                        if path.is_file())
    if not candidates or len({digest(path) for path in candidates}) != 1:
        raise RuntimeError('CURRENT_AGP_AAPT2_REQUIRED')
    return candidates[0]


def verify_release_resource_symbols(work, cache, jdk):
    # AGP's compile R is the Java input; its zero IDs are deliberate. Runtime R
    # may retain older timestamps when resource values change but IDs do not.
    resource = ROOT / 'app/build/intermediates/compile_r_class_jar/standardRelease/generateStandardReleaseRFile/R.jar'
    symbols = ROOT / 'app/build/intermediates/compile_symbol_list/standardRelease/generateStandardReleaseRFile/R.txt'
    definitions = ROOT / 'app/build/intermediates/local_only_symbol_list/standardRelease/parseStandardReleaseLocalResources/R-def.txt'
    artifacts = [resource, symbols, definitions]
    if not all(path.is_file() for path in artifacts):
        raise RuntimeError('CURRENT_STANDARD_RELEASE_COMPILE_RESOURCES_REQUIRED')
    aapt = current_agp_aapt2(cache)
    files, inputs = release_resource_inputs()
    artifacts_before = {str(path.relative_to(ROOT)): digest(path) for path in artifacts}

    def compile_symbols(item):
        index, source = item
        folder = work / 'resource-symbols' / str(index)
        staged = folder / 'res' / source.parent.name / source.name
        staged.parent.mkdir(parents=True)
        data = source.read_bytes()
        if hashlib.sha256(data).hexdigest() != inputs[str(source.relative_to(ROOT))]:
            raise RuntimeError('RESOURCE_INPUT_CHANGED_DURING_CHECK')
        staged.write_bytes(data)
        output = folder / 'compiled'; output.mkdir()
        text = folder / 'symbols.txt'
        subprocess.run([str(aapt), 'compile', str(staged), '--output-text-symbols', str(text),
                        '-o', str(output)], check=True, timeout=30, capture_output=True)
        if not text.is_file():
            # AAPT2 validates/compiles binary resources but does not emit a symbol
            # text file for them; their resource key is the validated filename.
            if source.suffix == '.xml' or not list(output.glob('*.flat')):
                raise RuntimeError('CURRENT_AAPT2_RESOURCE_SYMBOLS_REQUIRED')
            return {canonical_symbol(source.parent.name.split('-')[0], source.name.split('.')[0])}
        return resource_symbols(text.read_text(encoding='utf-8'), 'aapt')

    fresh = set()
    # Compile immutable copies into this test's private directory, not Gradle outputs.
    with ThreadPoolExecutor(max_workers=2) as pool:
        for value in pool.map(compile_symbols, enumerate(files)):
            fresh.update(value)
    definition_structure, compile_structure, class_structure = {}, {}, {}
    require_same_symbols(resource_symbols(definitions.read_text(encoding='utf-8'), 'definitions', definition_structure), fresh, 'R-def')
    require_same_symbols(resource_symbols(symbols.read_text(encoding='utf-8'), 'compile', compile_structure), fresh, 'R.txt')
    with zipfile.ZipFile(resource) as jar:
        classes = sorted(name[:-6].replace('/', '.') for name in jar.namelist()
                         if name.startswith('com/deepseekharness/app/R$') and name.endswith('.class'))
    javap = jdk / 'bin' / ('javap.exe' if os.name == 'nt' else 'javap')
    result = subprocess.run([str(javap), '-classpath', str(resource), '-constants'] + classes,
                            check=True, capture_output=True, text=True, timeout=30)
    require_same_symbols(javap_symbols(result.stdout, class_structure), fresh, 'R.jar')
    require_styleable_structure(definition_structure, compile_structure, class_structure)
    if release_resource_inputs()[1] != inputs or any(digest(path) != artifacts_before[str(path.relative_to(ROOT))] for path in artifacts):
        raise RuntimeError('RESOURCE_INPUT_CHANGED_DURING_CHECK')
    proof = {'scope': 'current Java compile symbols and styleable child indices; no jar array initializer or resource value packaging acceptance',
             'resourceInputs': inputs, 'agpArtifacts': artifacts_before, 'aapt2Sha256': digest(aapt),
             'styleableDefinitions': definition_structure['children'],
             'styleableArrayLengths': compile_structure['lengths'],
             'styleableChildIndices': compile_structure['indices'], 'symbolCount': len(fresh)}
    print('PASS current StandardRelease resource bytes -> AAPT2 symbols -> AGP R-def/R.txt/R.jar (' + str(len(fresh)) + ' symbols); no resource-value packaging acceptance claimed')
    return resource, proof


def run_release_source_tests(current, work, cache, sdk, junit, hamcrest, host_json):
    # Compile the actual production release sources against Android37. Runtime JSON
    # is a separately locked host implementation; Android stubs are not UI acceptance.
    resource, proof = verify_release_resource_symbols(work, cache, Path(os.environ['JAVA_HOME']))
    (ROOT / 'app/build/vscreen-test/release-resource-symbol-proof.json').write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')
    generated = work / 'generated'
    subprocess.run([os.sys.executable, '-B', str(ROOT / 'tools/prepare-ui-languages.py'),
                    '--output', str(generated)], check=True, timeout=30)
    locked, coordinates = current.locked_backup_classpath(cache)
    cp = [str(sdk), str(resource), str(junit), str(hamcrest)] + locked
    cp += [str(path) for path in cache.glob('caches/9.3.1/transforms/**/classes.jar') if 'instrumented' not in path.parts]
    cp += [str(path) for path in cache.glob('caches/modules-2/files-2.1/**/*.jar')
           if not path.name.endswith(('-sources.jar', '-javadoc.jar'))
           and not any(group in path.parts and name in path.parts for group, name in coordinates)]
    sources = [str(path) for folder in ('main', 'standard')
               for path in (ROOT / ('app/src/' + folder + '/java')).rglob('*.java')]
    sources += [str(path) for path in (ROOT / 'tools/aidl-stub').rglob('*.java')]
    sources += [str(path) for path in generated.rglob('*.java')]
    build_config = list((ROOT / 'app/build/generated/source/buildConfig/standard/release').rglob('*.java'))
    if not build_config:
        raise RuntimeError('CURRENT_RELEASE_BUILD_CONFIG_REQUIRED')
    sources += list(map(str, build_config))
    current._javac(sources, work / 'main', cp)
    names = ['util/CoalescingPollerTest', 'util/VirtualScreenRequestFenceTest',
             'util/TouchGestureDispatchTest', 'util/VirtualScreenPolicyTest',
             'util/VscreenBridgeRequestTest', 'util/ScreenSessionGrantTest',
             'vscreen/VirtualScreenManagerRevocationTest', 'vscreen/VirtualScreenManagerOwnershipTest']
    tests = [str(ROOT / ('app/src/test/java/com/deepseekharness/app/' + name + '.java')) for name in names]
    current._javac(tests, work / 'test', cp + [str(work / 'main')])
    run_cp = os.pathsep.join([str(host_json), str(work / 'test'), str(work / 'main')] + cp)
    arguments = work / 'junit-args.txt'
    current.write_argfile(arguments, ['-Dfile.encoding=UTF-8', '-cp', run_cp, 'org.junit.runner.JUnitCore']
                          + ['com.deepseekharness.app.' + name.replace('/', '.') for name in names],
                          encoding='mbcs' if os.name == 'nt' else 'utf-8')
    subprocess.run(['java', '-Dfile.encoding=UTF-8', '@' + str(arguments)], check=True, timeout=60)
    print('PASS current main/standard release source compilation and owned-state JUnit; Android framework construction is bypassed only in explicit host owner fixtures')


def run_aapt2_helper_tests():
    # 仅测试缓存工具选择与符号解析格式；不执行 AAPT2/Java/Gradle，也不读取真实构建产物。
    import unittest
    from unittest.mock import patch

    class Aapt2SelectionTest(unittest.TestCase):
        def setUp(self):
            temporary = tempfile.TemporaryDirectory(prefix='vscreen-aapt2-helper-')
            self.addCleanup(temporary.cleanup)
            root = Path(temporary.name)
            self.cache = root / 'cache'
            (root / 'build.gradle').write_text("plugins { id 'com.android.application' version '9.1.1' apply false }\n", encoding='utf-8')
            wrapper = root / 'gradle/wrapper/gradle-wrapper.properties'
            wrapper.parent.mkdir(parents=True)
            wrapper.write_text('distributionUrl=https\\://services.gradle.org/distributions/gradle-9.3.1-bin.zip\n', encoding='utf-8')
            self.root = root
            root_patch = patch.dict(globals(), ROOT=root)
            root_patch.start()
            self.addCleanup(root_patch.stop)

        def candidate(self, slot='one', classifier='linux', executable=None, agp='9.1.1', gradle='9.3.1', data=b'locked-aapt2'):
            if executable is None:
                executable = 'aapt2.exe' if classifier == 'windows' else 'aapt2'
            path = self.cache / ('caches/' + gradle + '/transforms/' + slot + '/transformed/aapt2-' + agp + '-123-' + classifier + '/' + executable)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            return path

        def test_platform_selection_ignores_other_platform_hashes_and_executables(self):
            linux = self.candidate(data=b'linux-aapt2')
            macos = self.candidate(classifier='osx', data=b'osx-aapt2')
            windows = self.candidate(classifier='windows', data=b'windows-aapt2')
            self.candidate(slot='wrong-executable', executable='aapt2.exe', data=b'wrong-linux-aapt2')
            self.candidate(slot='wrong-executable', classifier='osx', executable='aapt2.exe', data=b'wrong-osx-aapt2')
            self.candidate(slot='wrong-executable', classifier='windows', executable='aapt2', data=b'wrong-windows-aapt2')
            for platform, expected in [('linux', linux), ('darwin', macos), ('win32', windows)]:
                with self.subTest(platform=platform), patch.object(sys, 'platform', platform):
                    self.assertEqual(current_agp_aapt2(self.cache), expected)

        def test_linux_rejects_macos_only_cache(self):
            self.candidate(classifier='osx')
            with patch.object(sys, 'platform', 'linux'), self.assertRaisesRegex(RuntimeError, '^CURRENT_AGP_AAPT2_REQUIRED$'):
                current_agp_aapt2(self.cache)

        def test_macos_rejects_linux_only_cache(self):
            self.candidate()
            with patch.object(sys, 'platform', 'darwin'), self.assertRaisesRegex(RuntimeError, '^CURRENT_AGP_AAPT2_REQUIRED$'):
                current_agp_aapt2(self.cache)

        def test_unknown_platform_is_rejected_even_with_cached_tools(self):
            for classifier in ('linux', 'osx', 'windows'):
                self.candidate(classifier=classifier)
            with patch.object(sys, 'platform', 'freebsd14'), self.assertRaisesRegex(RuntimeError, '^UNSUPPORTED_AAPT2_PLATFORM:freebsd14$'):
                current_agp_aapt2(self.cache)

        def test_identical_duplicates_are_allowed(self):
            expected = self.candidate()
            self.candidate(slot='two')
            with patch.object(sys, 'platform', 'linux'):
                self.assertEqual(current_agp_aapt2(self.cache), expected)

        def test_conflicting_hashes_are_rejected(self):
            self.candidate()
            self.candidate(slot='two', data=b'different-aapt2')
            with patch.object(sys, 'platform', 'linux'), self.assertRaisesRegex(RuntimeError, '^CURRENT_AGP_AAPT2_REQUIRED$'):
                current_agp_aapt2(self.cache)

        def test_no_candidate_is_rejected(self):
            with patch.object(sys, 'platform', 'linux'), self.assertRaisesRegex(RuntimeError, '^CURRENT_AGP_AAPT2_REQUIRED$'):
                current_agp_aapt2(self.cache)

        def test_directory_is_not_a_candidate(self):
            path = self.cache / 'caches/9.3.1/transforms/one/transformed/aapt2-9.1.1-123-linux/aapt2'
            path.mkdir(parents=True)
            with patch.object(sys, 'platform', 'linux'), self.assertRaisesRegex(RuntimeError, '^CURRENT_AGP_AAPT2_REQUIRED$'):
                current_agp_aapt2(self.cache)

        def test_wrong_versions_and_other_platform_are_not_fallbacks(self):
            self.candidate(agp='9.0.0')
            self.candidate(gradle='9.2.0')
            self.candidate(classifier='windows')
            with patch.object(sys, 'platform', 'linux'), self.assertRaisesRegex(RuntimeError, '^CURRENT_AGP_AAPT2_REQUIRED$'):
                current_agp_aapt2(self.cache)

        def test_versions_follow_project_and_wrapper(self):
            (self.root / 'build.gradle').write_text("plugins { id 'com.android.application' version '9.2.0' apply false }\n", encoding='utf-8')
            (self.root / 'gradle/wrapper/gradle-wrapper.properties').write_text(
                'distributionUrl=https\\://services.gradle.org/distributions/gradle-9.4.0-all.zip\n', encoding='utf-8')
            self.candidate()
            expected = self.candidate(agp='9.2.0', gradle='9.4.0')
            with patch.object(sys, 'platform', 'linux'):
                self.assertEqual(current_agp_aapt2(self.cache), expected)

        def test_missing_agp_version_is_rejected(self):
            (self.root / 'build.gradle').write_text('plugins {}\n', encoding='utf-8')
            with self.assertRaisesRegex(RuntimeError, '^CURRENT_AAPT2_VERSION_REQUIRED$'):
                current_agp_aapt2(self.cache)

        def test_missing_gradle_version_is_rejected(self):
            (self.root / 'gradle/wrapper/gradle-wrapper.properties').write_text('distributionUrl=unknown\n', encoding='utf-8')
            with self.assertRaisesRegex(RuntimeError, '^CURRENT_GRADLE_VERSION_REQUIRED$'):
                current_agp_aapt2(self.cache)

    class ResourceSymbolTest(unittest.TestCase):
        def test_real_styleable_forms_produce_one_symbol_set(self):
            # 2026-11 CI 43185258663: AAPT2 的新文本符号格式同时带可见性和 int[] styleable。
            aapt = '\n'.join((
                'default int attr flowGapHorizontal',
                'public int[] styleable PluginFlowRow',
                'default int styleable PluginFlowRow_flowGapHorizontal',
            ))
            definitions = '\n'.join((
                'R_DEF: Internal format may change without notice',
                'local',
                'attr? flowGapHorizontal',
                'styleable PluginFlowRow flowGapHorizontal',
            ))
            compile_txt = '\n'.join((
                'int attr flowGapHorizontal 0x0',
                'int[] styleable PluginFlowRow { 0x0 }',
                'int styleable PluginFlowRow_flowGapHorizontal 0',
            ))
            javap_out = '\n'.join((
                'Compiled from "R.java"',
                'public final class com.deepseekharness.app.R$attr {',
                '  public static int flowGapHorizontal = 0;',
                '  public static {};',
                '}',
                'public final class com.deepseekharness.app.R$styleable {',
                '  public static int[] PluginFlowRow;',
                '  public static int PluginFlowRow_flowGapHorizontal = 0;',
                '  public static {};',
                '}',
            ))
            expected = {
                ('attr', 'flowGapHorizontal', 'scalar'),
                ('styleable', 'PluginFlowRow', 'array'),
                ('styleable', 'PluginFlowRow_flowGapHorizontal', 'scalar'),
            }
            definition_structure, compile_structure, class_structure = {}, {}, {}
            self.assertEqual(resource_symbols(aapt, 'aapt'), expected)
            self.assertEqual(resource_symbols(definitions, 'definitions', definition_structure), expected)
            self.assertEqual(resource_symbols(compile_txt, 'compile', compile_structure), expected)
            self.assertEqual(javap_symbols(javap_out, class_structure), expected)
            require_styleable_structure(definition_structure, compile_structure, class_structure)

        def test_missing_styleable_array_is_not_ignored(self):
            fresh = {
                ('attr', 'flowGapHorizontal', 'scalar'),
                ('styleable', 'PluginFlowRow', 'array'),
                ('styleable', 'PluginFlowRow_flowGapHorizontal', 'scalar'),
            }
            without_array = {item for item in fresh if item != ('styleable', 'PluginFlowRow', 'array')}
            with self.assertRaisesRegex(RuntimeError, '^STALE_STANDARD_RELEASE_RESOURCE_SYMBOLS:R-def'):
                require_same_symbols(without_array, fresh, 'R-def')

        def test_empty_styleable_keeps_the_array(self):
            self.assertEqual(resource_symbols('public int[] styleable Empty', 'aapt'),
                             {('styleable', 'Empty', 'array')})
            self.assertEqual(resource_symbols('styleable Empty', 'definitions'),
                             {('styleable', 'Empty', 'array')})
            self.assertEqual(resource_symbols('int[] styleable Empty { }', 'compile'),
                             {('styleable', 'Empty', 'array')})
            self.assertEqual(resource_symbols('int[] styleable Empty {}', 'compile'),
                             {('styleable', 'Empty', 'array')})
            self.assertEqual(javap_symbols('\n'.join((
                'public final class com.deepseekharness.app.R$styleable {',
                '  public static int[] Empty;',
                '  public static {};',
                '}',
            ))), {('styleable', 'Empty', 'array')})

        def check_structure(self, definitions, compile_txt, javap_out):
            definition_structure, compile_structure, class_structure = {}, {}, {}
            expected = resource_symbols(definitions, 'definitions', definition_structure)
            require_same_symbols(resource_symbols(compile_txt, 'compile', compile_structure), expected, 'R.txt')
            require_same_symbols(javap_symbols(javap_out, class_structure), expected, 'R.jar')
            require_styleable_structure(definition_structure, compile_structure, class_structure)
            return definition_structure, compile_structure, class_structure

        def styleable_fixture(self):
            # 实际 AGP PluginFlowRow 三子项；另外保留合法的同名 scalar/array。
            definitions = ('attr PluginFlowRow\nstyleable PluginFlowRow '
                           'flowGapHorizontal flowGapVertical flowStretchWrapped')
            compile_txt = ('int attr PluginFlowRow 0x0\n'
                           'int[] styleable PluginFlowRow { 0x0, 0x0, 0x0 }\n'
                           'int styleable PluginFlowRow_flowGapHorizontal 0\n'
                           'int styleable PluginFlowRow_flowGapVertical 1\n'
                           'int styleable PluginFlowRow_flowStretchWrapped 2')
            javap_out = ('public final class com.deepseekharness.app.R$attr {\n'
                         '  public static int PluginFlowRow = 0;\n}\n'
                         'public final class com.deepseekharness.app.R$styleable {\n'
                         '  public static int[] PluginFlowRow;\n'
                         '  public static int PluginFlowRow_flowGapHorizontal = 0;\n'
                         '  public static int PluginFlowRow_flowGapVertical = 1;\n'
                         '  public static int PluginFlowRow_flowStretchWrapped = 2;\n'
                         '  public static {};\n}')
            return definitions, compile_txt, javap_out

        def test_same_name_real_styleable_structure_positive_control(self):
            definitions, compile_txt, javap_out = self.styleable_fixture()
            definition_structure, compile_structure, class_structure = self.check_structure(
                definitions, compile_txt, javap_out)
            self.assertEqual(definition_structure['children']['PluginFlowRow'], (
                'PluginFlowRow_flowGapHorizontal', 'PluginFlowRow_flowGapVertical',
                'PluginFlowRow_flowStretchWrapped'))
            self.assertEqual(compile_structure['lengths'], {'PluginFlowRow': 3})
            self.assertEqual(compile_structure['indices'], class_structure['indices'])

        def test_short_array_out_of_bounds_duplicate_and_missing_children_rejected(self):
            definitions, compile_txt, javap_out = self.styleable_fixture()
            invalid = (
                compile_txt.replace('{ 0x0, 0x0, 0x0 }', '{}'),
                compile_txt.replace('{ 0x0, 0x0, 0x0 }', '{ 0x0, 0x0 }'),
                compile_txt.replace('flowGapVertical 1', 'flowGapVertical 99'),
                compile_txt.replace('flowGapVertical 1', 'flowGapVertical 0'),
                compile_txt.replace('int styleable PluginFlowRow_flowGapVertical 1\n', ''),
            )
            for text in invalid:
                with self.subTest(text=text), self.assertRaisesRegex(
                        RuntimeError, '^STALE_STANDARD_RELEASE_(STYLEABLE_STRUCTURE|RESOURCE_SYMBOLS)'):
                    self.check_structure(definitions, text, javap_out)

        def test_missing_rdef_or_jar_child_rejected(self):
            definitions, compile_txt, javap_out = self.styleable_fixture()
            invalid = (
                (definitions.replace(' flowGapVertical', ''), compile_txt, javap_out),
                (definitions, compile_txt, javap_out.replace(
                    '  public static int PluginFlowRow_flowGapVertical = 1;\n', '')),
            )
            for texts in invalid:
                with self.subTest(texts=texts), self.assertRaisesRegex(
                        RuntimeError, '^STALE_STANDARD_RELEASE_(STYLEABLE_STRUCTURE|RESOURCE_SYMBOLS)'):
                    self.check_structure(*texts)

        def test_empty_styleable_structure_positive_control(self):
            self.check_structure('styleable Empty', 'int[] styleable Empty {}',
                                 'public final class com.deepseekharness.app.R$styleable {\n'
                                 '  public static int[] Empty;\n}')

        def test_rtxt_reordered_indices_cannot_pass_an_unchanged_jar(self):
            definitions, compile_txt, javap_out = self.styleable_fixture()
            swapped = compile_txt.replace('flowGapHorizontal 0', 'flowGapHorizontal 1').replace(
                'flowGapVertical 1', 'flowGapVertical 0')
            with self.assertRaisesRegex(RuntimeError, '^STALE_STANDARD_RELEASE_STYLEABLE_STRUCTURE:R.txt/R.jar'):
                self.check_structure(definitions, swapped, javap_out)

        def test_rdef_is_explicit_membership_not_a_prefix_or_index_guess(self):
            # Android attrs 的实际 compile R 顺序不能从 R-def 文本位置推断。
            definitions = 'styleable View android:theme android:focusable theme\nstyleable View_extra theme'
            compile_txt = ('int[] styleable View { 0x0, 0x0, 0x0 }\n'
                           'int styleable View_android_focusable 0\n'
                           'int styleable View_android_theme 1\n'
                           'int styleable View_theme 2\n'
                           'int[] styleable View_extra { 0x0 }\n'
                           'int styleable View_extra_theme 0')
            javap_out = ('public final class com.deepseekharness.app.R$styleable {\n'
                         '  public static int[] View;\n'
                         '  public static int View_android_focusable = 0;\n'
                         '  public static int View_android_theme = 1;\n'
                         '  public static int View_theme = 2;\n'
                         '  public static int[] View_extra;\n'
                         '  public static int View_extra_theme = 0;\n}')
            self.check_structure(definitions, compile_txt, javap_out)

        def test_unknown_symbol_formats_are_rejected(self):
            unsupported = (
                'public long attr flowGapHorizontal',
                'public int attr flowGapHorizontal extra',
                'public int[] id flowGapHorizontal',
            )
            for text in unsupported:
                with self.subTest(kind='aapt', text=text), self.assertRaisesRegex(
                        RuntimeError, '^UNSUPPORTED_CURRENT_RESOURCE_SYMBOL'):
                    resource_symbols(text, 'aapt')
            for text in ('styleable', 'attr? flowGapHorizontal extra', 'attr flowGapHorizontal extra',
                         'future flowGapHorizontal', 'local ignored', 'R_DEF: unknown format',
                         'styleable Row gap gap', 'styleable Row gap\nstyleable Row other'):
                with self.subTest(kind='definitions', text=text), self.assertRaisesRegex(
                        RuntimeError, '^UNSUPPORTED_CURRENT_RESOURCE_SYMBOL'):
                    resource_symbols(text, 'definitions')
            malformed_r = (
                'int attr flowGapHorizontal 0x7f010001',
                'int styleable PluginFlowRow_flowGapHorizontal 0x0',
                'int[] styleable PluginFlowRow',
                'int[] string PluginFlowRow { 0x0 }',
                'int[] styleable PluginFlowRow { 0x0, 0x7f010001 }',
                'int[] styleable PluginFlowRow { 0x0, }',
                'int[] styleable PluginFlowRow { , 0x0 }',
                'int[] styleable PluginFlowRow { 0x0,, 0x0 }',
                'int[] styleable PluginFlowRow { invalid }',
                'int attr gap invalid',
                'int styleable Row_gap 0\nint styleable Row_gap 1',
            )
            for text in malformed_r:
                with self.subTest(kind='compile', text=text), self.assertRaisesRegex(
                        RuntimeError, '^CURRENT_COMPILE_R_SYMBOL_FORMAT'):
                    resource_symbols(text, 'compile')
            malformed_jar = (
                'public final class com.deepseekharness.app.R$attr {\n  public static int x = 1;\n}',
                'public final class com.deepseekharness.app.R$attr {\n  public static int[] x;\n}',
                'public final class com.deepseekharness.app.R$attr {\n  public static long x = 0;\n}',
                'public final class com.deepseekharness.app.R$styleable {\n  public static int x = 0x0;\n}',
                'public final class com.deepseekharness.app.R$styleable {\n  protected static int x = 0;\n}',
                'public final class com.deepseekharness.app.R$styleable {\n  public static int x = 0;',
                'public final class com.deepseekharness.app.R$styleable {\n  public static int x = 0;\n  public static int x = 1;\n}',
                'unknown format',
            )
            for text in malformed_jar:
                with self.subTest(kind='javap', text=text), self.assertRaisesRegex(
                        RuntimeError, '^CURRENT_COMPILE_R_CLASS_FORMAT'):
                    javap_symbols(text)

    suite = unittest.TestSuite((
        unittest.defaultTestLoader.loadTestsFromTestCase(Aapt2SelectionTest),
        unittest.defaultTestLoader.loadTestsFromTestCase(ResourceSymbolTest),
    ))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk")
    parser.add_argument("--sdk")
    parser.add_argument("--gradle-home")
    parser.add_argument('--self-test', action='store_true', help='Test AAPT2 cache selection and resource-symbol format parsing only; no Gradle, Java, resource compilation or real artifacts')
    parser.add_argument('--resource-gate-only', action='store_true', help='Verify current resource symbols without compiling/running the HTTP fixture')
    args = parser.parse_args()
    if args.self_test:
        return run_aapt2_helper_tests()
    for name in ('jdk', 'sdk', 'gradle_home'):
        if not getattr(args, name):
            parser.error('the following argument is required: --' + name.replace('_', '-'))
    jdk, sdk, cache = map(lambda value: Path(value).resolve(), [args.jdk, args.sdk, args.gradle_home])
    os.environ.update(JAVA_HOME=str(jdk), ANDROID_SDK_ROOT=str(sdk), GRADLE_USER_HOME=str(cache),
                      LANG="C.UTF-8", LC_CTYPE="C.UTF-8", PYTHONIOENCODING="utf-8",
                      DSHA_ONLY="CoalescingPollerTest,VirtualScreenRequestFenceTest,VirtualScreenManagerRevocationTest,TouchGestureDispatchTest,VirtualScreenPolicyTest,VscreenBridgeRequestTest")
    os.environ["PATH"] = str(jdk / "bin") + os.pathsep + os.environ.get("PATH", "")
    spec = importlib.util.spec_from_file_location("dsha_current_java_tests", ROOT / "tools/run-unit-tests.py")
    current = importlib.util.module_from_spec(spec); spec.loader.exec_module(current)
    current.ensure_utf8_locale()
    destination = ROOT / "app/build/vscreen-test"; destination.mkdir(parents=True, exist_ok=True)
    if args.resource_gate_only:
        with tempfile.TemporaryDirectory(prefix='vscreen-resources-', dir=destination) as folder:
            _, proof = verify_release_resource_symbols(Path(folder), cache, jdk)
            (destination / 'release-resource-symbol-proof.json').write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')
        return 0
    lock = json.loads((ROOT / "tools/vscreen-test-dependencies.json").read_text(encoding="utf-8"))
    jar = destination / lock["artifact"]
    if jar.is_file():
        data = jar.read_bytes()
    else:
        with urllib.request.urlopen(lock["url"], timeout=40) as response:
            data = response.read(lock["size"] + 1)
    if (len(data) != lock["size"] or hashlib.sha256(data).hexdigest() != lock["sha256"]
            or hashlib.sha1(data).hexdigest() != lock["publishedSha1"]):
        raise RuntimeError("VSCREEN_HOST_JSON_DIGEST_MISMATCH")
    jar.write_bytes(data)
    android = current.android_jar()
    junit = current.find_jar(cache, "junit-4.13.2", "junit-4.13")
    hamcrest = current.find_jar(cache, "hamcrest-core-1.3", "hamcrest-core")
    with tempfile.TemporaryDirectory(prefix="vscreen-current-", dir=destination) as folder:
        work = Path(folder)
        run_release_source_tests(current, work, cache, android, junit, hamcrest, jar)
        fixture = ROOT / "tools/fixtures/vscreen-manager/ManagerHttpFixture.java"
        classes = work / "http-fixture"
        current._javac([str(fixture)], classes, [str(jar), str(work / "main"), str(android)])
        command = [str(jdk / "bin" / ("java.exe" if os.name == "nt" else "java")),
                   "-Dfile.encoding=UTF-8", "-cp",
                   os.pathsep.join(map(str, [classes, jar, work / "main", android])),
                   "com.deepseekharness.app.vscreen.ManagerHttpFixture"]
        subprocess.run(command, cwd=ROOT, check=True, timeout=60)
    print("PASS current source compilation, selected JUnit and actual HTTP manager fixture; no Android platform acceptance claimed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
