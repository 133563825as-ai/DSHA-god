#!/usr/bin/env python3
"""固定宿主 jar 的离线正负控；网络只用 mock，不下载真实 jar。"""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


helper = load('sprout_host_jars', 'prepare-sprout-host-jars.py')
browser = load('browser_origin_server', 'test-browser-origin-server.py')


class HostJarTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.body = b'controlled jar bytes'
        self.jar = helper.Jar('framework', self.root / 'jars/framework.jar',
                              helper.FRAMEWORK_URL, hashlib.sha256(self.body).hexdigest(), 64)

    def test_missing_check_does_not_download_or_create_directory(self):
        with mock.patch.object(helper, 'urlopen') as network:
            with self.assertRaisesRegex(ValueError, '缺少.*--prepare'):
                helper.check_jar(self.jar)
        network.assert_not_called()
        self.assertFalse(self.jar.path.parent.exists())

    def test_existing_valid_check_and_prepare_are_offline(self):
        self.jar.path.parent.mkdir()
        self.jar.path.write_bytes(self.body)
        with mock.patch.object(helper, 'urlopen') as network:
            self.assertEqual(helper.check_jar(self.jar), self.jar.path)
            self.assertEqual(helper.prepare_jar(self.jar), self.jar.path)
        network.assert_not_called()
        self.assertEqual(self.jar.path.read_bytes(), self.body)

    def test_existing_wrong_bytes_refused_without_overwrite(self):
        self.jar.path.parent.mkdir()
        self.jar.path.write_bytes(b'unknown original')
        with mock.patch.object(helper, 'urlopen') as network:
            for action in (helper.check_jar, helper.prepare_jar):
                with self.assertRaisesRegex(ValueError, 'SHA-256 不匹配'):
                    action(self.jar)
        network.assert_not_called()
        self.assertEqual(self.jar.path.read_bytes(), b'unknown original')

    def test_explicit_download_uses_fixed_url_and_verifies_bytes(self):
        with mock.patch.object(helper, 'urlopen', return_value=io.BytesIO(self.body)) as network:
            self.assertEqual(helper.prepare_jar(self.jar), self.jar.path)
        network.assert_called_once_with(helper.FRAMEWORK_URL, timeout=60)
        self.assertEqual(self.jar.path.read_bytes(), self.body)
        self.assertEqual(list(self.jar.path.parent.iterdir()), [self.jar.path])

    def test_download_bad_hash_never_publishes_or_deletes_unknown_partial(self):
        self.jar.path.parent.mkdir()
        partial = self.jar.path.with_suffix('.jar.partial')
        partial.write_bytes(b'unknown partial')
        with mock.patch.object(helper, 'urlopen', return_value=io.BytesIO(b'wrong download')):
            with self.assertRaisesRegex(ValueError, '下载 SHA-256 不匹配'):
                helper.prepare_jar(self.jar)
        self.assertFalse(self.jar.path.exists())
        self.assertEqual(partial.read_bytes(), b'unknown partial')
        self.assertEqual(list(self.jar.path.parent.iterdir()), [partial])

    def test_download_stream_limit_without_content_length(self):
        with mock.patch.object(helper, 'urlopen', return_value=io.BytesIO(b'x' * 65)):
            with self.assertRaisesRegex(ValueError, '大小限额 64'):
                helper.prepare_jar(self.jar)
        self.assertFalse(self.jar.path.exists())

    def test_download_does_not_overwrite_file_created_during_network(self):
        def response(*args, **kwargs):
            self.jar.path.write_bytes(b'concurrent original')
            return io.BytesIO(self.body)
        with mock.patch.object(helper, 'urlopen', side_effect=response):
            with self.assertRaises(FileExistsError):
                helper.prepare_jar(self.jar)
        self.assertEqual(self.jar.path.read_bytes(), b'concurrent original')

    def test_existing_symlink_refused(self):
        target = self.root / 'original.jar'
        target.write_bytes(self.body)
        self.jar.path.parent.mkdir()
        self.jar.path.symlink_to(target)
        with mock.patch.object(helper, 'urlopen') as network:
            with self.assertRaisesRegex(ValueError, '符号链接'):
                helper.prepare_jar(self.jar)
        network.assert_not_called()
        self.assertEqual(target.read_bytes(), self.body)

    def test_specs_read_snakeyaml_sha_from_lock_not_network(self):
        (self.root / 'tools').mkdir()
        lock_path = self.root / 'tools/backup-dependencies.lock.json'
        lock = json.loads((ROOT / 'tools/backup-dependencies.lock.json').read_text(encoding='utf-8'))
        entry = next(item for item in lock['dependencies'] if item['name'] == 'snakeyaml')
        self.assertEqual(helper.jar_specs()['snakeyaml'].sha256,
                         'ef779af5d29a9dde8cc70ce0341f5c6f7735e23edff9685ceaa9d35359b7bb7f')
        entry['sha256'] = 'a' * 64
        lock_path.write_text(json.dumps(lock), encoding='utf-8')
        with mock.patch.object(helper, 'urlopen') as network:
            specs = helper.jar_specs(self.root)
        network.assert_not_called()
        self.assertEqual(specs['snakeyaml'].sha256, 'a' * 64)
        self.assertEqual(specs['snakeyaml'].path, self.root / helper.SNAKEYAML_PATH)
        self.assertEqual(specs['snakeyaml'].url, helper.SNAKEYAML_URL)
        self.assertEqual(specs['framework'].path, self.root / helper.FRAMEWORK_PATH)
        self.assertEqual(specs['framework'].sha256,
                         '6be2218c6a53fe3c57bc22ebdc723edcb7270a8a6f187545708aa5c0ed813977')
        entry['artifact'] = 'https://example.invalid/untrusted.jar'
        lock_path.write_text(json.dumps(lock), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, '固定 URL'):
            helper.jar_specs(self.root)

    def test_check_cli_reports_missing_and_success_without_network(self):
        with mock.patch.object(helper, 'jar_specs', return_value={'framework': self.jar}), \
                mock.patch.object(helper, 'urlopen') as network:
            error = io.StringIO()
            with contextlib.redirect_stderr(error):
                self.assertEqual(helper.main(['--check', '--jar', 'framework']), 1)
            self.assertIn('缺少', error.getvalue())
            self.jar.path.parent.mkdir()
            self.jar.path.write_bytes(self.body)
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(helper.main(['--check', '--jar', 'framework']), 0)
            self.assertEqual(output.getvalue(), str(self.jar.path) + '\n')
        network.assert_not_called()

    def test_browser_explicit_bad_framework_fails_before_java(self):
        explicit = self.root / 'android.jar'
        explicit.write_bytes(b'not a real framework')
        api = {'jar_specs': lambda: {'framework': self.jar}, 'check_jar': helper.check_jar}
        with mock.patch.object(browser.runpy, 'run_path', return_value=api), \
                mock.patch.object(browser.sys, 'argv', ['test-browser-origin-server.py', '--framework', str(explicit)]), \
                mock.patch.object(browser.subprocess, 'run') as java, \
                contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as result:
                browser.main()
        self.assertEqual(result.exception.code, 2)
        java.assert_not_called()
        self.assertFalse((self.root / 'app').exists())

    def test_browser_default_and_explicit_framework_keep_java_assertion_runner(self):
        self.jar.path.parent.mkdir()
        self.jar.path.write_bytes(self.body)
        explicit = self.root / 'explicit.jar'
        explicit.write_bytes(self.body)
        api = {'jar_specs': lambda: {'framework': self.jar}, 'check_jar': helper.check_jar}
        for flags, path in (([], self.jar.path), (['--framework', str(explicit)], explicit)):
            with self.subTest(flags=flags), mock.patch.object(browser, 'ROOT', self.root), \
                    mock.patch.object(browser.runpy, 'run_path', return_value=api), \
                    mock.patch.object(browser.sys, 'argv', ['test-browser-origin-server.py', *flags]), \
                    mock.patch.object(browser.subprocess, 'run') as java:
                java.return_value.stdout = ''
                browser.main()
                self.assertEqual(java.call_count, 2)
                compile_args = java.call_args_list[0].args[0]
                self.assertIn(str(path), compile_args[compile_args.index('-cp') + 1])
                self.assertIn('com.deepseekharness.app.ui.BrowserOriginRestoreServerTest',
                              java.call_args_list[1].args[0])
                self.assertEqual(java.call_args_list[1].kwargs['timeout'], 90)
                self.assertEqual(java.return_value.check_returncode.call_count, 2)


if __name__ == '__main__':
    unittest.main()
