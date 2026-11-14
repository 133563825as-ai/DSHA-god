#!/usr/bin/env python3
"""固定输入接线合成负控；不下载、不运行Gradle、不执行归档。"""
import contextlib
import hashlib
import io
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('validation_inputs', ROOT/'tools/prepare-validation-inputs.py')
mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)


class ValidationInputsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root/mod.PREFIX).mkdir(parents=True)
        self.recipes = {name: {'source': 'current-sha'} for name in mod.RECEIPTS}
        self.saved = {}
        for name in mod.RECEIPTS:
            value = {'inputs': self.recipes[name], 'archive_sha256': hashlib.sha256(b'binary').hexdigest(), 'version': '0.2.2-rc.1'}
            self.saved[name] = (json.dumps(value, indent=2) + '\n').encode()
            (self.root/mod.PREFIX/name).write_bytes(self.saved[name])

    def restore(self):
        for name in mod.RECEIPTS:
            (self.root/mod.PREFIX/name).write_text('{"old":"bundle metadata"}')
            (self.root/mod.PREFIX/name.replace('.inputs.json', '.bin')).write_bytes(b'binary')

    def test_old_bundle_receipt_never_replaces_current_head_bytes(self):
        result = mod.preserve_restore(self.root, self.saved, self.recipes, '0.2.2-rc.1', self.restore)
        for name in mod.RECEIPTS:
            self.assertEqual(self.saved[name], (self.root/mod.PREFIX/name).read_bytes())
            self.assertTrue(result[name]['restoredHeadBytes'])

    def test_wrong_current_recipe_rejects_before_restore(self):
        self.recipes[mod.RECEIPTS[0]] = {'source': 'other'}
        with patch.object(self, 'restore') as restore, self.assertRaisesRegex(ValueError, 'RECIPE'):
            mod.preserve_restore(self.root, self.saved, self.recipes, '0.2.2-rc.1', restore)
        restore.assert_not_called()

    def test_wrong_binary_is_not_hidden_by_head_receipt(self):
        def restore():
            self.restore()
            (self.root/mod.PREFIX/'dsh-runtime.bin').write_bytes(b'bad')
        with self.assertRaisesRegex(ValueError, 'ARCHIVE'):
            mod.preserve_restore(self.root, self.saved, self.recipes, '0.2.2-rc.1', restore)
        self.assertNotEqual(self.saved[mod.RECEIPTS[0]], (self.root/mod.PREFIX/mod.RECEIPTS[0]).read_bytes())

    def test_restore_failure_does_not_rewrite_receipts(self):
        def restore():
            self.restore(); raise ValueError('pin mismatch')
        with self.assertRaisesRegex(ValueError, 'pin mismatch'):
            mod.preserve_restore(self.root, self.saved, self.recipes, '0.2.2-rc.1', restore)
        self.assertNotEqual(self.saved[mod.RECEIPTS[0]], (self.root/mod.PREFIX/mod.RECEIPTS[0]).read_bytes())

    def test_wrong_version_rejects_before_restore(self):
        with patch.object(self, 'restore') as restore, self.assertRaisesRegex(ValueError, 'VERSION'):
            mod.preserve_restore(self.root, self.saved, self.recipes, 'wrong', restore)
        restore.assert_not_called()

    def test_symlink_binary_rejects(self):
        def restore():
            self.restore(); path = self.root/mod.PREFIX/'dsh-runtime.bin'
            path.unlink(); path.symlink_to(self.root/mod.PREFIX/'ubuntu-tools.bin')
        with self.assertRaisesRegex(ValueError, 'ARCHIVE'):
            mod.preserve_restore(self.root, self.saved, self.recipes, '0.2.2-rc.1', restore)

    def test_dirty_or_untracked_head_is_not_authority(self):
        with patch.object(mod.subprocess, 'check_output', return_value=b'not-current'):
            with self.assertRaisesRegex(ValueError, 'NOT_HEAD'): mod.head_receipts(self.root)

    def test_workflow_is_push_scoped_test_only_and_keeps_gate_order(self):
        import re
        text = (ROOT/'.github/workflows/rebirth-validation.yml').read_text()
        self.assertIn("branches: ['validate/rebirth-rc1-20261114']", text)
        self.assertIn("github.event_name == 'push'", text)
        self.assertIn("github.ref == 'refs/heads/validate/rebirth-rc1-20261114'", text)
        self.assertIn('contents: read', text)
        self.assertIn('persist-credentials: false', text)
        self.assertNotIn('workflow_dispatch:', text)
        self.assertNotIn('secrets:', text.lower())
        self.assertNotRegex(text, r':app:(?:assemble|package|bundle|publish|install|clean|sign)')
        self.assertNotIn('--stage package', text)
        self.assertNotIn('sourceOnlyTests=true', text)
        self.assertNotIn('--allow-stale-archive', text)
        self.assertNotIn('rebuild_runtime:', text)
        positions = [text.index(value) for value in ['--stage fast', ':app:testStandardDebugUnitTest', ':app:lintStandardRelease', ':app:mapStandardReleaseSourceSetPaths', '--stage runtime', '--stage software']]
        self.assertEqual(sorted(positions), positions)
        self.assertIn('--mode full --require-fixtures', text)
        self.assertIn("rebirthHostFixtures 'org.bouncycastle:bcprov-jdk15to18:1.85.2'", text)
        self.assertIn("rebirthHostFixtures 'org.yaml:snakeyaml:2.4'", text)
        self.assertIn('GRADLE_USER_HOME:', text)
        # 与现有source-hygiene完全相同的扫描表达式，不能让内嵌诊断尾词误成action。
        actions = re.findall(r'uses:\s*([^\s#]+)', text)
        self.assertEqual(4, len(actions))
        for selected in actions:
            self.assertTrue(re.fullmatch(r'\./\.github/(?:actions/[\w-]+|workflows/_node-[\w-]+\.yml)', selected)
                            or re.fullmatch(r'[\w.-]+/[\w.-]+@[0-9a-f]{40}', selected), selected)
        artifact = text.split('name: Upload test evidence only including failures', 1)[1]
        self.assertNotRegex(artifact, r'(?im)^\s+[^#\n]*(?:\.apk|\.bin|test-runtimes|/home|/profile)')

    def test_diagnostic_steps_do_not_continue_business_after_failure(self):
        text = (ROOT/'.github/workflows/rebirth-validation.yml').read_text()
        self.assertNotIn('continue-on-error', text)
        self.assertNotIn('BASH_COMMAND', text)
        self.assertNotIn('set -x', text)
        for value in ['l1_source', 'l1_fast', 'l2_unit', 'l2_xml', 'l2_lint', 'l2_map', 'l3_runtime', 'l3_software']:
            self.assertIn('id: ' + value, text)
        self.assertEqual(2, text.count("if: failure() && steps."))
        self.assertEqual(1, text.count('if: always()'))
        for block in text.split('      - name: ')[1:]:
            if 'if: always()' in block:
                self.assertTrue(block.startswith('Upload test evidence'))
        # 原有全部阶段命令仍按同一先后顺序，仅诊断step可越过failure运行。
        order = ['L1 identity and source checks', 'L1 complete fast gates', 'L2 complete unit tests',
                 'L2 complete unit XML verification', 'L2 Lint', 'L2 resource source map',
                 'L3 complete runtime gates', 'L3 software gates']
        self.assertEqual(sorted(text.index(x) for x in order), [text.index(x) for x in order])

    def test_manifest_annotations_only_emit_declared_safe_metadata(self):
        text = (ROOT/'.github/workflows/rebirth-validation.yml').read_text()
        for stage, title in [('fast', 'L1 safe failure summary'), ('runtime', 'L3 safe failure summary')]:
            block = text.split('      - name: ' + title, 1)[1].split('      - name: ', 1)[0]
            code = block.split("python3 - <<'PY'\n", 1)[1].rsplit('          PY', 1)[0]
            import textwrap
            code = textwrap.dedent(code)
            declared = {'tests': [{'path': 'tools/test-safe.py', 'stage': stage},
                                   {'path': 'tools/bad%0A::error.py', 'stage': stage}]}
            report = {'tests': [
                {'path': 'tools/test-safe.py', 'status': 'FAILED', 'exitCode': 1, 'error': 'PRIVATE_SECRET', 'log': 'PRIVATE_LOG'},
                {'path': 'tools/test-safe.py', 'status': 'TIMEOUT', 'exitCode': 'PRIVATE_SECRET'},
                {'path': 'tools/not-declared.py', 'status': 'FAILED', 'exitCode': 1},
                {'path': 'tools/bad%0A::error.py', 'status': 'FAILED', 'exitCode': 1},
                {'path': 'tools/test-safe.py', 'status': 'FAILED\n::error::PRIVATE_SECRET', 'exitCode': 1},
                {'path': ['bad'], 'status': ['bad'], 'exitCode': True},
            ]}
            def read(path, *args, **kwargs):
                return json.dumps(declared if str(path) == 'tools/host-tests.manifest.json' else report)
            output = io.StringIO()
            with patch.object(Path, 'read_text', read), contextlib.redirect_stdout(output):
                exec(compile(code, '<workflow-safe-annotation>', 'exec'), {})
            value = output.getvalue()
            self.assertIn('path=test-safe.py status=FAILED exitCode=1', value)
            self.assertIn('status=TIMEOUT exitCode=unknown', value)
            self.assertIn('count=2 passed=0 failed=2', value)
            for forbidden in ['PRIVATE_SECRET', 'PRIVATE_LOG', 'not-declared', '%', 'bad%0A']:
                self.assertNotIn(forbidden, value)

    def test_missing_current_head_receipt_never_falls_back_to_bundle(self):
        with patch.object(mod.subprocess, 'check_output', side_effect=RuntimeError('HEAD missing')):
            with self.assertRaisesRegex(RuntimeError, 'HEAD missing'): mod.head_receipts(self.root)


if __name__ == '__main__': unittest.main(verbosity=2)
