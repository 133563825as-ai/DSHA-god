#!/usr/bin/env python3
"""Verify the actual isolated unsigned Sprout APK; no signing, installation or release."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'dsh.android.client.rebirth'


def run(argv):
    return subprocess.check_output([str(value) for value in argv], cwd=ROOT, text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    folder = ROOT / 'app/build/outputs/apk/standard/release'
    metadata = json.loads((folder / 'output-metadata.json').read_text(encoding='utf-8'))
    if metadata.get('applicationId') != PACKAGE or len(metadata.get('elements', [])) != 1:
        raise ValueError('REBIRTH_OUTPUT_IDENTITY')
    element = metadata['elements'][0]
    name = element['outputFile']
    if Path(name).name != name or not name.endswith('-unsigned.apk'):
        raise ValueError('REBIRTH_UNSIGNED_OUTPUT_PATH')
    apk = folder / name
    badging = run([args.build_tools / 'aapt2', 'dump', 'badging', apk])
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not package or package.group(1) != PACKAGE or package.group(2) != '359' or not package.group(3).endswith('-Rebirth-Sprout.1'):
        raise ValueError('REBIRTH_BADGING_IDENTITY')
    if "application-label:'DSHA 新生'" not in badging or 'application-debuggable' in badging:
        raise ValueError('REBIRTH_LABEL_OR_DEBUGGABLE')
    labels = re.findall(r"^application-label(?:-[^:]+)?:'([^']*)'", badging, re.M)
    if not labels or any(label != 'DSHA 新生' for label in labels):
        raise ValueError('REBIRTH_LOCALIZED_LABEL')
    for expected in ("sdkVersion:'30'", "targetSdkVersion:'37'", "native-code: 'arm64-v8a'"):
        if expected not in badging:
            raise ValueError('REBIRTH_PLATFORM_IDENTITY:' + expected)
    manifest = run([args.build_tools / 'aapt2', 'dump', 'xmltree', apk, '--file', 'AndroidManifest.xml'])
    for suffix in ('updates', 'shizuku', 'documents', 'entry'):
        if PACKAGE + '.' + suffix not in manifest:
            raise ValueError('REBIRTH_MANIFEST_SCOPE:' + suffix)
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if any(name.upper().startswith('META-INF/') and name.upper().endswith(('.RSA', '.DSA', '.EC', '.SF')) for name in names):
            raise ValueError('REBIRTH_UNEXPECTED_V1_SIGNATURE')
    # Verify there is no v2/v3 signing block before ZIP central directory, not merely verifier failure.
    data = apk.read_bytes()
    eocd = data.rfind(b'PK\x05\x06', max(0, len(data) - 65557))
    if eocd < 0 or eocd + 22 > len(data):
        raise ValueError('REBIRTH_ZIP_EOCD')
    central = int.from_bytes(data[eocd + 16:eocd + 20], 'little')
    if central < 24 or central > eocd:
        raise ValueError('REBIRTH_ZIP_CENTRAL_DIRECTORY')
    if data[central - 16:central] == b'APK Sig Block 42':
        raise ValueError('REBIRTH_UNEXPECTED_APK_SIGNING_BLOCK')
    # Tool availability is separate from expected unsigned rejection; retain failures too.
    args.output.mkdir(parents=True, exist_ok=True)
    tool = subprocess.run([str(args.build_tools / 'apksigner'), 'version'], capture_output=True, text=True)
    (args.output / 'apksigner-tool.json').write_text(json.dumps(
        dict(exitCode=tool.returncode, stdout=tool.stdout, stderr=tool.stderr), ensure_ascii=False, indent=2), encoding='utf-8')
    if tool.returncode != 0:
        raise ValueError('REBIRTH_APKSIGNER_UNAVAILABLE')
    # Reject any successful signature verification; unsigned is not an installable signed candidate.
    verification = subprocess.run([str(args.build_tools / 'apksigner'), 'verify', '--verbose', str(apk)], capture_output=True, text=True)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'unsigned-signature-check.json').write_text(json.dumps(
        dict(exitCode=verification.returncode, stdout=verification.stdout, stderr=verification.stderr,
             v1Members=False, apkSigningBlock=False), ensure_ascii=False, indent=2), encoding='utf-8')
    if verification.returncode != 1 or 'DOES NOT VERIFY' not in verification.stdout + verification.stderr:
        raise ValueError('REBIRTH_UNSIGNED_VERIFIER_UNEXPECTED_RESULT')
    (args.output / 'apk-badging.txt').write_text(badging, encoding='utf-8')
    (args.output / 'apk-manifest.txt').write_text(manifest, encoding='utf-8')
    for script in ('verify-dsh-upgrade-apk.py', 'verify-recovery-apk.py', 'verify-apk-managed-assets.py'):
        result = run(['python3', 'tools/' + script, apk])
        (args.output / (script + '.log')).write_text(result, encoding='utf-8')
    run(['python3', 'tools/verify-third-party-apk.py', apk, '--output', args.output / 'third-party-apk.json'])
    commit = run(['git', 'rev-parse', 'HEAD']).strip()
    if run(['git', 'status', '--porcelain', '--untracked-files=no']).strip():
        raise ValueError('REBIRTH_TRACKED_SOURCE_CHANGED')
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    target = args.output / ('DSHA-Rebirth-Sprout-b359-' + commit[:12] + '-unsigned.apk')
    if target.exists():
        raise ValueError('REBIRTH_DELIVERY_EXISTS')
    shutil.copyfile(apk, target)
    if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
        raise ValueError('REBIRTH_DELIVERY_DIGEST')
    target.with_suffix('.apk.sha256').write_text(digest + '  ' + target.name + '\n', encoding='utf-8')
    receipt = dict(schema=1, kind='UNSIGNED_NOT_FOR_INSTALLATION', sourceCommit=commit,
                   sourceBaseline='92acaa0408cb7b031ee3036c6116c6463ae6b62e',
                   packageName=PACKAGE, appName='DSHA 新生', versionCode=359,
                   versionName=package.group(3), bytes=target.stat().st_size, sha256=digest,
                   signed=False, installed=False, device='NOT_RUN', published=False)
    (args.output / 'candidate-receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(receipt, ensure_ascii=False))


if __name__ == '__main__':
    main()
