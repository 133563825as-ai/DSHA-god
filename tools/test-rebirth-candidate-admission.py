#!/usr/bin/env python3
"""同run新门禁准入与版本/签名入口合成测试；不运行Gradle或SDK工具。"""
import copy
import importlib.util
from pathlib import Path
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


admission = load("rebirth_admission", "tools/rebirth_candidate_admission.py")
identity = load("rebirth_identity_test", "tools/release-identity.py")
signer = load("rebirth_signer_test", "tools/sign-release-apks.py")


class Admission(unittest.TestCase):
    def setUp(self):
        self.sha = "a" * 40
        self.env = {"GITHUB_REPOSITORY": admission.REPOSITORY, "GITHUB_REF": admission.REF,
                    "GITHUB_SHA": self.sha, "GITHUB_EVENT_NAME": "workflow_dispatch",
                    "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1"}
        self.needs = {key: {"result": "success"} for key in admission.REQUIRED_JOBS}

    def test_only_current_successful_jobs_admit(self):
        value = admission.admit(self.env, self.sha, self.needs)
        self.assertFalse(value["historicalBaselineReused"])
        self.assertEqual(value["commit"], self.sha)
        self.assertFalse(value["signed"])

    def test_rerun_cannot_relabel_previous_attempt_success(self):
        for attempt in ("2", "3", "999", "01"):
            with self.subTest(attempt=attempt), self.assertRaisesRegex(
                    ValueError, "REBIRTH_ADMISSION_(FRESH_RUN_REQUIRED|RUN)"):
                admission.admit(dict(self.env, GITHUB_RUN_ATTEMPT=attempt), self.sha, self.needs)
        self.assertEqual(admission.admit(self.env, self.sha, self.needs)["runAttempt"], "1")

    def test_wrong_transport_or_historical_sha_never_admits(self):
        for key, bad in (("GITHUB_SHA", "b" * 40), ("GITHUB_REF", "refs/heads/main"),
                         ("GITHUB_REPOSITORY", "other/repo"), ("GITHUB_EVENT_NAME", "push"),
                         ("GITHUB_RUN_ID", ""), ("GITHUB_RUN_ATTEMPT", "0")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                admission.admit(dict(self.env, **{key: bad}), self.sha, self.needs)

    def test_missing_extra_failed_or_skipped_gate_rejects(self):
        for result in ("failure", "skipped", "cancelled", ""):
            needs = copy.deepcopy(self.needs)
            needs["validation"]["result"] = result
            with self.subTest(result=result), self.assertRaises(ValueError):
                admission.admit(self.env, self.sha, needs)
        for needs in ({}, dict(self.needs, old_baseline={"result": "success"})):
            with self.assertRaises(ValueError):
                admission.admit(self.env, self.sha, needs)

    def test_effective_version_is_identity_specific_and_strict(self):
        rebirth = identity.load("rebirth")
        expected = identity.effective_version(rebirth)
        self.assertEqual(expected["versionName"], rebirth["versionName"] + "-Rebirth-Sprout.2")
        self.assertEqual(signer.source_version(selected=rebirth), expected)
        for name in ("mod", "upstream"):
            value = identity.load(name)
            self.assertEqual(identity.effective_version(value)["versionName"], value["versionName"])
        values = {"packageName": rebirth["packageName"], **expected, "minSdk": 30,
                  "nativeCode": "arm64-v8a", "debuggable": False}
        signer.check_manifest(values, rebirth, expected)
        for version in (rebirth["versionName"], expected["versionName"] + "-extra"):
            with self.assertRaises(SystemExit):
                signer.check_manifest(dict(values, versionName=version), rebirth, expected)

    def test_rebirth_signing_is_rejected_before_sdk_or_key_access(self):
        with mock.patch.object(signer, "sdk_tools", side_effect=AssertionError("SDK accessed")), \
                mock.patch.object(signer, "sign", side_effect=AssertionError("key accessed")):
            with self.assertRaisesRegex(SystemExit, "REBIRTH_OFFLINE_SIGNING_ONLY"):
                signer.main(["--apk", "missing-unsigned.apk", "--identity", "rebirth"])


if __name__ == "__main__":
    unittest.main()
