#!/usr/bin/env python3
"""复用固定成功软件基线；只核公开CI证据与精确包检查/CI改动，不执行构建。"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BASE = 'db59aed4b10b0e4eca9ceee2f2f0b8befcc5c2c0'
RUN = 43692535293
REPO = '133563825as-ai/DSHA-god'
BRANCH = 'validate/rebirth-unsigned-20261115'
ALLOWED = frozenset({
    '.github/workflows/rebirth-unsigned-candidate.yml',
    'tools/check-rebirth-package-admission.py', 'tools/test-rebirth-unsigned-workflow.py',
    '.github/workflows/ci-fast.yml', '.github/workflows/docs-check.yml',
    'tools/host-tests.manifest.json', 'tools/rebirth-apk-check.py', 'tools/test-rebirth-apk-check.py',
})
REQUIRED_STEPS = frozenset({
    'Current frozen binary inputs and native x64 host fixtures',
    'Trusted Ubuntu Gradle task graph fence and host dependency preparation',
    'L1 identity and source checks', 'L1 complete fast gates', 'L2 locked Robolectric inputs',
    'L2 complete unit tests', 'L2 complete unit XML verification', 'L2 Lint',
    'L2 resource source map', 'L3 locked historical input', 'L3 complete runtime gates', 'L3 software gates',
})


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def changed_paths(data):
    paths = data.decode('utf-8').split('\0')
    paths = [p for p in paths if p]
    if not set(paths) <= ALLOWED:
        raise ValueError('PACKAGE_BASELINE_SOURCE_CHANGED')
    return sorted(paths)


def validate_run(value):
    if (value.get('id') != RUN or value.get('head_sha') != BASE or value.get('status') != 'completed'
            or value.get('conclusion') != 'success' or value.get('event') != 'push'
            or value.get('head_branch') != 'validate/rebirth-rc1-20261114'
            or value.get('path') != '.github/workflows/rebirth-validation.yml'
            or value.get('workflow_id') != 418225569 or value.get('name') != 'Rebirth RC1 Ubuntu tests only'
            or value.get('repository', {}).get('full_name') != REPO):
        raise ValueError('PACKAGE_SOFTWARE_BASELINE_NOT_SUCCESSFUL')


def validate_jobs(value):
    jobs = value.get('jobs')
    if value.get('total_count') != 1 or not isinstance(jobs, list) or len(jobs) != 1:
        raise ValueError('PACKAGE_SOFTWARE_JOB_SCOPE')
    job = jobs[0]
    if (job.get('id') != 132745996960 or job.get('name') != 'gates' or job.get('run_id') != RUN
            or job.get('head_sha') != BASE or job.get('status') != 'completed' or job.get('conclusion') != 'success'):
        raise ValueError('PACKAGE_SOFTWARE_JOB_FAILED')
    selected = [step.get('name') for step in job.get('steps', [])
                if step.get('status') == 'completed' and step.get('conclusion') == 'success']
    if any(selected.count(name) != 1 for name in REQUIRED_STEPS):
        raise ValueError('PACKAGE_SOFTWARE_REQUIRED_STEP_NOT_PASSED')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError('PACKAGE_ADMISSION_REDIRECT_REFUSED')


def public_json(suffix):
    url = f'https://api.github.com/repos/{REPO}/actions/runs/{RUN}' + suffix
    request = urllib.request.Request(url, headers={'Accept': 'application/vnd.github+json',
        'User-Agent': 'DSHA-Rebirth-Unsigned-Admission', 'X-GitHub-Api-Version': '2022-11-28'})
    # 单次公开GET，无Authorization、无token读取、无重试/镜像/权限升级。
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=30) as response:
        body = response.read(2 * 1024 * 1024 + 1)
    if len(body) > 2 * 1024 * 1024:
        raise ValueError('PACKAGE_ADMISSION_RESPONSE_LIMIT')
    value = json.loads(body)
    if not isinstance(value, dict):
        raise ValueError('PACKAGE_ADMISSION_RESPONSE_SHAPE')
    return value


def validate_transport(environment, head):
    if (environment.get('GITHUB_EVENT_NAME') != 'push' or environment.get('GITHUB_REPOSITORY') != REPO
            or environment.get('GITHUB_REF') != 'refs/heads/' + BRANCH
            or environment.get('GITHUB_SHA') != head or len(head) != 40
            or any(c not in '0123456789abcdef' for c in head)):
        raise ValueError('PACKAGE_ADMISSION_TRANSPORT')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    head = git('rev-parse', 'HEAD').decode().strip()
    validate_transport(os.environ, head)
    if git('status', '--porcelain', '--untracked-files=no').strip():
        raise ValueError('PACKAGE_ADMISSION_DIRTY_TRACKED')
    subprocess.run(['git', 'merge-base', '--is-ancestor', BASE, 'HEAD'], cwd=ROOT, check=True)
    changed = changed_paths(git('diff', '--name-only', '--no-renames', '-z', BASE, 'HEAD'))
    run = public_json(''); validate_run(run)
    jobs = public_json('/jobs?per_page=100'); validate_jobs(jobs)
    receipt = {'schema': 1, 'status': 'ADMITTED_UNSIGNED_PACKAGE_ONLY',
        'sourceCommit': head, 'softwareBaseCommit': BASE, 'softwareRun': RUN,
        'changedPaths': changed, 'scope': 'CI/check-only delta; prior exact production software evidence reused, not rerun',
        'signed': False, 'installed': False, 'published': False}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(receipt, indent=2) + '\n')
    print('PASS fixed successful software baseline; unsigned package only')


if __name__ == '__main__':
    main()
