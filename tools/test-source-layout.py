#!/usr/bin/env python3
"""逻辑映射的真实文件迁移、语言根、冲突和当前单 app 等价性反例。"""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from source_layout import SourceLayout, MANIFEST, PACKAGE
from runtime_input_contract import launcher_paths, JAVA, load

ROOT = Path(__file__).resolve().parents[1]


def module(name, directory, kind='jvm'):
    return {'path': name, 'directory': directory, 'kind': kind,
            'sourceSets': ['main', 'test'], 'formatSourceSets': ['main', 'test'],
            'testSourceSets': ['test'], 'testTask': 'test'}


def tool(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), ROOT / 'tools' / (name + '.py'))
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


class SourceLayoutTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='a8-layout-中文 ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.spec = {'schema': 1, 'modules': [module(':app', 'app', 'android-application')]}
        self.put(MANIFEST, json.dumps(self.spec))

    def put(self, name, text='class Probe {}\n'):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')
        return path

    def save(self):
        self.put(MANIFEST, json.dumps(self.spec))

    def test_java_and_kotlin_roots_have_stable_identity_and_helpers_are_enumerated(self):
        self.put('app/src/main/java/' + PACKAGE + '/util/Policy.java')
        self.put('app/src/test/kotlin/' + PACKAGE + '/util/PolicyTest.kt')
        self.put('app/src/test/java/' + PACKAGE + '/util/CrashChild.java')
        entries = SourceLayout(self.root).source_files()
        self.assertEqual({'main:' + PACKAGE + '/util/Policy.java',
                          'test:' + PACKAGE + '/util/PolicyTest.kt',
                          'test:' + PACKAGE + '/util/CrashChild.java'}, {e.identity for e in entries})
        self.assertEqual({'.java', '.kt'}, {e.path.suffix for e in entries})

    def test_physical_module_extraction_and_language_root_move_preserve_launcher_keys(self):
        original = self.put('app/src/main/java/' + PACKAGE + '/runtime/Runner.kt')
        explicit = self.put('app/src/main/java/' + PACKAGE + '/util/Policy.java')
        spec = {'launcherTrees': [JAVA + '/runtime'], 'launcherSources': ['util/Policy.java']}
        before = launcher_paths(self.root, spec)
        self.spec['modules'].append(module(':core:policy', 'core/policy'))
        self.save()
        for source in (original, explicit):
            logical = source.relative_to(self.root / 'app/src/main/java')
            destination = self.root / 'core/policy/src/main/kotlin' / logical
            destination.parent.mkdir(parents=True, exist_ok=True)
            source.rename(destination)
        after = launcher_paths(self.root, spec)
        self.assertEqual(set(before), set(after))
        self.assertEqual({'runtime/Runner.kt', 'util/Policy.java'}, set(after))
        self.assertTrue(all('core/policy/src/main/kotlin' in str(p) for p in after.values()))
        self.assertEqual('core/policy/build/test-results/test', SourceLayout(self.root).test_tasks()[1]['reports'])

    def test_parallel_languages_and_cross_module_duplicates_fail(self):
        self.put('app/src/main/java/' + PACKAGE + '/Policy.java')
        duplicate = self.put('app/src/main/kotlin/' + PACKAGE + '/Policy.kt')
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_IDENTITY'):
            SourceLayout(self.root).source_files()
        duplicate.unlink()
        self.spec['modules'].append(module(':policy', 'policy'))
        self.save()
        self.put('policy/src/main/java/' + PACKAGE + '/Policy.java')
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_IDENTITY'):
            SourceLayout(self.root).source_files()

    def test_missing_identity_does_not_guess_another_source_set(self):
        self.put('app/src/test/java/' + PACKAGE + '/Policy.java')
        with self.assertRaisesRegex(ValueError, 'MISSING_OR_DUPLICATE'):
            SourceLayout(self.root).resolve_class('Policy')

    def test_linked_file_and_linked_ancestor_are_rejected(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'Probe.kt').write_text('class Probe')
        path = self.root / 'app/src/main/kotlin'
        path.parent.mkdir(parents=True)
        path.symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'SOURCE_LAYOUT_LINK'):
            SourceLayout(self.root).source_files()

    def test_unregistered_source_set_cannot_reduce_inventory(self):
        self.put('app/src/hidden/kotlin/' + PACKAGE + '/HiddenTest.kt')
        with self.assertRaisesRegex(ValueError, 'UNDECLARED_SOURCE_SET'):
            SourceLayout(self.root).source_files()

    def test_layout_rejects_traversal_duplicate_modules_and_overlap(self):
        for change in ('../outside', '/absolute', 'app//other', 'app\\other', 'app\nother'):
            spec = copy.deepcopy(self.spec)
            spec['modules'][0]['directory'] = change
            with self.subTest(path=change), self.assertRaises(ValueError):
                SourceLayout(self.root, spec)
        for extra in (module(':app', 'policy'), module(':policy', 'app/policy')):
            spec = copy.deepcopy(self.spec)
            spec['modules'].append(extra)
            with self.assertRaises(ValueError):
                SourceLayout(self.root, spec)

    def test_java_and_kotlin_format_inventory_use_same_moved_layout(self):
        java_tree, kotlin_tree = tool('format-java-tree'), tool('format-kotlin-tree')
        self.put(java_tree.TOOL_SOURCE)
        for source_set in ('main', 'test'):
            (self.root / 'app/src' / source_set / 'java' / PACKAGE).mkdir(parents=True)
        self.spec['modules'].append(module(':policy', 'policy'))
        self.save()
        java_name = 'policy/src/main/kotlin/' + PACKAGE + '/Policy.java'
        kotlin_name = 'policy/src/test/java/' + PACKAGE + '/PolicyTest.kt'
        self.put(java_name)
        self.put(kotlin_name)
        (self.root / 'policy/src/test/java' / PACKAGE).mkdir(parents=True, exist_ok=True)
        self.assertIn(java_name, java_tree.inventory(self.root))
        self.assertIn(kotlin_name, kotlin_tree.inventory(self.root))

    def test_moved_kotlin_still_hits_owner_boundary_and_formatter_ownership(self):
        from unittest.mock import patch
        self.spec['modules'].append(module(':policy', 'policy'))
        self.save()
        path = self.put('policy/src/main/kotlin/' + PACKAGE + '/runtime/Escape.kt',
                        'import com.deepseekharness.app.ui.MainActivity\nclass Escape\n')
        core, formatter = tool('test-core-boundaries'), tool('format-kotlin')
        with patch.object(core, 'ROOT', self.root), patch.object(core, 'LAYOUT', SourceLayout(self.root)):
            selected = core.source_files('runtime')
            self.assertEqual([path], selected)
            sources = [(core.logical_name(p), p.read_text()) for p in selected]
            self.assertTrue(core.layer_violations(sources, core.UI_LAYER))
        self.assertEqual(path, formatter.checked_path(path, root=self.root)[0])

    def test_launcher_kotlin_root_is_mapped_but_unknown_module_tree_is_rejected(self):
        self.put('app/src/main/kotlin/' + PACKAGE + '/runtime/Guard.kt')
        result = launcher_paths(self.root, {'launcherSources': [],
                                'launcherTrees': ['app/src/main/kotlin/' + PACKAGE + '/runtime']})
        self.assertEqual({'runtime/Guard.kt'}, set(result))
        with self.assertRaisesRegex(ValueError, 'RUNTIME_LAUNCHER_TREE'):
            launcher_paths(self.root, {'launcherSources': [], 'launcherTrees': [JAVA + '/missing']})
        with self.assertRaisesRegex(ValueError, 'SOURCE_ROOT_UNSUPPORTED'):
            launcher_paths(self.root, {'launcherSources': [],
                'launcherTrees': ['foreign/src/main/kotlin/' + PACKAGE + '/runtime']})


