#!/usr/bin/env python3
"""仅解析三个声明测试的固定终态标记；不输出异常/日志正文，不执行日志。"""
import json
import os
from pathlib import Path
import stat

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = Path('app/build/host-runtime-rebirth-ci')
PREFIX = b'DSHA_TEST_DIAGNOSTIC '
MAX_LOG = 16 * 1024 * 1024
MAX_LINE = 4096
CONTRACTS = {
    'tools/test-build193-patch-chain.py': ('BUILD193_PATCH_CHAIN', frozenset('INPUT_IDENTITY RECIPE_BINDING CURRENT_PYTHON OLD_PYTHON FIXTURE_SERIALIZE JAVA_COMPILE JAVA_EXECUTE RECEIPT_CHECK CLEANUP COMPLETE'.split())),
    'tools/test-mobile-refresh.mjs': ('MOBILE_REFRESH', frozenset('FIXTURE_SETUP BROWSER_LAUNCH BROWSER_CONTEXT BROWSER_NAVIGATE BROWSER_READY FRONTEND_STYLE MODULE_LOAD CARD_TAP UPLOAD_STYLE SCROLL_FRESH_MODULE SCROLL_HOST_POSITION SCROLL_REMOUNT SESSION_LOG DESKTOP_BOUNDARY PAGE_ERRORS CLEANUP COMPLETE'.split())),
    'tools/test-recovery-profile-boot.mjs': ('RECOVERY_PROFILE', frozenset('FIXTURE_SETUP PROFILE_START PROFILE_READY AUTH_URL AUTH_HANDSHAKE AUTH_HTML BROWSER_LAUNCH BROWSER_CONTEXT BROWSER_NAVIGATE WORKBENCH_DISMISS WORKSPACE_READY COMPOSER_EDIT SESSION_OBSERVATION REQUEST_EXTENSION CLIENT_ERRORS REPORT_WRITE TEMP_BROWSER_STORAGE NORMAL_STOP NO_MODEL_REQUEST TEMP_FILESYSTEM CLEANUP COMPLETE'.split())),
}
STATES = frozenset({'FAILED', 'TIMEOUT', 'FAILED_FIXTURE_INVALIDATED'})


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate')
        result[key] = value
    return result


def read_owned(root, relative, maximum):
    """每级openat NOFOLLOW，FD普通文件，有界读取；root为本进程受信checkout。"""
    if relative.is_absolute() or '..' in relative.parts or not relative.parts:
        raise ValueError('path')
    fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in relative.parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd); fd = child
        source = os.open(relative.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
        try:
            before = os.fstat(source)
            if not stat.S_ISREG(before.st_mode) or before.st_size > maximum:
                raise ValueError('file')
            result = bytearray()
            while len(result) <= maximum:
                block = os.read(source, min(65536, maximum + 1 - len(result)))
                if not block:
                    break
                result.extend(block)
            after = os.fstat(source)
            if len(result) > maximum or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
                raise ValueError('changed')
            return bytes(result)
        finally:
            os.close(source)
    finally:
        os.close(fd)


def parse_marker(data, test, phases):
    # 按原字节行解析；非marker正文永不进入输出，巨长marker不尝试解释。
    candidates = [line for line in data.split(b'\n') if line.startswith(PREFIX)]
    if not candidates:
        return None, 'DIAG_UNAVAILABLE'
    if len(candidates) != 1:
        return None, 'DIAG_AMBIGUOUS'
    line = candidates[0]
    if len(line) > MAX_LINE or any(byte < 32 or byte == 127 for byte in line):
        return None, 'DIAG_INVALID'
    try:
        value = json.loads(line[len(PREFIX):].decode('ascii'), object_pairs_hook=no_duplicates)
        if not isinstance(value, dict) or set(value) != {'test', 'phase', 'reason'}:
            raise ValueError('keys')
        if value['test'] != test or not isinstance(value['phase'], str) or value['phase'] not in phases:
            raise ValueError('contract')
        phase, reason = value['phase'], value['reason']
        expected = 'COMPLETED' if phase == 'COMPLETE' else 'CLEANUP_FAILED' if phase == 'CLEANUP' else 'STEP_FAILED'
        if reason != expected:
            raise ValueError('pair')
    except (ValueError, UnicodeError, TypeError, RecursionError):
        return None, 'DIAG_INVALID'
    return value, None


def annotations(root=ROOT):
    try:
        declared = json.loads(read_owned(root, Path('tools/host-tests.manifest.json'), 2 * 1024 * 1024))['tests']
        allowed = {row['path'] for row in declared if isinstance(row, dict) and row.get('stage') == 'runtime' and isinstance(row.get('path'), str)}
        report = json.loads(read_owned(root, OUTPUT/'manifest.json', 4 * 1024 * 1024))
        rows = report.get('tests', [])
        if not isinstance(rows, list):
            raise ValueError('rows')
    except (OSError, ValueError, KeyError, TypeError, AttributeError, RecursionError):
        return ['::error::gate=l3-runtime diag=DIAG_MANIFEST_UNAVAILABLE']
    lines = []
    for path, (test, phases) in CONTRACTS.items():
        if path not in allowed:
            continue
        selected = [row for row in rows if isinstance(row, dict) and row.get('path') == path]
        if not selected:
            continue
        if len(selected) != 1:
            lines.append('::error::gate=l3-runtime path=' + Path(path).name + ' diag=DIAG_AMBIGUOUS')
            continue
        row = selected[0]
        if not isinstance(row.get('status'), str) or row['status'] not in STATES:
            continue
        code = row.get('exitCode')
        code = str(code) if type(code) is int and -255 <= code <= 255 else 'unknown'
        # 不使用row.log/error/command等不受信字段，只以固定basename推导固定日志。
        try:
            data = read_owned(root, OUTPUT/(Path(path).name + '.log'), MAX_LOG)
            marker, diagnostic = parse_marker(data, test, phases)
        except (OSError, ValueError):
            marker, diagnostic = None, 'DIAG_UNAVAILABLE'
        line = '::error::gate=l3-runtime path=' + Path(path).name + ' status=' + row['status'] + ' exitCode=' + code
        if diagnostic:
            line += ' diag=' + diagnostic
        else:
            line += ' phase=' + marker['phase'] + ' reason=' + marker['reason']
        lines.append(line)
    return lines


if __name__ == '__main__':
    for line in annotations():
        print(line)
