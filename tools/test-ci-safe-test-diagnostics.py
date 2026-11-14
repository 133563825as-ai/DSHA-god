#!/usr/bin/env python3
"""固定诊断解析器合成恶意输入负控；不启动浏览器/JVM/CI。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('safe_diag', ROOT/'tools/ci-safe-test-diagnostics.py')
mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)


class SafeDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = self.root/mod.OUTPUT; self.output.mkdir(parents=True)
        (self.root/'tools').mkdir()
        self.path = 'tools/test-mobile-refresh.mjs'
        self.test, self.phases = mod.CONTRACTS[self.path]
        (self.root/'tools/host-tests.manifest.json').write_text(json.dumps({'tests': [{'path': p, 'stage': 'runtime'} for p in mod.CONTRACTS]}))
        self.row = {'path': self.path, 'status': 'FAILED', 'exitCode': 1, 'error': 'PRIVATE_SECRET', 'log': '/private/log'}
        self.report()
        self.log = self.output/(Path(self.path).name + '.log')

    def report(self, rows=None):
        (self.output/'manifest.json').write_text(json.dumps({'tests': [self.row] if rows is None else rows}))

    def marker(self, **change):
        value = {'test': self.test, 'phase': 'CARD_TAP', 'reason': 'STEP_FAILED'}; value.update(change)
        return mod.PREFIX + json.dumps(value).encode() + b'\n'

    def test_exact_codes_only_ignore_raw_logs_and_untrusted_paths(self):
        self.log.write_bytes(b'PRIVATE_SECRET ::error::raw\n' + self.marker())
        lines = mod.annotations(self.root)
        self.assertEqual(['::error::gate=l3-runtime path=test-mobile-refresh.mjs status=FAILED exitCode=1 phase=CARD_TAP reason=STEP_FAILED'], lines)
        self.assertNotIn('PRIVATE_SECRET', ''.join(lines)); self.assertNotIn('/private', ''.join(lines))

    def test_every_declared_phase_has_exact_reason_pair(self):
        for test, phases in mod.CONTRACTS.values():
            for phase in phases:
                reason = 'COMPLETED' if phase == 'COMPLETE' else 'CLEANUP_FAILED' if phase == 'CLEANUP' else 'STEP_FAILED'
                data = mod.PREFIX + json.dumps({'test': test, 'phase': phase, 'reason': reason}).encode()
                value, error = mod.parse_marker(data, test, phases)
                self.assertIsNone(error); self.assertEqual(phase, value['phase'])
                for wrong in {'COMPLETED', 'CLEANUP_FAILED', 'STEP_FAILED'} - {reason}:
                    bad = mod.PREFIX + json.dumps({'test': test, 'phase': phase, 'reason': wrong}).encode()
                    self.assertEqual('DIAG_INVALID', mod.parse_marker(bad, test, phases)[1])

    def test_unknown_extra_wrong_types_and_injection_reject_without_raw(self):
        for change in [{'test': 'OTHER'}, {'phase': 'OTHER'}, {'phase': 'CARD_TAP\n::error::PRIVATE_SECRET'},
                       {'phase': 'CARD_TAP%0A'}, {'reason': ['bad']}, {'test': {}}, {'phase': None},
                       {'error': 'PRIVATE_SECRET'}, {'sha256': 'a'*64}, {'reason': 'PRIVATE_SECRET'}]:
            self.log.write_bytes(self.marker(**change))
            lines = mod.annotations(self.root)
            self.assertIn('diag=DIAG_INVALID', lines[0]); self.assertNotIn('PRIVATE_SECRET', ''.join(lines))

    def test_duplicate_terminal_even_identical_is_ambiguous(self):
        self.log.write_bytes(self.marker()*2)
        self.assertIn('diag=DIAG_AMBIGUOUS', mod.annotations(self.root)[0])

    def test_missing_truncated_huge_and_duplicate_keys(self):
        for data, result in [(b'raw PRIVATE_SECRET', 'DIAG_UNAVAILABLE'),
                             (mod.PREFIX+b'{', 'DIAG_INVALID'),
                             (mod.PREFIX+b'x'*(mod.MAX_LINE+1), 'DIAG_INVALID'),
                             (mod.PREFIX+b'{"test":"MOBILE_REFRESH","test":"MOBILE_REFRESH","phase":"CARD_TAP","reason":"STEP_FAILED"}', 'DIAG_INVALID')]:
            self.log.write_bytes(data)
            self.assertIn('diag='+result, mod.annotations(self.root)[0])

    def test_symlink_file_or_parent_is_never_read(self):
        outside = self.root/'outside'; outside.write_bytes(self.marker())
        self.log.symlink_to(outside)
        self.assertIn('diag=DIAG_UNAVAILABLE', mod.annotations(self.root)[0])
        self.log.unlink()
        with self.assertRaises((OSError, ValueError)):
            mod.read_owned(self.root, Path('outside/../outside'), 1024)
        parent = self.root/'linked'; parent.symlink_to(self.output, target_is_directory=True)
        with self.assertRaises((OSError, ValueError)):
            mod.read_owned(self.root, Path('linked/manifest.json'), 1024)

    def test_size_limit_and_nonregular_reject(self):
        self.log.write_bytes(b'x'*(mod.MAX_LOG+1))
        self.assertIn('diag=DIAG_UNAVAILABLE', mod.annotations(self.root)[0])
        self.log.unlink(); self.log.mkdir()
        self.assertIn('diag=DIAG_UNAVAILABLE', mod.annotations(self.root)[0])

    def test_manifest_unknown_path_state_and_exit_never_pass_through(self):
        self.log.write_bytes(self.marker())
        self.report([{'path': 'tools/unknown.py', 'status': 'FAILED', 'exitCode': 1}])
        self.assertEqual([], mod.annotations(self.root))
        self.row['status'] = 'FAILED\n::error::PRIVATE_SECRET'; self.report()
        self.assertEqual([], mod.annotations(self.root))
        self.row['status'] = 'FAILED'; self.row['exitCode'] = True; self.report()
        self.assertIn('exitCode=unknown', mod.annotations(self.root)[0])
        self.report([self.row, self.row])
        self.assertEqual(['::error::gate=l3-runtime path=test-mobile-refresh.mjs diag=DIAG_AMBIGUOUS'], mod.annotations(self.root))

    def test_unavailable_manifest_and_bad_shape_are_fixed(self):
        for data in ['{', '[]', '{"tests":null}']:
            (self.output/'manifest.json').write_text(data)
            self.assertEqual(['::error::gate=l3-runtime diag=DIAG_MANIFEST_UNAVAILABLE'], mod.annotations(self.root))

    def test_workflow_only_adds_helper_to_failure_summary(self):
        text = (ROOT/'.github/workflows/rebirth-validation.yml').read_text()
        block = text.split('      - name: L3 safe failure summary', 1)[1].split('      - name: L3 software gates', 1)[0]
        self.assertIn("if: failure() && steps.l3_runtime.outcome == 'failure'", block)
        self.assertEqual(1, text.count('python3 -B tools/ci-safe-test-diagnostics.py'))
        self.assertIn('python3 -B tools/ci-safe-test-diagnostics.py', block)
        self.assertNotIn('continue-on-error', text)


if __name__ == '__main__': unittest.main(verbosity=2)