class CurrentAppEquivalenceTest(unittest.TestCase):
    def test_current_source_inventory_equals_real_git_source_list(self):
        layout = SourceLayout(ROOT)
        tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
        expected = {name for name in tracked if name.startswith('app/src/')
                    and Path(name).suffix in ('.java', '.kt', '.kts')
                    and len(Path(name).parts) > 4 and Path(name).parts[3] in ('java', 'kotlin')}
        self.assertEqual(expected, {entry.relative for entry in layout.source_files()})
        self.assertTrue(any(e.path.suffix == '.kt' and e.source_set == 'test'
                            for e in layout.source_files()))

    def test_current_launcher_logical_and_physical_members_equal_legacy_algorithm(self):
        spec, expected = load(ROOT), {}
        for name in spec['launcherSources']:
            expected[name] = ROOT / JAVA / name
        for name in spec['launcherTrees']:
            for path in (ROOT / name).rglob('*'):
                if name.startswith(JAVA + '/') and path.suffix in ('.java', '.kt'):
                    expected[path.relative_to(ROOT / JAVA).as_posix()] = path
                elif not name.startswith(JAVA + '/') and path.suffix == '.so':
                    expected[path.relative_to(ROOT).as_posix()] = path
        expected.update({'generator:' + n: ROOT / n for n in spec.get('generatorSources', [])})
        self.assertEqual(expected, launcher_paths(ROOT, spec))

    def test_current_format_lists_equal_legacy_scans_without_reducing_rules(self):
        java_tree, kotlin_tree = tool('format-java-tree'), tool('format-kotlin-tree')
        lock = json.loads(java_tree.LOCK.read_text())
        excluded = set(lock['protectedFiles'] + lock['generatedFiles'])
        expected_java = {java_tree.TOOL_SOURCE}
        expected_kotlin = set()
        for source_set in java_tree.SETS:
            for path in (ROOT / 'app/src' / source_set / java_tree.SOURCE).rglob('*.java'):
                name = path.relative_to(ROOT).as_posix()
                if not java_tree.EXCLUDED_PARTS.intersection(path.parts) and name not in excluded:
                    expected_java.add(name)
            for language in ('java', 'kotlin'):
                for path in (ROOT / 'app/src' / source_set / language).rglob('*'):
                    if path.suffix in ('.kt', '.kts') and not kotlin_tree.EXCLUDED_PARTS.intersection(path.parts):
                        expected_kotlin.add(path.relative_to(ROOT).as_posix())
        self.assertEqual(expected_java, set(java_tree.inventory(ROOT, lock['protectedFiles'], lock['generatedFiles'])))
        self.assertEqual(expected_kotlin, set(kotlin_tree.inventory(ROOT)))


if __name__ == '__main__':
    unittest.main(verbosity=2)
