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

    def test_proof_worker_terminal_preserves_primary_and_cleanup_failure(self):
        import subprocess
        source = (ROOT/'tools/test-plugin-bundle-proof-workers.mjs').read_text()
        cleanup = source.split('}catch(error){failurePhase=phase;primaryError=error;throw error;}', 1)[1].split("phase='REMOVAL';\nconst report=", 1)[0]
        terminal = source.split("phase='COMPLETE';\n", 1)[1]
        for main_fail, cleanup_fail, phase, reason in [
            (False, False, 'COMPLETE', 'COMPLETED'), (True, False, 'BURST_ADMISSION', 'STEP_FAILED'),
            (False, True, 'CLEANUP', 'CLEANUP_FAILED'), (True, True, 'BURST_ADMISSION', 'STEP_FAILED')]:
            script = "let phase='BURST_ADMISSION',failurePhase,primaryError,cleanupFailure=false; const originalOpen=()=>{},previous=undefined,readGate=undefined,server=undefined;let measure=true;const mainError=Error('synthetic main');const managerContexts=[{fiber:{dispose:async()=>{if(" + str(cleanup_fail).lower() + ")throw Error('synthetic cleanup');}}}];const fs={promises:{open:originalOpen}};\n"
            script += "try { try { if("+str(main_fail).lower()+")throw mainError; }catch(error){failurePhase=phase;primaryError=error;throw error;}" + cleanup
            script += "phase='COMPLETE';\n" + terminal
            synthetic = self.root/'terminal.mjs'; synthetic.write_text(script)
            result = subprocess.run(['node', str(synthetic)], capture_output=True, text=True, timeout=10)
            self.assertEqual(int(main_fail or cleanup_fail), result.returncode, result.stderr)
            rows = [line for line in result.stdout.splitlines() if line.startswith(mod.PREFIX.decode())]
            self.assertEqual(1, len(rows))
            marker, error = mod.parse_marker(rows[0].encode(), *mod.CONTRACTS['tools/test-plugin-bundle-proof-workers.mjs'])
            self.assertIsNone(error); self.assertEqual((phase, reason), (marker['phase'], marker['reason']))

    def test_proof_worker_assertions_and_scheduling_budget_remain_exact(self):
        import subprocess
        source = (ROOT/'tools/test-plugin-bundle-proof-workers.mjs').read_text()
        old = subprocess.check_output(['git', 'show', 'HEAD:tools/test-plugin-bundle-proof-workers.mjs'], cwd=ROOT, text=True)
        # 原整行包含断言/轮询/并发请求的算法表达式不得改写，仅新增单独phase行。
        for line in old.splitlines():
            if 'assert' in line and not line.startswith('import ') or 'i<100' in line or 'i<200' in line or 'length:18' in line:
                self.assertIn(line, source)
        self.assertIn('PLUGIN_BUNDLE_PROOF_WORKERS', {v[0] for v in mod.CONTRACTS.values()})

    def test_workflow_only_adds_helper_to_failure_summary(self):
        text = (ROOT/'.github/workflows/rebirth-validation.yml').read_text()
        block = text.split('      - name: L3 safe failure summary', 1)[1].split('      - name: L3 software gates', 1)[0]
        self.assertIn("if: failure() && steps.l3_runtime.outcome == 'failure'", block)
        self.assertEqual(1, sum(line.strip() == 'python3 -B tools/ci-safe-test-diagnostics.py' for line in text.splitlines()))
        self.assertIn('python3 -B tools/ci-safe-test-diagnostics.py', block)
        self.assertNotIn('continue-on-error', text)


