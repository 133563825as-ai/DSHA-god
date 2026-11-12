#!/usr/bin/env python3
"""小字节契约仅在测试内注入；不将合成数据宣称为真正 build212 APK。"""
import argparse
from contextlib import ExitStack, redirect_stderr
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fixture = load('previous_fixture_test_contract', 'previous-runtime-fixture.py')
runner = load('previous_fixture_test_runner', 'run-host-tests.py')
child = load('previous_fixture_test_child', 'test-build193-patch-chain.py')


class PreviousRuntimeFixtureTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='previous-runtime-contract-')
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.good_bytes = b'test-only previous generation fixture\x00\xff\n'
        self.good = self.directory / 'test-only-build212.apk'
        self.good.write_bytes(self.good_bytes)
        # 只在测试内替换摘要；生产入口没有契约参数、环境变量或 CLI override。
        self.small_contract = fixture.CONTRACT._replace(sha256=hashlib.sha256(self.good_bytes).hexdigest())
        environment = patch.dict(os.environ, {}, clear=True)
        environment.start()
        self.addCleanup(environment.stop)
        self.row = {'requires': ['previous_runtime_apk'], 'args': ['--old-apk', '{previousRuntimeApk}']}

    def values(self, selected):
        args = argparse.Namespace(node='node', toolchain_root=None, playwright=None,
                                  browsers_path=None, jdk=None, mobile_upstream='.',
                                  flavor='standard', previous_runtime_apk=selected)
        return runner.Inputs(args).values(self.directory, self.row)

    def contracts(self):
        stack = ExitStack()
        for module in (fixture, runner.previous_runtime_fixture, child.previous_runtime_fixture):
            stack.enter_context(patch.object(module, 'CONTRACT', self.small_contract))
        return stack

    def test_production_contract_is_exact_and_shared(self):
        expected = fixture.ApkIdentity(
            212, '0.2.1-rc.3',
            '2a69a315eca22f8711a97122a54a2f8beb4d98e624a04d8bb3afa65a420e89d2',
            'https://github.com/SunsetRNE/DSHA-Sunset-mod/releases/download/'
            'dsha-a-su-build212-20261030-150600/dsha-a-su-eb278988.apk')
        self.assertEqual(fixture.CONTRACT, expected)
        self.assertEqual(runner.previous_runtime_fixture.CONTRACT, expected)
        self.assertEqual(child.previous_runtime_fixture.CONTRACT, expected)
        self.assertEqual(child.OLD_ARCHIVE_SHA,
                         'a02cc5a42727238491ee36d4b0b46461d035dd6f8fca22580eb19d8456fbefc3')
        for verify in (fixture.verify_apk, self.values):
            with self.assertRaisesRegex(ValueError, 'PREVIOUS_RUNTIME_APK_IDENTITY'):
                verify(self.good)
        with self.assertRaisesRegex(ValueError, 'PREVIOUS_RUNTIME_APK_IDENTITY'):
            child.parse_args(['--old-apk', str(self.good)])

    def test_verified_path_passes_runner_and_child_under_test_only_contract(self):
        with self.contracts():
            self.assertEqual(fixture.verify_apk(self.good), self.good.resolve())
            values = self.values(str(self.good))
            argv = [runner.resolve(value, values) for value in self.row['args']]
            args = child.parse_args(argv)
            self.assertEqual(args.old_apk, self.good.resolve())
            # 不缓存路径的已验证状态；同一路径字节变化后必须重新拒绝。
            self.good.write_bytes(self.good_bytes + b'changed')
            with self.assertRaisesRegex(ValueError, 'PREVIOUS_RUNTIME_APK_IDENTITY'):
                self.values(str(self.good))
            with self.assertRaisesRegex(ValueError, 'PREVIOUS_RUNTIME_APK_IDENTITY'):
                child.parse_args(argv)

    def test_real_temporary_byte_negative_controls_reject_in_both_consumers(self):
        # 这些是标明 generation 的测试字节，不是假造/下载的历史 APK。
        cases = {
            'wrong': b'wrong fixture bytes',
            'current-build359': b'test-only current generation 0.2.2-rc.1 build359',
            'previous-build192': b'test-only obsolete generation build192',
            '192-digest-as-content': b'f1b332bc087e999bf9bd8954a70c9c7ad869d0a04193933d446854f0e31fbf3c',
        }
        with self.contracts():
            for name, content in cases.items():
                with self.subTest(name=name):
                    selected = self.directory / (name + '.apk')
                    selected.write_bytes(content)
                    with self.assertRaisesRegex(ValueError, 'PREVIOUS_RUNTIME_APK_IDENTITY'):
                        self.values(str(selected))
                    with self.assertRaisesRegex(ValueError, 'PREVIOUS_RUNTIME_APK_IDENTITY'):
                        child.parse_args(['--old-apk', str(selected)])
            missing = self.directory / 'missing.apk'
            for selected in (missing, self.directory):
                with self.subTest(missing=str(selected)):
                    with self.assertRaisesRegex(ValueError, 'PREVIOUS_RUNTIME_APK_MISSING'):
                        self.values(str(selected))
                    with self.assertRaisesRegex(ValueError, 'PREVIOUS_RUNTIME_APK_MISSING'):
                        child.parse_args(['--old-apk', str(selected)])

    def test_absent_argument_fails_closed(self):
        with self.assertRaisesRegex(ValueError, 'HOST_PREVIOUS_RUNTIME_APK_REQUIRED'):
            self.values(None)
        with self.assertRaisesRegex(ValueError, 'PREVIOUS_RUNTIME_APK_REQUIRED'):
            fixture.verify_apk(None)
        with patch.dict(os.environ, {}, clear=True), redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as error:
                child.parse_args([])
        self.assertEqual(error.exception.code, 2)

    def test_archive_remains_independently_hash_pinned(self):
        wrong = self.directory / 'wrong-runtime.tar'
        wrong.write_bytes(b'test-only wrong archive')
        with self.assertRaisesRegex(AssertionError, 'OLD_212_ARCHIVE_IDENTITY'):
            child.parse_args(['--old-archive', str(wrong)])


if __name__ == '__main__':
    if not __debug__:
        raise RuntimeError('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    unittest.main()
