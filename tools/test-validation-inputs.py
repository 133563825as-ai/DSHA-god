#!/usr/bin/env python3
"""固定输入接线合成负控；不下载、不运行Gradle、不执行归档。"""
import hashlib
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
        artifact = text.split('name: Upload test evidence only including failures', 1)[1]
        self.assertNotRegex(artifact, r'(?im)^\s+[^#\n]*(?:\.apk|\.bin|test-runtimes|/home|/profile)')

    def test_missing_current_head_receipt_never_falls_back_to_bundle(self):
        with patch.object(mod.subprocess, 'check_output', side_effect=RuntimeError('HEAD missing')):
            with self.assertRaisesRegex(RuntimeError, 'HEAD missing'): mod.head_receipts(self.root)


if __name__ == '__main__': unittest.main(verbosity=2)
