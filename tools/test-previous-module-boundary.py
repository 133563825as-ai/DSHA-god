#!/usr/bin/env python3
"""固定 build212 缺席集合的负控，以及不伪造历史 bytes 的 Java 缺席分支正控。"""
import importlib.util
import io
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('build193_boundary', ROOT / 'tools/test-build193-patch-chain.py')
boundary = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(boundary)
PRESENT = '@deepseek-ai/dsh-plugin-manager/lib/index.js'
ABSENT = sorted(boundary.ABSENT_IN_BUILD212)
MODULES = [PRESENT, *ABSENT]


def runtime_archive(modules):
    """仅用于成员数量负控的内存归档，不作为真实 build212 证据。"""
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode='w') as output:
        for module in modules:
            data = ('fixture:' + module).encode()
            member = tarfile.TarInfo('runtime/node_modules/' + module)
            member.size = len(data)
            output.addfile(member, io.BytesIO(data))
    buffer.seek(0)
    return tarfile.open(fileobj=buffer, mode='r:')


class PreviousModuleBoundaryTest(unittest.TestCase):
    def test_exact_absence_set(self):
        with runtime_archive([PRESENT]) as archive:
            modules = boundary.previous_modules(archive, MODULES)
        self.assertEqual(set(modules), set(MODULES))
        self.assertEqual(modules[PRESENT], ('fixture:' + PRESENT).encode())
        self.assertTrue(all(modules[module] is None for module in ABSENT))

    def test_non_allowlisted_missing_module_rejected(self):
        with runtime_archive([]) as archive:
            with self.assertRaisesRegex(AssertionError, 'UNEXPECTED_BUILD212_MODULE_ABSENCE'):
                boundary.previous_modules(archive, MODULES)

    def test_allowlisted_module_unexpectedly_present_rejected(self):
        for unexpected in ABSENT:
            with self.subTest(module=unexpected), runtime_archive([PRESENT, unexpected]) as archive:
                with self.assertRaisesRegex(AssertionError, 'BUILD212_ABSENCE_SET_MISMATCH'):
                    boundary.previous_modules(archive, MODULES)

    def test_absence_allowlist_must_be_fully_observed(self):
        with runtime_archive([PRESENT]) as archive:
            with self.assertRaisesRegex(AssertionError, 'BUILD212_ABSENCE_SET_MISMATCH'):
                boundary.previous_modules(archive, [PRESENT, *ABSENT[:-1]])

    def test_old_duplicate_rejected_even_for_allowlisted_module(self):
        for duplicate in [PRESENT, *ABSENT]:
            with self.subTest(module=duplicate), runtime_archive([PRESENT, duplicate, duplicate]) as archive:
                with self.assertRaisesRegex(AssertionError, 'APK_MODULE_UNIQUENESS'):
                    boundary.previous_modules(archive, MODULES)

    def test_current_and_new_archive_member_uniqueness_unchanged(self):
        # 当前/新归档共用严格提取器，绝不继承历史缺席豁免。
        for role in ['current', 'new']:
            for module in [PRESENT, *ABSENT]:
                for count in [0, 1, 2]:
                    with self.subTest(role=role, module=module, count=count), runtime_archive([module] * count) as archive:
                        if count == 1:
                            self.assertEqual(boundary.module_bytes(archive, module), ('fixture:' + module).encode())
                        else:
                            with self.assertRaisesRegex(AssertionError, 'APK_MODULE_UNIQUENESS'):
                                boundary.module_bytes(archive, module)

    def test_java_absent_branch_executes_complete_chain(self):
        jdk = Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None
        suffix = '.exe' if os.name == 'nt' else ''
        javac = str(jdk / 'bin' / ('javac' + suffix)) if jdk else shutil.which('javac')
        java = str(jdk / 'bin' / ('java' + suffix)) if jdk else shutil.which('java')
        self.assertTrue(javac and java, 'JDK17_REQUIRED')
        with tempfile.TemporaryDirectory(prefix='previous-module-boundary-') as temporary:
            root = Path(temporary)
            module = root / '0'
            module.mkdir()
            # 这是明确标注的合成分支 probe；真实历史 Java 回归另用原脚本和固定 APK。
            for name, value in {'canonical.js': 'alpha beta', 'expected.js': 'ALPHA BETA',
                                'apk.js': 'ALPHA BETA', 'name.txt': ABSENT[0],
                                'absent-in-build212.txt': ABSENT[0]}.items():
                (module / name).write_text(value, encoding='utf8')
            boundary.write_chain(module / 'chain', [
                ('first', [('alpha', 'ALPHA')], ['ALPHA']),
                ('second', [('beta', 'BETA')], ['BETA']),
            ])
            (root / 'modules.txt').write_text('0', encoding='utf8')
            fixture = root / 'Build193PatchChainFixture.java'
            fixture.write_text(boundary.JAVA, encoding='utf8')
            classes = root / 'classes'
            classes.mkdir()
            production = ROOT / 'app/src/main/java/com/deepseekharness/app/util'
            sources = [production / 'ManagedPatchChain.java', production / 'ExactTextPatch.java',
                       ROOT / 'tools/fixtures/documents-parent/com/deepseekharness/app/util/UiText.java', fixture]
            compiled = subprocess.run([javac, '-encoding', 'UTF-8', '--release', '17', '-d', str(classes),
                                       *map(str, sources)], capture_output=True, text=True, timeout=60)
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            ran = subprocess.run([java, '-cp', str(classes), 'Build193PatchChainFixture', str(root)],
                                 capture_output=True, text=True, timeout=60)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn('PASS_ABSENT_IN_BUILD212 empty-input-probe-not-historical-bytes', ran.stdout)
            self.assertIn('fresh/repeat/build212-boundary/staged-canonical/failclosed', ran.stdout)
            self.assertEqual((module / 'predecessor-executed-count.txt').read_text(), '0')
            self.assertFalse((module / 'old-192.js').exists())
            self.assertFalse((module / 'old-direct.js').exists())


if __name__ == '__main__':
    unittest.main()
