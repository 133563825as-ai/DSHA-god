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
VALIDATION_BASELINE = 'db59aed4b10b0e4eca9ceee2f2f0b8befcc5c2c0'
SOFTWARE_RUN = 43692535293
KEY_ASSETS = ('builtin-plugins/dsh-web-mobile/lib/client.js',
              'app-integration/client.js', 'dsh-runtime.bin')
CHUNK = 1024 * 1024
PHASES = frozenset(('INPUT_IDENTITY', 'BADGING', 'MANIFEST', 'ZIP', 'ASSETS',
                    'ZIP_BOUNDARY', 'SIGNATURE', 'SUBSCRIPT_UPGRADE', 'RECOVERY',
                    'MANAGED', 'THIRD_PARTY', 'SOURCE_CLEAN', 'DELIVERY', 'COMPLETE'))
_PHASE = 'INPUT_IDENTITY'
SAFE_REASONS = {value: value for value in (
    'REBIRTH_BADGING_IDENTITY', 'REBIRTH_LABEL_OR_DEBUGGABLE', 'REBIRTH_LOCALIZED_LABEL',
    'REBIRTH_ABI_IDENTITY', 'REBIRTH_MANIFEST_PACKAGE', 'REBIRTH_MANIFEST_AUTHORITIES',
    'REBIRTH_MANIFEST_ENTRY', 'REBIRTH_MANIFEST_LEGACY_IDENTITY', 'REBIRTH_ZIP_EOCD',
    'REBIRTH_ZIP_CENTRAL_DIRECTORY', 'REBIRTH_UNEXPECTED_APK_SIGNING_BLOCK',
    'REBIRTH_DUPLICATE_ZIP_MEMBER', 'REBIRTH_UNEXPECTED_V1_SIGNATURE', 'REBIRTH_ZIP_ABI_IDENTITY',
    'REBIRTH_DESCRIPTOR_SIZE', 'REBIRTH_DESCRIPTOR_SOURCE', 'REBIRTH_RUNTIME_IDENTITY',
    'REBIRTH_UNSIGNED_VERIFIER_UNEXPECTED_RESULT', 'REBIRTH_OUTPUT_IDENTITY',
    'REBIRTH_UNSIGNED_OUTPUT_PATH', 'REBIRTH_APKSIGNER_UNAVAILABLE',
    'REBIRTH_TRACKED_SOURCE_CHANGED', 'REBIRTH_DELIVERY_EXISTS', 'REBIRTH_DELIVERY_DIGEST',
    'REBIRTH_NOTICE_INVALID')}
SAFE_REASONS.update({
    'REBIRTH_PLATFORM_IDENTITY:sdkVersion': 'REBIRTH_PLATFORM_SDK',
    'REBIRTH_PLATFORM_IDENTITY:targetSdkVersion': 'REBIRTH_PLATFORM_TARGET_SDK',
    'REBIRTH_KEY_ASSET_DIGEST:builtin-plugins/dsh-web-mobile/lib/client.js': 'REBIRTH_MOBILE_DIGEST',
    'REBIRTH_KEY_ASSET_DIGEST:app-integration/client.js': 'REBIRTH_INTEGRATION_DIGEST',
    'REBIRTH_KEY_ASSET_DIGEST:dsh-runtime.bin': 'REBIRTH_RUNTIME_ARCHIVE_DIGEST',
})


def phase(value):
    global _PHASE
    if value not in PHASES:
        raise ValueError('INVALID_DIAGNOSTIC_PHASE')
    _PHASE = value


def diagnostic_reason(error):
    if isinstance(error, ValueError) and len(error.args) == 1 and type(error.args[0]) is str:
        return SAFE_REASONS.get(error.args[0], 'STEP_FAILED')
    return 'STEP_FAILED'


def run_with_diagnostic():
    phase('INPUT_IDENTITY')
    try:
        main()
    except SystemExit as error:
        if error.code is not None and error.code != 0:
            print('::error::gate=unsigned-candidate-check phase=' + _PHASE
                  + ' reason=STEP_FAILED', flush=True)
        raise
    except BaseException as error:
        print('::error::gate=unsigned-candidate-check phase=' + _PHASE
              + ' reason=' + diagnostic_reason(error), flush=True)
        raise
    else:
        phase('COMPLETE')