class SoftwareDiagnosticsTest(unittest.TestCase):
    def record(self, phase='java-version', code=0, **extra):
        return (json.dumps({'phase': phase, 'exitCode': code, 'log': 'PRIVATE_PATH', **extra})+'\n').encode()

    def sequence(self, count, failed=None):
        return b''.join(self.record(phase, -1 if failed == i else 0) for i, phase in enumerate(mod.SOFTWARE_PHASES[:count]))

    def terminal(self):
        return b'{"status":"INCOMPLETE_OR_FAILED","report":"PRIVATE_PATH"}\n'

    def test_exact_failed_command_only_exposes_fixed_name_and_int(self):
        value = mod.software_annotation(self.sequence(4, 3) + self.terminal())
        self.assertEqual('::error::gate=l3-software reason=COMMAND_FAILED phase=gradle-verification exitCode=-1', value)
        self.assertNotIn('PRIVATE', value)

    def test_internal_check_only_reports_last_completed_not_failure_phase(self):
        data = self.sequence(4) + b'{"status":"INCOMPLETE_OR_FAILED","report":"PRIVATE_PATH"}\n'
        self.assertEqual('::error::gate=l3-software reason=INTERNAL_CHECK_FAILED lastCompleted=gradle-verification', mod.software_annotation(data))

    def test_truncated_prefix_with_no_terminal_is_unavailable(self):
        for data in [self.sequence(2), self.sequence(4, 3)]:
            self.assertEqual('::error::gate=l3-software diag=DIAG_UNAVAILABLE', mod.software_annotation(data))

    def test_plain_lines_are_ignored_never_exposed(self):
        result = mod.software_annotation(b'PRIVATE_SECRET ::error::raw\n' + self.sequence(1, 0) + self.terminal())
        self.assertNotIn('PRIVATE', result); self.assertIn('COMMAND_FAILED', result)

    def test_missing_records_unavailable(self):
        self.assertEqual('::error::gate=l3-software diag=DIAG_UNAVAILABLE', mod.software_annotation(b'ordinary PRIVATE text\n'))

    def test_duplicate_phase_or_second_sequence_rejected(self):
        for data in [self.record()*2, self.sequence(19)+self.record()]:
            self.assertIn('DIAG_INVALID', mod.software_annotation(data))

    def test_out_of_order_or_unknown_phase_rejected(self):
        for phase in ['node-version', 'UNKNOWN', 'java-version\n::error::PRIVATE', ['java-version']]:
            self.assertIn('DIAG_INVALID', mod.software_annotation(self.record(phase)))

    def test_exit_bool_string_or_out_of_range_rejected(self):
        for value in [True, '1', 1.0, 256, -256, None]:
            self.assertIn('DIAG_INVALID', mod.software_annotation(self.record(code=value)))

    def test_nonzero_cannot_be_followed_by_another_command(self):
        self.assertIn('DIAG_INVALID', mod.software_annotation(self.record(code=1)+self.record('node-version')))

    def test_bad_keys_duplicate_keys_or_truncated_json_rejected(self):
        for data in [self.record(extra='PRIVATE'), b'{"phase":"java-version","phase":"java-version","exitCode":1,"log":"x"}\n', b'{', b'{"phase":"java-version"}\n']:
            self.assertIn('DIAG_INVALID', mod.software_annotation(data))

    def test_terminal_wrong_shape_success_or_repeated_rejected(self):
        for data in [b'{"status":"PASS_SOFTWARE","report":"PRIVATE"}\n', b'{"status":"OTHER","report":"PRIVATE"}\n',
                     b'{"status":"INCOMPLETE_OR_FAILED","report":"PRIVATE"}\n'*2,
                     b'{"status":"INCOMPLETE_OR_FAILED","report":"PRIVATE"}\n'+self.record('node-version')]:
            self.assertIn('DIAG_INVALID', mod.software_annotation(self.record()+data))

    def test_oversized_or_control_json_is_fixed_invalid(self):
        for data in [b'{'+b'x'*mod.MAX_LINE, b'{\t"phase":"java-version","exitCode":1,"log":"PRIVATE"}']:
            self.assertEqual('::error::gate=l3-software diag=DIAG_INVALID', mod.software_annotation(data))

    def test_fixed_software_path_nofollow_and_bounds(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); target=root/mod.SOFTWARE_LOG;target.parent.mkdir(parents=True)
            outside=root/'outside';outside.write_bytes(self.record(code=1));target.symlink_to(outside)
            self.assertIn('DIAG_UNAVAILABLE', mod.software_annotations(root)[0])
            target.unlink();target.write_bytes(b'x'*(mod.MAX_LOG+1))
            self.assertIn('DIAG_UNAVAILABLE', mod.software_annotations(root)[0])

    def test_workflow_summary_failure_only_and_original_software_invocation(self):
        text=(ROOT/'.github/workflows/rebirth-validation.yml').read_text()
        block=text.split('      - name: L3 safe software failure summary',1)[1].split('      - name: Upload test evidence',1)[0]
        self.assertIn("if: failure() && steps.l3_software.outcome == 'failure'",block)
        self.assertIn('run: python3 -B tools/ci-safe-test-diagnostics.py --software',block)
        self.assertIn('python3 tools/verify-stability.py --stage software --java-home "$JAVA_HOME" --sdk "$ANDROID_HOME" --test-forks 1',text)
        self.assertNotIn('continue-on-error',text)


if __name__ == '__main__': unittest.main(verbosity=2)
