#!/usr/bin/env python3
"""验证分支专用：固定bundle运二进制，当前提交receipt原字节绑定当前recipe。"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
RECEIPTS = ('dsh-runtime.inputs.json', 'ubuntu-tools.inputs.json')
PREFIX = 'app/src/main/assets/'


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1048576), b''):
            h.update(block)
    return h.hexdigest()


def load(root, name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), root/'tools'/name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def head_receipts(root):
    result = {}
    for name in RECEIPTS:
        path = PREFIX + name
        # 只有已提交HEAD是权威，不能从bundle或本地重算新证明。
        result[name] = subprocess.check_output(['git', 'show', 'HEAD:' + path], cwd=root)
        file = root/path
        if file.is_symlink() or file.read_bytes() != result[name]:
            raise ValueError('VALIDATION_RECEIPT_NOT_HEAD:' + name)
    return result


def validate_receipts(root, saved, recipes, version, binary=False):
    for name in RECEIPTS:
        value = json.loads(saved[name])
        if value.get('inputs') != recipes[name] or not isinstance(value.get('archive_sha256'), str):
            raise ValueError('VALIDATION_RECEIPT_RECIPE:' + name)
        if name == 'dsh-runtime.inputs.json' and value.get('version') != version:
            raise ValueError('VALIDATION_RECEIPT_VERSION')
        if binary:
            archive = root/PREFIX/name.replace('.inputs.json', '.bin')
            if archive.is_symlink() or not archive.is_file() or digest(archive) != value['archive_sha256']:
                raise ValueError('VALIDATION_RECEIPT_ARCHIVE:' + name)


def preserve_restore(root, saved, recipes, version, restore):
    validate_receipts(root, saved, recipes, version)
    # restore必须是当前ci-assets完整bundle digest/member/pin预检；失败不得修receipt掩盖。
    restore()
    validate_receipts(root, saved, recipes, version, binary=True)
    # 两份都严格绑定后，才恢复已提交原字节；绝不生成新inputs或新archive digest。
    for name in RECEIPTS:
        target = root/PREFIX/name
        if target.is_symlink() or not target.is_file():
            raise ValueError('VALIDATION_RECEIPT_TARGET:' + name)
    for name in RECEIPTS:
        (root/PREFIX/name).write_bytes(saved[name])
    return {name: {'headSha256': hashlib.sha256(saved[name]).hexdigest(),
                   'archiveSha256': json.loads(saved[name])['archive_sha256'],
                   'restoredHeadBytes': (root/PREFIX/name).read_bytes() == saved[name]}
            for name in RECEIPTS}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    assets = load(ROOT, 'ci-assets.py')
    assets.require_https(args.url)
    import re
    if not re.fullmatch('[a-f0-9]{64}', args.sha256):
        parser.error('fixed bundle SHA256 required')
    saved = head_receipts(ROOT)
    builder = load(ROOT, 'build-dsh-runtime.py')
    ubuntu = load(ROOT, 'prepare-ubuntu-tools.py')
    recipes = {RECEIPTS[0]: builder.recipe_inputs(), RECEIPTS[1]: ubuntu.inputs()}
    version = json.loads((ROOT/'tools/dsh-runtime/package.json').read_text())['dependencies']['@deepseek-ai/dsh']
    with tempfile.TemporaryDirectory(prefix='validation-inputs-', dir=ROOT/'app') as folder:
        package = Path(folder)/'inputs.zip'
        def restore():
            assets.download(args.url, package)
            assets.restore(package, args.sha256, False, folder)
        result = preserve_restore(ROOT, saved, recipes, version, restore)
    subprocess.run(['git', 'diff', '--exit-code', '--', *[PREFIX + name for name in RECEIPTS]], cwd=ROOT, check=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps({'schema': 1, 'bundleSha256': args.sha256,
        'scope': 'frozen binary pins + current HEAD receipt bytes; no archive rebuild',
        'receipts': result}, indent=2) + '\n')
    print('PASS fixed binary inputs and current HEAD receipts; no stale-archive exception')


if __name__ == '__main__':
    main()
