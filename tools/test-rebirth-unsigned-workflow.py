#!/usr/bin/env python3
"""当前候选CI结构负控；不联网、不运行Gradle、不生成APK。"""
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class CandidateWorkflowTest(unittest.TestCase):
    def test_old_entries_only_reject_and_never_build(self):
        for name in ("rebirth-unsigned-candidate.yml", "sprout-candidate.yml"):
            source = (ROOT / ".github/workflows" / name).read_text()
            self.assertIn("REBIRTH_OLD_ENTRY_RETIRED", source)
            self.assertIn("exit 1", source)
            for forbidden in ("push:", "gradlew", "secrets.", "upload-artifact", "identity=upstream"):
                self.assertNotIn(forbidden, source)

    def test_current_entry_has_no_signing_or_historical_baseline(self):
        source = (ROOT / ".github/workflows/rebirth-candidate.yml").read_text()
        for forbidden in ("sign-candidate:", "secrets.", "DSHA_KEYSTORE=", "base64 -d", "apksigner sign",
                          "check-rebirth-package-admission.py", "db59aed4", "continue-on-error"):
            self.assertNotIn(forbidden, source)
        self.assertIn("needs: [bind, inputs, validation, identity-matrix]", source)
        self.assertIn("NEEDS_JSON: ${{ toJSON(needs) }}", source)
        self.assertIn("rebirth_candidate_admission.py", source)
        self.assertIn("-Pdsha.identity=rebirth", source)
        self.assertIn("-Pdsha.rust.mode=off", source)
        self.assertIn("-Pdsha.unsignedPackage=true", source)
        self.assertIn("--allow-unsigned", source)
        self.assertIn("'requiresSigning': True", source)
        self.assertIn("仓外", source)

    def test_bind_requires_fresh_run_before_downstream_jobs(self):
        source = (ROOT / ".github/workflows/rebirth-candidate.yml").read_text()
        block = source.split("\n  bind:", 1)[1].split("\n  inputs:", 1)[0]
        script = block.split("          python3 - <<'PYCODE'\n", 1)[1].split(
            "          PYCODE", 1)[0]
        script = "\n".join(line[10:] for line in script.splitlines())
        import os
        from unittest import mock
        env = {"GITHUB_REPOSITORY": "133563825as-ai/DSHA-god",
               "GITHUB_REF": "refs/heads/sync/rebirth-latest-20261116",
               "EXPECTED_SHA": "a" * 40, "GITHUB_SHA": "a" * 40,
               "GITHUB_RUN_ATTEMPT": "1"}
        with mock.patch.dict(os.environ, env, clear=True):
            exec(compile(script, "candidate-bind", "exec"), {})
        for attempt in (None, "2", "3", "01"):
            current = dict(env)
            if attempt is None:
                current.pop("GITHUB_RUN_ATTEMPT")
            else:
                current["GITHUB_RUN_ATTEMPT"] = attempt
            with self.subTest(attempt=attempt), mock.patch.dict(os.environ, current, clear=True):
                with self.assertRaisesRegex(SystemExit, "REBIRTH_ADMISSION_FRESH_RUN_REQUIRED"):
                    exec(compile(script, "candidate-bind", "exec"), {})

    def test_software_gate_is_current_reusable_workflow(self):
        source = (ROOT / ".github/workflows/rebirth-validation.yml").read_text()
        self.assertIn("workflow_call:", source)
        for gate in ("contracts:", "java-format:", "kotlin-format:", "rust-check:",
                     "rust-differential:", "host-regression:", "unit:", "lint:"):
            self.assertIn("  " + gate, source)
        self.assertIn("mode: full", source)
        self.assertIn("require_fixtures: true", source)
        self.assertIn("rust_host: true", source)
        self.assertIn("--stage runtime", source)
        self.assertIn("--managed", source)
        self.assertIn("--previous-runtime-apk", source)
        self.assertIn("--pnpm-cli", source)
        self.assertIn("TEST_ONLY_FORBIDS_ARTIFACT_TASKS", source)
        for forbidden in ("secrets.", "push:", "20261105e", "db59aed4"):
            self.assertNotIn(forbidden, source)


class IdentityUnificationTest(unittest.TestCase):
    """同一候选身份只能有一份取值：仓库/分支、有效版本后缀与准入常量都由身份文件与解析器决定。"""

    def setUp(self):
        self.identity = json.loads((ROOT / "ci/release-identity.rebirth.json").read_text(encoding="utf-8"))
        self.workflow = (ROOT / ".github/workflows/rebirth-candidate.yml").read_text(encoding="utf-8")
        self.resolver = self.load("rebirth_release_identity", "tools/release-identity.py")
        self.admission = self.load("rebirth_admission_constants", "tools/rebirth_candidate_admission.py")

    @staticmethod
    def load(name, relative):
        spec = importlib.util.spec_from_file_location(name, ROOT / relative)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_workflow_and_admission_use_the_identity_repository_and_ref(self):
        self.assertIn("'%s'" % self.identity["repository"], self.workflow)
        self.assertIn("'%s'" % self.identity["ref"], self.workflow)
        self.assertEqual(self.admission.REPOSITORY, self.identity["repository"])
        self.assertEqual(self.admission.REF, self.identity["ref"])

    def test_effective_version_suffix_has_one_value(self):
        suffix = self.resolver.REBIRTH_VERSION_SUFFIX
        self.assertEqual(self.resolver.effective_version(self.identity)["versionName"],
                         self.identity["versionName"] + suffix)
        self.assertIn("def rebirthVersionSuffix = '" + suffix + "'",
                      (ROOT / "app/build.gradle").read_text(encoding="utf-8"))
        self.assertIn("identity['versionName'] + '" + suffix + "'", self.workflow)

    def test_identity_matrix_runs_the_identity_guard_tests(self):
        matrix = self.workflow.split("identity-matrix:", 1)[1].split("\n  build-unsigned:", 1)[0]
        for name in ("tools/test-verify-rebirth-candidate.py", "tools/test-release-identity.py",
                     "tools/test-rebirth-candidate-admission.py", "tools/test-rebirth-unsigned-workflow.py"):
            self.assertIn(name, matrix)


if __name__ == "__main__":
    unittest.main()
