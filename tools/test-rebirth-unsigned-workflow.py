#!/usr/bin/env python3
"""候选包CI准入及边界合成负控；不联网、不运行Gradle、不生成APK。"""
import copy
import importlib.util
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('admission', ROOT/'tools/check-rebirth-package-admission.py')
mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)


class CandidateWorkflowTest(unittest.TestCase):
    def run_record(self):
        return {'id': mod.RUN, 'head_sha': mod.BASE, 'status': 'completed', 'conclusion': 'success',
            'event': 'push', 'head_branch': 'validate/rebirth-rc1-20261114',
            'workflow_id': 418225569, 'name': 'Rebirth RC1 Ubuntu tests only',
            'path': '.github/workflows/rebirth-validation.yml', 'repository': {'full_name': mod.REPO}}

    def jobs_record(self):
        return {'total_count': 1, 'jobs': [{'id': 132745996960, 'name': 'gates', 'run_id': mod.RUN,
            'head_sha': mod.BASE, 'status': 'completed', 'conclusion': 'success',
            'steps': [{'name': n, 'status': 'completed', 'conclusion': 'success'} for n in mod.REQUIRED_STEPS]}]}

    def test_fixed_run_and_all_required_stages_admit(self):
        mod.validate_run(self.run_record()); mod.validate_jobs(self.jobs_record())

    def test_other_sha_run_repo_event_workflow_or_conclusion_reject(self):
        for key, bad in [('head_sha', 'a'*40), ('id', 1), ('event', 'workflow_dispatch'),
                         ('workflow_id', 1), ('name', 'other'), ('path', 'other.yml'),
                         ('head_branch', 'main'), ('status', 'in_progress'), ('conclusion', 'failure'),
                         ('repository', {'full_name': 'other/repo'})]:
            row = self.run_record(); row[key] = bad
            with self.subTest(key=key), self.assertRaises(ValueError): mod.validate_run(row)

    def test_failed_skipped_missing_or_duplicate_required_step_reject(self):
        for mode in ['failed', 'skipped', 'missing', 'duplicate']:
            value = self.jobs_record(); rows = value['jobs'][0]['steps']
            if mode == 'missing': rows.pop()
            elif mode == 'duplicate': rows.append(copy.deepcopy(rows[0]))
            else: rows[0]['conclusion'] = 'failure' if mode == 'failed' else 'skipped'
            with self.subTest(mode=mode), self.assertRaises(ValueError): mod.validate_jobs(value)

    def test_other_job_or_multiple_jobs_reject(self):
        for key in ['id', 'name', 'head_sha', 'run_id', 'status', 'conclusion']:
            value = self.jobs_record(); value['jobs'][0][key] = 'wrong'
            with self.assertRaises(ValueError): mod.validate_jobs(value)
        value = self.jobs_record(); value['total_count'] = 2
        with self.assertRaises(ValueError): mod.validate_jobs(value)

    def test_only_exact_eight_ci_checker_paths_can_change(self):
        self.assertEqual(8, len(mod.ALLOWED))
        mod.changed_paths(('\0'.join(sorted(mod.ALLOWED))+'\0').encode())
        for path in ['app/build.gradle', 'app/src/main/assets/runtime-descriptor.json',
                     'tools/dsh-runtime/package-lock.json', 'tools/other.py', '../unsafe', '.github/workflows/rebirth-validation.yml']:
            with self.subTest(path=path), self.assertRaises(ValueError): mod.changed_paths((path+'\0').encode())

    def test_detached_head_uses_exact_transport_not_attached_branch(self):
        head = 'a'*40
        env = {'GITHUB_EVENT_NAME': 'push', 'GITHUB_REPOSITORY': mod.REPO,
               'GITHUB_REF': 'refs/heads/'+mod.BRANCH, 'GITHUB_SHA': head}
        mod.validate_transport(env, head)
        for key, value in [('GITHUB_EVENT_NAME', 'workflow_dispatch'), ('GITHUB_REPOSITORY', 'other/repo'),
                           ('GITHUB_REF', 'refs/heads/main'), ('GITHUB_SHA', 'b'*40)]:
            bad = dict(env); bad[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): mod.validate_transport(bad, head)

    def test_public_api_does_not_add_token_or_retry(self):
        source = (ROOT/'tools/check-rebirth-package-admission.py').read_text()
        self.assertNotIn("'Authorization':", source)
        for forbidden in ['GITHUB_TOKEN', 'GH_TOKEN', "'branch', '--show-current'"]:
            self.assertNotIn(forbidden, source)
        self.assertNotIn('sleep(', source)
        with self.assertRaises(ValueError): mod.NoRedirect().redirect_request(None, None, None, None, None, None)

    def test_pack_trigger_permissions_and_no_install_publish_sign(self):
        text = (ROOT/'.github/workflows/rebirth-unsigned-candidate.yml').read_text()
        self.assertIn("branches: ['validate/rebirth-unsigned-20261115']", text)
        self.assertIn("github.event_name == 'push'", text)
        self.assertIn("github.repository == '133563825as-ai/DSHA-god'", text)
        self.assertIn("github.ref == 'refs/heads/validate/rebirth-unsigned-20261115'", text)
        self.assertIn('contents: read', text); self.assertIn('persist-credentials: false', text)
        for forbidden in ['workflow_dispatch:', 'secrets:', 'continue-on-error', 'apksigner sign', '--stage package', '--stage all', 'tools/dsha-gradle --']:
            self.assertNotIn(forbidden, text)
        selected = re.findall(r'uses:\s*([^\s#]+)', text)
        for action in selected:
            self.assertTrue(re.fullmatch(r'\./\.github/actions/[\w-]+', action) or re.fullmatch(r'[\w.-]+/[\w.-]+@[0-9a-f]{40}', action))

    def test_exact_candidate_flags_and_no_reuse_of_tests_only_fence(self):
        text = (ROOT/'.github/workflows/rebirth-unsigned-candidate.yml').read_text()
        command = next(line.strip() for line in text.splitlines() if line.strip().startswith('bash gradlew '))
        self.assertEqual('bash gradlew :app:printReleaseIdentity :app:assembleStandardRelease -Pdsha.identity=upstream -Pdsha.unsignedPackage=true -Pdsha.rebirthCandidate=true --no-daemon --max-workers=2 2>&1 | tee out/rebirth-unsigned/candidate-build.log', command)
        self.assertNotIn('sourceOnlyTests=true', text)
        self.assertNotIn('--allow-stale-archive', text)
        self.assertNotIn('rebuild_runtime:', text)
        self.assertIn('prepare-validation-inputs.py', text)
        self.assertIn('prepare-test-runtime.py --platform host', text)
        for requirement in ['REBIRTH_PACK_TASK_ENTRY', 'REBIRTH_PACK_PROPERTIES', 'REBIRTH_PACK_ANDROID_CONFIGURATION',
                            'REBIRTH_PACK_FORBIDDEN_GRAPH', 'buildTypes.release.signingConfig == null',
                            "':app:validateSigningStandardRelease'", "'com.android.build.gradle.internal.tasks.ValidateSigningTask'",
                            "t.path != ':app:packageStandardRelease'", 'verifyPublishCertificate', 'SignApk', 'PackageBundleTask']:
            self.assertIn(requirement, text)

    def test_actual_artifact_endpoint_fence_not_jar_or_signing_metadata(self):
        text = (ROOT/'.github/workflows/rebirth-unsigned-candidate.yml').read_text()
        fence = text.split('gradle.taskGraph.whenReady', 1)[1].split('          GRADLE', 1)[0]
        self.assertNotIn("n.startsWith('bundle')", fence)
        self.assertNotIn('SigningConfigWriter', fence)
        self.assertIn("chain.contains('com.android.build.gradle.tasks.PackageApplication')", fence)
        self.assertIn("n ==~ /(?i)bundleStandard(Debug|Release)/", fence)
        pattern = r'(?i)bundleStandard(Debug|Release)'
        for denied in ['bundleStandardRelease', 'bundleStandardDebug']:
            self.assertRegex(denied, pattern)
        for allowed in ['bundleLibRuntimeToJarStandardRelease', 'bundleLibCompileToJarStandardRelease', 'bundleStandardReleaseResources']:
            self.assertIsNone(re.fullmatch(pattern, allowed))
        self.assertIn("n.startsWith('package')", fence)
        self.assertIn("n.endsWith('Resources')", fence)
        self.assertIn("t.path == ':app:validateSigningStandardRelease'", fence)
        self.assertIn("chain.contains('com.android.build.gradle.internal.tasks.ValidateSigningTask')", fence)

    def test_build_diagnostic_is_fixed_codes_and_bounded_task_names_only(self):
        text = (ROOT/'.github/workflows/rebirth-unsigned-candidate.yml').read_text()
        for code in ['TASK_ENTRY', 'PROPERTIES', 'ENVIRONMENT', 'ANDROID_CONFIGURATION', 'FORBIDDEN_GRAPH']:
            self.assertIn("println('::error::gate=unsigned-candidate-build reason=REBIRTH_PACK_" + code + "')", text)
            self.assertIn("throw new GradleException('REBIRTH_PACK_" + code + "')", text)
        self.assertIn('reason=GRADLE_FAILED_UNCLASSIFIED exitCode=%s', text)
        self.assertNotIn('BASH_COMMAND', text)
        self.assertNotIn('forbidden*.path', text)
        self.assertIn('forbidden.take(32)', text)
        self.assertIn('taskName=COUNT_TRUNCATED', text)
        pattern = re.search(r'def safeName = t.name ==~ /([^/]+)/ \? t.name : \'UNSAFE_NAME\'', text)
        self.assertIsNotNone(pattern)
        for value in ['packageStandardRelease', 'extractStandardReleaseNativeDebugMetadata']:
            self.assertEqual(value, value if re.fullmatch(pattern.group(1), value) else 'UNSAFE_NAME')
        for value in ['bad%0A::error::PRIVATE', 'bad\nPRIVATE', ':app:privateTask', '../private', '', 'a'*121]:
            safe = value if re.fullmatch(pattern.group(1), value) else 'UNSAFE_NAME'
            self.assertEqual('UNSAFE_NAME', safe)
            self.assertNotIn('PRIVATE', safe)
        # 仍保留原拒绝规则，不因诊断放行Debug/Low任务或JVM环境注入。
        self.assertIn('n ==~ /(?i).*(Debug|Low).*/', text)
        self.assertIn("'JAVA_TOOL_OPTIONS'", text)

    def test_unsigned_artifact_success_only_and_evidence_no_wide_builds(self):
        text = (ROOT/'.github/workflows/rebirth-unsigned-candidate.yml').read_text()
        artifact = text.split('      - name: Upload unsigned candidate not for installation', 1)[1].split('      - name: Upload package check evidence', 1)[0]
        self.assertIn('if: success()', artifact)
        self.assertIn('REBIRTH-UNSIGNED-NOT-FOR-INSTALLATION', artifact)
        self.assertIn('out/rebirth-unsigned/*-unsigned.apk', artifact)
        self.assertIn('out/rebirth-unsigned/candidate-receipt.json', artifact)
        evidence = text.split('      - name: Upload package check evidence', 1)[1]
        self.assertIn('if: always()', evidence)
        self.assertNotIn('.apk', evidence); self.assertNotIn('app/build', evidence)


if __name__ == '__main__': unittest.main(verbosity=2)
