"""Verify the actual AGP manifest output without printing manifest contents."""
from pathlib import Path
import sys
from xml.etree import ElementTree as ET


def verify(build, binding):
    if not binding.is_file() or binding.is_symlink() or not 0 < binding.stat().st_size <= 512:
        return False
    raw = binding.read_text(encoding='utf-8')
    path = Path(raw.removesuffix('\n'))
    if path.is_absolute() or '..' in path.parts or path.name != 'AndroidManifest.xml' or raw != path.as_posix() + '\n':
        return False
    manifest = build / path
    if manifest.resolve() != manifest or not manifest.is_file() or not 0 < manifest.stat().st_size <= 2**20:
        return False
    data = manifest.read_bytes()
    if b'\x00' in data or b'<!DOCTYPE' in data or b'<!ENTITY' in data:
        return False
    root = ET.fromstring(data.decode('utf-8'))
    return (root.tag == 'manifest' and root.get('package') == 'dsh.android.client.rebirth'
            and root.find('application') is not None
            and all(e.get('{http://schemas.android.com/apk/res/android}name') != 'android.permission.WRITE_SECURE_SETTINGS'
                    for e in root if e.tag.startswith('uses-permission')))


if __name__ == '__main__':
    try:
        build, binding = map(Path, sys.argv[1:])
        passed = build.is_absolute() and build.resolve() == build and verify(build, binding)
    except (OSError, RuntimeError, ValueError, ET.ParseError):
        passed = False
    print('B368_LINT_MERGED_PERMISSION=' + ('ABSENT' if passed else 'REFUSED'))
    raise SystemExit(0 if passed else 1)
