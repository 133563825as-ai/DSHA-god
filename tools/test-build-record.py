#!/usr/bin/env python3
"""测试版/稳定版留痕记录：为一个版本生成带时间的摘要清单（只在本机计算，不联网、不上传）。

用法：
  python3 tools/test-build-record.py --build 1 [--channel stable] --apk <安装包.apk> --out <清单目录>

产出（都在 --out 下）：
  t<N>|s<N>-source.tar.gz   该提交的源码归档（git archive，内容由提交唯一决定）
  t<N>|s<N>-record.json          提交、标签、提交时间、记录时间、APK 与源码归档的 SHA-256、APK 内署名
清单本身也算一次摘要（record 文件的 SHA-256 打印在终端），可另行抄存或交公证/时间戳服务。
"""
import argparse
import datetime
import hashlib
import json
import pathlib
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--build', required=True, type=int)
    parser.add_argument('--channel', choices=['test', 'stable'], default='test')
    parser.add_argument('--apk', required=True, type=pathlib.Path)
    parser.add_argument('--out', required=True, type=pathlib.Path)
    args = parser.parse_args()
    if args.build < 1 or not args.apk.is_file():
        sys.exit('参数无效')
    if git('status', '--porcelain', '--untracked-files=no'):
        sys.exit('工作树有未提交的改动：先提交，再生成记录（记录必须对应一个确定的提交）')
    name = ('s%d' if args.channel == 'stable' else 't%d') % args.build
    commit = git('rev-parse', 'HEAD')
    args.out.mkdir(parents=True, exist_ok=True)
    source = args.out / (name + '-source.tar.gz')
    subprocess.run(['git', 'archive', '--format=tar.gz', '-o', str(source), commit], cwd=ROOT, check=True)
    # APK 内的署名来自 BuildConfig；在 dex 里按 UTF-8 查找，确认这个包确实带着它。
    with zipfile.ZipFile(args.apk) as apk:
        dex = b''.join(apk.read(n) for n in apk.namelist() if n.startswith('classes') and n.endswith('.dex'))
    record = {
        'channel': args.channel,
        'build': name,
        'signature': '南街小一',
        'signatureInApk': '南街小一'.encode('utf-8') in dex,
        'buildLabelInApk': name.encode('ascii') in dex,
        'commit': commit,
        'commitTime': git('show', '-s', '--format=%cI', commit),
        'tags': git('tag', '--points-at', commit).split(),
        'recordedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='seconds'),
        'apk': {'file': args.apk.name, 'bytes': args.apk.stat().st_size, 'sha256': sha256(args.apk)},
        'source': {'file': source.name, 'bytes': source.stat().st_size, 'sha256': sha256(source)},
        'license': 'MIT（衍生自 DSH-APP/DSHA，Copyright (c) 2026 qiannianhuanxiang；原版权与许可声明保留）',
    }
    out = args.out / (name + '-record.json')
    out.write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(out, sha256(out))
    if not record['signatureInApk']:
        sys.exit('APK 里没有找到署名：确认用 -Pdsha.sunsetTest=true 或 -Pdsha.stableBuild=N 打的包')


if __name__ == '__main__':
    main()
