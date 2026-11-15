#!/usr/bin/env python3
"""Synthetic APK identity checks only; never signs, builds or installs an APK."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('rebirth_apk_check', ROOT / 'tools/rebirth-apk-check.py')
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class ApkCheckTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='rebirth-apk-check-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.apk = self.root / 'synthetic-unsigned.apk'
        self.assets = {name: ('synthetic:' + name).encode() for name in CHECK.KEY_ASSETS}
        contract = dict(baseVersion='synthetic', inputs={
            name: hashlib.sha256(data).hexdigest() for name, data in self.assets.items()})
        self.runtime_id = hashlib.sha256(json.dumps(contract, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        self.descriptor = dict(version=1, runtimeId=self.runtime_id, **contract)
        self.descriptor_path = self.root / 'app/src/main/assets/runtime-descriptor.json'
        self.descriptor_path.parent.mkdir(parents=True)
        self.write_descriptor()
        self.badging = "\n".join([
            "package: name='dsh.android.client.rebirth' versionCode='359' versionName='candidate-Rebirth-Sprout.1'",
            "sdkVersion:'30'", "targetSdkVersion:'37'", "application-label:'DSHA 新生'",
            "application-label-zh:'DSHA 新生'", "native-code: 'arm64-v8a'"])
        self.manifest = '\n'.join(
            'A: android:authorities(0x01010018)="' + CHECK.PACKAGE + '.' + suffix + '" (Raw: "ignored")'
            for suffix in ('updates', 'shizuku', 'documents'))
        self.manifest += '\nA: android:taskAffinity(0x01010012)="' + CHECK.PACKAGE + '.entry"'
        self.manifest += '\nA: package="' + CHECK.PACKAGE + '"'

    def write_descriptor(self):
        self.descriptor_path.write_text(json.dumps(self.descriptor))

    def make_apk(self, extra=(), replace=None):
        members = [('lib/arm64-v8a/libsynthetic.so', b'synthetic library'),
                   ('assets/runtime-descriptor.json', self.descriptor_path.read_bytes())]
        members += [('assets/' + name, data) for name, data in self.assets.items()]
        if replace:
            members = [(name, replace.get(name, data)) for name, data in members]
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(self.apk, 'w') as archive:
                for name, data in members + list(extra):
                    archive.writestr(name, data)

    def test_actual_zip_assets_produce_bound_runtime_and_hashes(self):
        self.make_apk()
        actual = CHECK.inspect_apk(self.apk, self.root)
        self.assertEqual(self.runtime_id, actual['actualRuntimeId'])
        self.assertEqual(self.descriptor['inputs'], actual['actualAssetSha256'])
        self.assertEqual(['arm64-v8a'], actual['allAbi'])
        self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), CHECK.file_digest(self.apk))

    def test_duplicate_zip_member_is_rejected(self):
        self.make_apk([('assets/dsh-runtime.bin', b'duplicate')])
        with self.assertRaisesRegex(ValueError, 'REBIRTH_DUPLICATE_ZIP_MEMBER'):
            CHECK.inspect_apk(self.apk, self.root)

    def test_extra_or_missing_zip_abi_is_rejected(self):
        self.make_apk([('lib/x86_64/libsynthetic.so', b'extra')])
        with self.assertRaisesRegex(ValueError, 'REBIRTH_ZIP_ABI_IDENTITY'):
            CHECK.inspect_apk(self.apk, self.root)

    def test_v1_signature_extensions_are_rejected(self):
        for name in ('META-INF/CERT.RSA', 'META-INF/KEY.SF', 'meta-inf/key.ec', 'META-INF/CERT.DSA'):
            with self.subTest(name=name):
                self.make_apk([(name, b'not a real signature')])
                with self.assertRaisesRegex(ValueError, 'REBIRTH_UNEXPECTED_V1_SIGNATURE'):
                    CHECK.inspect_apk(self.apk, self.root)

    def test_v2_v3_signing_block_magic_is_rejected(self):
        self.make_apk()
        raw = self.apk.read_bytes()
        eocd = raw.rfind(b'PK\x05\x06')
        central = int.from_bytes(raw[eocd + 16:eocd + 20], 'little')
        block = b'not-a-valid-signature-blockAPK Sig Block 42'
        changed = bytearray(raw[:central] + block + raw[central:])
        changed[eocd + len(block) + 16:eocd + len(block) + 20] = (central + len(block)).to_bytes(4, 'little')
        self.apk.write_bytes(changed)
        with self.assertRaisesRegex(ValueError, 'REBIRTH_UNEXPECTED_APK_SIGNING_BLOCK'):
            CHECK.inspect_apk(self.apk, self.root)

    def test_eocd_invalid_or_trailing_bytes_rejected(self):
        for raw in (b'not zip', b'PK\x05\x06', b'PK\x05\x06' + b'\0' * 18 + b'trailing'):
            self.apk.write_bytes(raw)
            with self.assertRaises(ValueError):
                CHECK.unsigned_zip_boundary(self.apk)

    def test_descriptor_self_report_cannot_replace_actual_asset_hash(self):
        self.make_apk(replace={'assets/dsh-runtime.bin': b'changed runtime'})
        with self.assertRaisesRegex(ValueError, 'REBIRTH_KEY_ASSET_DIGEST'):
            CHECK.inspect_apk(self.apk, self.root)

    def test_descriptor_must_match_source_and_derive_runtime_id(self):
        self.make_apk(replace={'assets/runtime-descriptor.json': b'{}'})
        with self.assertRaisesRegex(ValueError, 'REBIRTH_DESCRIPTOR_SOURCE'):
            CHECK.inspect_apk(self.apk, self.root)
        self.descriptor['runtimeId'] = '0' * 64
        self.write_descriptor()
        self.make_apk()
        with self.assertRaisesRegex(ValueError, 'REBIRTH_RUNTIME_IDENTITY'):
            CHECK.inspect_apk(self.apk, self.root)

    def test_badging_requires_exact_single_abi(self):
        self.assertEqual('candidate-Rebirth-Sprout.1', CHECK.verify_badging(self.badging))
        for replacement in ("native-code: 'arm64-v8a' 'x86_64'", "native-code: 'arm64-v8a-extra'",
                            "native-code: 'x86_64'", "native-code: 'arm64-v8a'\nnative-code: 'arm64-v8a'"):
            with self.subTest(replacement=replacement):
                with self.assertRaisesRegex(ValueError, 'REBIRTH_ABI_IDENTITY'):
                    CHECK.verify_badging(self.badging.replace("native-code: 'arm64-v8a'", replacement))

    def test_badging_identity_platform_debug_and_labels(self):
        for before, after in ((CHECK.PACKAGE, CHECK.PACKAGE + '.test'), ('359', '360'),
                              ("sdkVersion:'30'", "sdkVersion:'29'"),
                              ("targetSdkVersion:'37'", "targetSdkVersion:'36'"), ('DSHA 新生', 'old label')):
            with self.subTest(before=before):
                with self.assertRaises(ValueError):
                    CHECK.verify_badging(self.badging.replace(before, after))
        with self.assertRaises(ValueError):
            CHECK.verify_badging(self.badging + '\napplication-debuggable')

    def test_manifest_exact_authorities_and_entry(self):
        CHECK.verify_manifest(self.manifest)
        for changed in (self.manifest.replace('.updates', '.updates.extra'),
                        self.manifest.replace('.shizuku', '.shizuku;io.github.sunsetrne.dsha.shizuku'),
                        self.manifest + '\nA: android:authorities="extra.authority"',
                        self.manifest.replace('.entry', '.entry.extra'),
                        self.manifest.replace('.documents', '.test.documents')):
            with self.assertRaises(ValueError):
                CHECK.verify_manifest(changed)

    def test_manifest_rejects_legacy_and_candidate_test_identity_values(self):
        for value in ('io.github.sunsetrne.dsha', 'io.github.sunsetrne.dsha.test.provider',
                      'dsh.android.client', 'dsh.android.client.test.provider',
                      CHECK.PACKAGE + '.test', CHECK.PACKAGE + '.unexpected'):
            with self.subTest(value=value):
                with self.assertRaisesRegex(ValueError, 'REBIRTH_MANIFEST_LEGACY_IDENTITY'):
                    CHECK.verify_manifest(self.manifest + '\nA: android:name="' + value + '"')
        CHECK.verify_manifest(self.manifest + '\nA: android:name="com.deepseekharness.app.DshaApp"')

    def test_digest_reads_bounded_chunks(self):
        class Limited(io.BytesIO):
            def read(self, size=-1):
                self_size = CHECK.CHUNK
                if size != self_size:
                    raise AssertionError('unbounded read')
                return super().read(size)
        self.assertEqual(hashlib.sha256(b'abc').hexdigest(), CHECK.stream_digest(Limited(b'abc')))

    def test_unsigned_verifier_requires_exact_unsigned_failure(self):
        CHECK.require_unsigned_verifier(SimpleNamespace(returncode=1, stdout='', stderr='DOES NOT VERIFY'))
        for code, output in ((0, 'DOES NOT VERIFY'), (2, 'DOES NOT VERIFY'), (1, 'tool crashed'), (0, 'Verifies')):
            with self.subTest(code=code, output=output):
                with self.assertRaisesRegex(ValueError, 'REBIRTH_UNSIGNED_VERIFIER_UNEXPECTED_RESULT'):
                    CHECK.require_unsigned_verifier(SimpleNamespace(returncode=code, stdout=output, stderr=''))

    def test_diagnostic_exact_whitelist_only(self):
        for original, safe in CHECK.SAFE_REASONS.items():
            self.assertEqual(safe, CHECK.diagnostic_reason(ValueError(original)))
            self.assertRegex(safe, r'^REBIRTH_[A-Z0-9_]+$')
        for error in (ValueError('REBIRTH_MANIFEST_PACKAGE\n::error::secret'),
                      ValueError('REBIRTH_MANIFEST_PACKAGE extra'),
                      ValueError('prefix REBIRTH_MANIFEST_PACKAGE'),
                      ValueError('REBIRTH_KEY_ASSET_DIGEST:unknown/path'),
                      ValueError('REBIRTH_PLATFORM_IDENTITY:unknown'),
                      ValueError('REBIRTH_MANIFEST_PACKAGE', 'secret'),
                      ValueError({'secret': 'value'}), RuntimeError('REBIRTH_MANIFEST_PACKAGE')):
            with self.subTest(error=type(error).__name__):
                self.assertEqual('STEP_FAILED', CHECK.diagnostic_reason(error))

    def test_failure_annotation_once_and_original_exception_propagates(self):
        for stage in CHECK.PHASES:
            original = ValueError('REBIRTH_MANIFEST_PACKAGE')
            def failing():
                CHECK.phase(stage)
                raise original
            output = io.StringIO()
            with patch.object(CHECK, 'main', failing), contextlib.redirect_stdout(output):
                with self.assertRaises(ValueError) as raised:
                    CHECK.run_with_diagnostic()
            self.assertIs(original, raised.exception)
            self.assertEqual(['::error::gate=unsigned-candidate-check phase=' + stage
                              + ' reason=REBIRTH_MANIFEST_PACKAGE'], output.getvalue().splitlines())

    def test_unknown_failure_cannot_inject_annotation_and_success_is_quiet(self):
        original = RuntimeError('secret\n::error::gate=forged reason=forged')
        def failing():
            CHECK.phase('MANIFEST')
            raise original
        output = io.StringIO()
        with patch.object(CHECK, 'main', failing), contextlib.redirect_stdout(output):
            with self.assertRaises(RuntimeError) as raised:
                CHECK.run_with_diagnostic()
        self.assertIs(original, raised.exception)
        self.assertEqual('::error::gate=unsigned-candidate-check phase=MANIFEST reason=STEP_FAILED\n', output.getvalue())
        output = io.StringIO()
        with patch.object(CHECK, 'main', lambda: None), contextlib.redirect_stdout(output):
            CHECK.run_with_diagnostic()
        self.assertEqual('', output.getvalue())
        self.assertEqual('COMPLETE', CHECK._PHASE)
        with self.assertRaises(ValueError):
            CHECK.phase('MANIFEST\n::error::forged')

    def test_system_exit_zero_is_quiet_and_nonzero_remains_failure(self):
        for code in (None, 0, 2):
            original = SystemExit(code)
            def exiting():
                CHECK.phase('INPUT_IDENTITY')
                raise original
            output = io.StringIO()
            with patch.object(CHECK, 'main', exiting), contextlib.redirect_stdout(output):
                with self.assertRaises(SystemExit) as raised:
                    CHECK.run_with_diagnostic()
            self.assertIs(original, raised.exception)
            self.assertEqual('' if code is None or code == 0 else
                             '::error::gate=unsigned-candidate-check phase=INPUT_IDENTITY reason=STEP_FAILED\n',
                             output.getvalue())

    def test_helpers_set_precise_phase_without_printing_annotation(self):
        self.make_apk()
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            CHECK.inspect_apk(self.apk, self.root)
        self.assertEqual('ZIP_BOUNDARY', CHECK._PHASE)
        self.assertEqual('', output.getvalue())
        self.make_apk(replace={'assets/dsh-runtime.bin': b'mismatch'})
        with self.assertRaises(ValueError):
            CHECK.inspect_apk(self.apk, self.root)
        self.assertEqual('ASSETS', CHECK._PHASE)
        self.make_apk([('assets/dsh-runtime.bin', b'duplicate')])
        with self.assertRaises(ValueError):
            CHECK.inspect_apk(self.apk, self.root)
        self.assertEqual('ZIP', CHECK._PHASE)

    def test_cli_keeps_original_signature_and_verifier_chain(self):
        source = (ROOT / 'tools/rebirth-apk-check.py').read_text()
        for text in ("'--build-tools'", "'--output'", 'verify-dsh-upgrade-apk.py',
                     'verify-recovery-apk.py', 'verify-apk-managed-assets.py', 'verify-third-party-apk.py',
                     "verification.returncode != 1", "'DOES NOT VERIFY'", "signatureStatus='NOT_SIGNED'",
                     "signature=None", "installable=False"):
            self.assertIn(text, source)
        self.assertEqual('db59aed4b10b0e4eca9ceee2f2f0b8befcc5c2c0', CHECK.VALIDATION_BASELINE)
        self.assertEqual(43692535293, CHECK.SOFTWARE_RUN)


if __name__ == '__main__':
    unittest.main()