def stream_digest(stream):
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(CHUNK), b''):
        digest.update(block)
    return digest.hexdigest()


def file_digest(path):
    with path.open('rb') as stream:
        return stream_digest(stream)


def sdk_rows(badging, key):
    aliases = {'sdkVersion': ('sdkVersion', 'minSdkVersion'),
               'targetSdkVersion': ('targetSdkVersion',)}[key]
    rows = []
    for row in badging.splitlines():
        stripped = row.lstrip(' \t')
        for alias in aliases:
            if stripped.startswith(alias + ':'):
                rows.append((alias, row, stripped[len(alias) + 1:]))
    return rows


def sdk_observation(badging, key):
    expected = {'sdkVersion': '30', 'targetSdkVersion': '37'}[key]
    # min SDK两别名合并计数，不能让合法行掩盖额外畸形行。
    rows = sdk_rows(badging, key)
    count = 'ZERO' if not rows else 'ONE' if len(rows) == 1 else 'MANY'
    shape, value = 'OTHER', 'UNPARSEABLE'
    if len(rows) == 1:
        payload = rows[0][2].strip(' \t\r')
        quoted = re.fullmatch(r"'([0-9]+)'", payload)
        integer = re.fullmatch(r'[0-9]+', payload)
        trailing = re.fullmatch(r"'([0-9]+)'[ \t]+[^ \t].*", payload)
        if quoted:
            shape, decimal = 'LEGACY_QUOTED_DECIMAL', quoted.group(1)
        elif integer:
            shape, decimal = 'COLON_INT', payload
        elif trailing:
            shape, decimal = 'QUOTED_WITH_TRAILING_FIELD', trailing.group(1)
        else:
            decimal = None
        if decimal is not None:
            value = 'EXPECTED' if decimal == expected else 'OTHER_DECIMAL'
    return dict(field=rows[0][0] if len(rows) == 1 else key, count=count, shape=shape, value=value)


def print_sdk_observation(badging, key):
    observed = sdk_observation(badging, key)
    print('::error::gate=unsigned-candidate-sdk field=' + observed['field']
          + ' count=' + observed['count'] + ' shape=' + observed['shape']
          + ' value=' + observed['value'], flush=True)


def verify_badging(badging):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not package or package.group(1) != PACKAGE or package.group(2) != '359' or not package.group(3).endswith('-Rebirth-Sprout.1'):
        raise ValueError('REBIRTH_BADGING_IDENTITY')
    if 'application-debuggable' in badging:
        raise ValueError('REBIRTH_LABEL_OR_DEBUGGABLE')
    if "application-label:'DSHA 新生'" not in badging:
        raise ValueError('REBIRTH_LABEL_OR_DEBUGGABLE')
    labels = re.findall(r"^application-label(?:-[^:]+)?:'([^']*)'", badging, re.M)
    if not labels or any(label != 'DSHA 新生' for label in labels):
        raise ValueError('REBIRTH_LOCALIZED_LABEL')
    for key, expected in (('sdkVersion', '30'), ('targetSdkVersion', '37')):
        rows = sdk_rows(badging, key)
        if len(rows) != 1 or re.fullmatch(re.escape(rows[0][0]) + ":'" + expected + r"'[ \t]*", rows[0][1]) is None:
            print_sdk_observation(badging, key)
            raise ValueError('REBIRTH_PLATFORM_IDENTITY:' + key)
    native = re.findall(r'^native-code:(.*)$', badging, re.M)
    if len(native) != 1 or re.fullmatch(r"\s*'arm64-v8a'\s*", native[0]) is None:
        raise ValueError('REBIRTH_ABI_IDENTITY')
    return package.group(3)


