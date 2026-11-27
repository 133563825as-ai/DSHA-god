#!/usr/bin/env python3
"""嫩芽宿主测试的固定 jar；仅 --prepare 下载，--check 始终离线。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
import tempfile
from typing import NamedTuple
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
FRAMEWORK_PATH = Path('app/build/sprout-host-jars/android-all-14-robolectric-10818077.jar')
FRAMEWORK_URL = ('https://repo.maven.apache.org/maven2/org/robolectric/android-all/'
                 '14-robolectric-10818077/android-all-14-robolectric-10818077.jar')
# 公开 .jar.sha256 / .jar.sha1 已独立核对；运行时绝不下载校验值来建立信任。
FRAMEWORK_SHA256 = '6be2218c6a53fe3c57bc22ebdc723edcb7270a8a6f187545708aa5c0ed813977'
SNAKEYAML_PATH = Path('app/build/backup-dependencies/snakeyaml-2.4.jar')
SNAKEYAML_URL = 'https://repo.maven.apache.org/maven2/org/yaml/snakeyaml/2.4/snakeyaml-2.4.jar'
CHUNK_SIZE = 1024 * 1024


class Jar(NamedTuple):
    name: str
    path: Path
    url: str
    sha256: str
    max_bytes: int


def jar_specs(root=ROOT):
    lock = json.loads((root / 'tools/backup-dependencies.lock.json').read_text(encoding='utf-8'))
    entries = [item for item in lock['dependencies']
               if item.get('group') == 'org.yaml' and item.get('name') == 'snakeyaml']
    if lock.get('version') != 1 or len(entries) != 1:
        raise ValueError('SnakeYAML lock 必须为 version 1 且有唯一 org.yaml:snakeyaml')
    entry = entries[0]
    if (entry.get('version') != '2.4' or entry.get('artifact') != SNAKEYAML_URL
            or not re.fullmatch(r'[0-9a-f]{64}', entry.get('sha256', ''))):
        raise ValueError('SnakeYAML lock 的版本、固定 URL 或 SHA-256 无效')
    return {
        'framework': Jar('framework', root / FRAMEWORK_PATH, FRAMEWORK_URL,
                         FRAMEWORK_SHA256, 160 * 1024 * 1024),
        'snakeyaml': Jar('snakeyaml', root / SNAKEYAML_PATH, SNAKEYAML_URL,
                         entry['sha256'], 1024 * 1024),
    }


def stream_digest(source, limit, destination=None):
    digest = hashlib.sha256()
    total = 0
    while True:
        chunk = source.read(min(CHUNK_SIZE, limit - total + 1))
        if not chunk:
            return digest.hexdigest()
        total += len(chunk)
        if total > limit:
            raise ValueError(f'jar 超过大小限额 {limit} bytes')
        digest.update(chunk)
        if destination is not None:
            destination.write(chunk)


def check_jar(jar):
    if jar.path.is_symlink():
        raise ValueError(f'{jar.name}: 拒绝符号链接 {jar.path}')
    if not jar.path.exists():
        raise ValueError(f'{jar.name}: 缺少 {jar.path}；请显式运行 '
                         f'python3 tools/prepare-sprout-host-jars.py --prepare --jar {jar.name}')
    if not jar.path.is_file():
        raise ValueError(f'{jar.name}: 不是普通文件 {jar.path}')
    with jar.path.open('rb') as source:
        actual = stream_digest(source, jar.max_bytes)
    if actual != jar.sha256:
        raise ValueError(f'{jar.name}: SHA-256 不匹配 {jar.path} '
                         f'(expected {jar.sha256}, actual {actual})；拒绝覆盖，请人工检查')
    return jar.path


def prepare_jar(jar):
    if jar.path.exists() or jar.path.is_symlink():
        return check_jar(jar)
    jar.path.parent.mkdir(parents=True, exist_ok=True)
    # 只清理本次创建的临时文件，不查找、复用或删除未知 partial。
    with tempfile.TemporaryFile(dir=jar.path.parent) as temporary:
        with urlopen(jar.url, timeout=60) as response:
            actual = stream_digest(response, jar.max_bytes, temporary)
        if actual != jar.sha256:
            raise ValueError(f'{jar.name}: 下载 SHA-256 不匹配 '
                             f'(expected {jar.sha256}, actual {actual})；未发布 {jar.path}')
        temporary.seek(0)
        # 排他创建；即使下载期间出现同名文件也绝不覆盖。
        with jar.path.open('xb') as destination:
            shutil.copyfileobj(temporary, destination, CHUNK_SIZE)
    return check_jar(jar)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--prepare', action='store_true', help='显式联网准备固定 jar')
    mode.add_argument('--check', action='store_true', help='只校验本地文件，不联网、不写文件')
    parser.add_argument('--jar', choices=('all', 'framework', 'snakeyaml'), default='all')
    args = parser.parse_args(argv)
    try:
        specs = jar_specs()
        selected = specs.values() if args.jar == 'all' else [specs[args.jar]]
        for jar in selected:
            path = prepare_jar(jar) if args.prepare else check_jar(jar)
            print(path)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f'sprout-host-jars: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
