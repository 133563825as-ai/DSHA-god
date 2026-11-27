#!/usr/bin/env python3
"""当前同一次 workflow run 的软件门禁准入；不复用历史成功基线、不联网。"""
import argparse
import json
import os
from pathlib import Path
import re

REPOSITORY = "133563825as-ai/DSHA-god"
REF = "refs/heads/sync/rebirth-latest-20261116"
REQUIRED_JOBS = frozenset({"bind", "inputs", "validation", "identity-matrix"})


def validate_transport(env, expected_sha):
    if not re.fullmatch(r"[0-9a-f]{40}", expected_sha or ""):
        raise ValueError("REBIRTH_ADMISSION_SHA")
    expected = {"GITHUB_REPOSITORY": REPOSITORY, "GITHUB_REF": REF,
                "GITHUB_SHA": expected_sha, "GITHUB_EVENT_NAME": "workflow_dispatch"}
    if any(env.get(key) != value for key, value in expected.items()):
        raise ValueError("REBIRTH_ADMISSION_TRANSPORT")
    for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
        if not re.fullmatch(r"[1-9][0-9]*", env.get(key, "")):
            raise ValueError("REBIRTH_ADMISSION_RUN")
    # needs.result 不携带各 gate 的 attempt 身份；后续 attempt 可能沿用旧成功 job。
    # 在尚无逐 gate 证据协议时只允许完整新 run，禁止部分/全部 rerun 冒充新鲜门禁。
    if env["GITHUB_RUN_ATTEMPT"] != "1":
        raise ValueError("REBIRTH_ADMISSION_FRESH_RUN_REQUIRED")


def admit(env, expected_sha, needs):
    validate_transport(env, expected_sha)
    if not isinstance(needs, dict) or set(needs) != REQUIRED_JOBS:
        raise ValueError("REBIRTH_ADMISSION_JOB_SET")
    if any(not isinstance(row, dict) or row.get("result") != "success" for row in needs.values()):
        raise ValueError("REBIRTH_ADMISSION_JOB_NOT_SUCCESS")
    return {"schema": 1, "kind": "rebirth-same-run-software-admission", "commit": expected_sha,
            "repository": REPOSITORY, "ref": REF, "runId": env["GITHUB_RUN_ID"],
            "runAttempt": env["GITHUB_RUN_ATTEMPT"], "jobs": sorted(REQUIRED_JOBS),
            "status": "PASS", "historicalBaselineReused": False, "rustApkMode": "off",
            "installable": False, "signed": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = admit(os.environ, args.expected_sha, json.loads(os.environ["NEEDS_JSON"]))
    except (ValueError, KeyError) as error:
        raise SystemExit(str(error)) from None
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