def verify_manifest(manifest):
    if re.findall(r'\bA: package="([^"]*)"', manifest) != [PACKAGE]:
        raise ValueError('REBIRTH_MANIFEST_PACKAGE')
    authorities = re.findall(r'android:authorities(?:\([^)]*\))?="([^"]*)"', manifest)
    expected = {PACKAGE + '.' + suffix for suffix in ('updates', 'shizuku', 'documents')}
    if len(authorities) != 3 or set(authorities) != expected:
        raise ValueError('REBIRTH_MANIFEST_AUTHORITIES')
    affinities = re.findall(r'android:taskAffinity(?:\([^)]*\))?="([^"]*)"', manifest)
    if affinities != [PACKAGE + '.entry']:
        raise ValueError('REBIRTH_MANIFEST_ENTRY')
    # 包名值必须隔离；Java类名com.deepseekharness.app不属于applicationId残留。
    candidate_values = expected | {PACKAGE, PACKAGE + '.entry',
                                   PACKAGE + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'}
    for value in re.findall(r'="([^"]*)"', manifest):
        if 'io.github.sunsetrne.dsha' in value:
            raise ValueError('REBIRTH_MANIFEST_LEGACY_IDENTITY')
        identities = re.findall(r'dsh\.android\.client[\w.]*', value)
        if any(identity not in candidate_values for identity in identities):
            raise ValueError('REBIRTH_MANIFEST_LEGACY_IDENTITY')


def unsigned_zip_boundary(apk):
    phase('ZIP_BOUNDARY')
    # 仅读取EOCD有界尾部，不把数百MiB APK再次载入内存。
    size = apk.stat().st_size
    with apk.open('rb') as stream:
        start = max(0, size - 65557)
        stream.seek(start)
        tail = stream.read(65557)
        at = tail.rfind(b'PK\x05\x06')
        if at < 0 or at + 22 > len(tail) or at + 22 + int.from_bytes(tail[at + 20:at + 22], 'little') != len(tail):
            raise ValueError('REBIRTH_ZIP_EOCD')
        if tail[at + 4:at + 8] != b'\0' * 4:
            raise ValueError('REBIRTH_ZIP_CENTRAL_DIRECTORY')
        central = int.from_bytes(tail[at + 16:at + 20], 'little')
        if central < 24 or central > start + at or central == 0xffffffff:
            raise ValueError('REBIRTH_ZIP_CENTRAL_DIRECTORY')
        stream.seek(central - 16)
        if stream.read(16) == b'APK Sig Block 42':
            raise ValueError('REBIRTH_UNEXPECTED_APK_SIGNING_BLOCK')


def inspect_apk(apk, root=ROOT):
    phase('ZIP')
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('REBIRTH_DUPLICATE_ZIP_MEMBER')
        if any(name.upper().startswith('META-INF/') and name.upper().endswith(('.RSA', '.DSA', '.EC', '.SF')) for name in names):
            raise ValueError('REBIRTH_UNEXPECTED_V1_SIGNATURE')
        abis = {name.split('/')[1] for name in names if name.startswith('lib/') and not name.endswith('/')}
        if abis != {'arm64-v8a'}:
            raise ValueError('REBIRTH_ZIP_ABI_IDENTITY')
        phase('ASSETS')
        descriptor_name = 'assets/runtime-descriptor.json'
        if archive.getinfo(descriptor_name).file_size > 2 * CHUNK:
            raise ValueError('REBIRTH_DESCRIPTOR_SIZE')
        raw = archive.read(descriptor_name)
        if raw != (root / 'app/src/main/assets/runtime-descriptor.json').read_bytes():
            raise ValueError('REBIRTH_DESCRIPTOR_SOURCE')
        descriptor = json.loads(raw)
        contract = {key: value for key, value in descriptor.items() if key not in ('runtimeId', 'version')}
        identity = hashlib.sha256(json.dumps(contract, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        if descriptor.get('version') != 1 or descriptor.get('runtimeId') != identity:
            raise ValueError('REBIRTH_RUNTIME_IDENTITY')
        assets = {}
        for name in KEY_ASSETS:
            with archive.open('assets/' + name) as stream:
                actual = stream_digest(stream)
            if actual != descriptor['inputs'].get(name):
                raise ValueError('REBIRTH_KEY_ASSET_DIGEST:' + name)
            assets[name] = actual
    unsigned_zip_boundary(apk)
    return dict(actualRuntimeId=identity, actualAssetSha256=assets,
                descriptorSha256=hashlib.sha256(raw).hexdigest(), allAbi=['arm64-v8a'])


def require_unsigned_verifier(verification):
    if verification.returncode != 1 or 'DOES NOT VERIFY' not in verification.stdout + verification.stderr:
        raise ValueError('REBIRTH_UNSIGNED_VERIFIER_UNEXPECTED_RESULT')


def run(argv):
    return subprocess.check_output([str(value) for value in argv], cwd=ROOT, text=True)


def success_notice(receipt):
    if (receipt.get('signed') is not False or receipt.get('signature') is not None
            or 'signature' not in receipt or receipt.get('installable') is not False
            or receipt.get('signatureStatus') != 'NOT_SIGNED'
            or type(receipt.get('bytes')) is not int or receipt['bytes'] <= 0):
        raise ValueError('REBIRTH_NOTICE_INVALID')
    for name, length in (('sourceCommit', 40), ('sha256', 64), ('actualRuntimeId', 64)):
        value = receipt.get(name)
        if type(value) is not str or re.fullmatch('[a-f0-9]{' + str(length) + '}', value) is None:
            raise ValueError('REBIRTH_NOTICE_INVALID')
    print('::notice::status=VERIFIED_UNSIGNED_NOT_INSTALLABLE sourceCommit=' + receipt['sourceCommit']
          + ' bytes=' + str(receipt['bytes']) + ' sha256=' + receipt['sha256']
          + ' actualRuntimeId=' + receipt['actualRuntimeId'], flush=True)


def main():
    phase('INPUT_IDENTITY')
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
    phase('BADGING')
    badging = run([args.build_tools / 'aapt2', 'dump', 'badging', apk])
    version_name = verify_badging(badging)
    phase('MANIFEST')
    manifest = run([args.build_tools / 'aapt2', 'dump', 'xmltree', apk, '--file', 'AndroidManifest.xml'])
    verify_manifest(manifest)
    actual = inspect_apk(apk)
    phase('SIGNATURE')
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
    require_unsigned_verifier(verification)
    (args.output / 'apk-badging.txt').write_text(badging, encoding='utf-8')
    (args.output / 'apk-manifest.txt').write_text(manifest, encoding='utf-8')
    for script in ('verify-dsh-upgrade-apk.py', 'verify-recovery-apk.py', 'verify-apk-managed-assets.py'):
        phase({'verify-dsh-upgrade-apk.py': 'SUBSCRIPT_UPGRADE',
               'verify-recovery-apk.py': 'RECOVERY',
               'verify-apk-managed-assets.py': 'MANAGED'}[script])
        result = run(['python3', 'tools/' + script, apk])
        (args.output / (script + '.log')).write_text(result, encoding='utf-8')
    phase('THIRD_PARTY')
    run(['python3', 'tools/verify-third-party-apk.py', apk, '--output', args.output / 'third-party-apk.json'])
    phase('SOURCE_CLEAN')
    commit = run(['git', 'rev-parse', 'HEAD']).strip()
    if run(['git', 'status', '--porcelain', '--untracked-files=no']).strip():
        raise ValueError('REBIRTH_TRACKED_SOURCE_CHANGED')
    phase('DELIVERY')
    digest = file_digest(apk)
    target = args.output / ('DSHA-Rebirth-Sprout-b359-' + commit[:12] + '-unsigned.apk')
    if target.exists():
        raise ValueError('REBIRTH_DELIVERY_EXISTS')
    shutil.copyfile(apk, target)
    if file_digest(target) != digest:
        raise ValueError('REBIRTH_DELIVERY_DIGEST')
    target.with_suffix('.apk.sha256').write_text(digest + '  ' + target.name + '\n', encoding='utf-8')
    receipt = dict(schema=1, kind='UNSIGNED_NOT_FOR_INSTALLATION', sourceCommit=commit,
                   sourceBaseline='92acaa0408cb7b031ee3036c6116c6463ae6b62e',
                   validationBaseline=VALIDATION_BASELINE, softwareRun=SOFTWARE_RUN,
                   packageName=PACKAGE, appName='DSHA 新生', versionCode=359,
                   versionName=version_name, bytes=target.stat().st_size, sha256=digest,
                   signed=False, signature=None, signatureStatus='NOT_SIGNED', installable=False, installed=False, device='NOT_RUN', published=False)
    receipt.update(actual)
    (args.output / 'candidate-receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(receipt, ensure_ascii=False))
    success_notice(receipt)


if __name__ == '__main__':
    run_with_diagnostic()
