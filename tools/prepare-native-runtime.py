#!/usr/bin/env python3
"""生成原生运行环境（不经 proot 的 Android Node）的打包输入。

输出（都在 --output 下，均为生成物，不进仓库）：
  jniLibs/arm64-v8a/libnode.so   Termux nodejs-lts 的 bin/node（字节不变）
  jniLibs/arm64-v8a/librg.so     Termux ripgrep 的 bin/rg（字节不变）
  assets/native-runtime.zip      依赖库 lib/*、根证书、打过 Android 补丁的 DSH 树 node_modules/、许可文件
  assets/native-runtime.json     清单：每个文件的 SHA-256 与来源，App 解压后据此核验

为什么不经网络：构建只读本地缓存（--cache，缺省 ~/.cache/dsha-native-runtime/debs），
每个 deb 都按 tools/native-runtime/lock.json 的 SHA-256 核对；缓存缺文件就失败并提示 --fetch。
--fetch 只在显式给出时才下载，下载后同样核对。

为什么 node 放 jniLibs：Android 10+ 的应用不能执行 filesDir 里的文件（W^X），
只有安装时解压到 nativeLibraryDir 的文件可以执行；依赖库按 LD_LIBRARY_PATH 从 filesDir 加载（S2 真机验证）。
"""
import argparse
import hashlib
import io
import json
import os
import pathlib
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCK = ROOT / 'tools/native-runtime/lock.json'
TERMUX_PREFIX = 'data/data/com.termux/files/usr/'
ZIP_TIME = (2026, 1, 1, 0, 0, 0)

sys.path.insert(0, str(ROOT / 'tools/native-runtime'))
import dsh_android_patches  # noqa: E402


def sha(data):
    return hashlib.sha256(data).hexdigest()


def fail(code, detail=''):
    raise SystemExit('NATIVE_RUNTIME_' + code + (': ' + detail if detail else ''))


def deb_members(path):
    """解出 deb 的 data.tar.*，返回 {相对 Termux 前缀的路径: (TarInfo, bytes|None)}。"""
    data = path.read_bytes()
    if data[:8] != b'!<arch>\n':
        fail('DEB_FORMAT', path.name)
    offset = 8
    while offset < len(data):
        name = data[offset:offset + 16].decode().strip()
        size = int(data[offset + 48:offset + 58])
        body = data[offset + 60:offset + 60 + size]
        offset += 60 + size + (size & 1)
        if name.startswith('data.tar'):
            result = {}
            with tarfile.open(fileobj=io.BytesIO(body), mode='r:*') as archive:
                for member in archive:
                    rel = member.name.lstrip('./')
                    if not rel.startswith(TERMUX_PREFIX):
                        continue
                    rel = rel[len(TERMUX_PREFIX):]
                    content = archive.extractfile(member).read() if member.isfile() else None
                    result[rel] = (member, content)
            return result
    fail('DEB_NO_DATA', path.name)


def resolve(members, rel, depth=0):
    """按 deb 内的软链接解析到真实文件内容（只在同一前缀内）。"""
    if depth > 8 or rel not in members:
        return None
    member, content = members[rel]
    if member.issym():
        target = member.linkname
        base = os.path.dirname(rel)
        return resolve(members, os.path.normpath(os.path.join(base, target)) if not target.startswith('/') else
                       target.split(TERMUX_PREFIX, 1)[-1], depth + 1)
    return content if member.isfile() else None


def elf_needed(data):
    if data[:4] != b'\x7fELF' or data[4] != 2:
        fail('ELF_FORMAT')
    phoff, = struct.unpack_from('<Q', data, 32)
    entsize, count = struct.unpack_from('<HH', data, 54)
    loads, dynamic = [], None
    for index in range(count):
        kind, _, offset, vaddr, _, filesz, _, _ = struct.unpack_from('<IIQQQQQQ', data, phoff + index * entsize)
        if kind == 1:
            loads.append((vaddr, offset, filesz))
        elif kind == 2:
            dynamic = (offset, filesz)
    if dynamic is None:
        return []
    entries = []
    for at in range(0, dynamic[1], 16):
        tag, value = struct.unpack_from('<qQ', data, dynamic[0] + at)
        entries.append((tag, value))
        if tag == 0:
            break
    strtab = next(v for t, v in entries if t == 5)
    base = next(strtab - va + off for va, off, size in loads if va <= strtab < va + size)
    text = lambda at: data[base + at:data.index(b'\0', base + at)].decode()
    return [text(v) for t, v in entries if t == 1]


def zip_add(archive, name, data):
    info = zipfile.ZipInfo(name, date_time=ZIP_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    archive.writestr(info, data, compresslevel=6)


def fetch(entry, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    temp = target.with_suffix(target.suffix + '.part')
    subprocess.run(['curl', '-sfL', '-C', '-', '-m', '900', '-o', str(temp), entry['url']], check=True)
    if sha(temp.read_bytes()) != entry['sha256']:
        temp.unlink()
        fail('FETCH_SHA256', entry['file'])
    temp.replace(target)


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=pathlib.Path)
    parser.add_argument('--cache', type=pathlib.Path,
                        default=pathlib.Path(os.environ.get('DSHA_NATIVE_RUNTIME_CACHE',
                                                            pathlib.Path.home() / '.cache/dsha-native-runtime/debs')))
    parser.add_argument('--dsh-runtime', type=pathlib.Path, default=ROOT / 'app/src/main/assets/dsh-runtime.bin')
    parser.add_argument('--fetch', action='store_true', help='缓存缺包时从锁定 URL 下载（仍核对 SHA-256）')
    args = parser.parse_args(argv)
    lock = json.loads(LOCK.read_text(encoding='utf-8'))
    if lock.get('schema') != 1:
        fail('LOCK_SCHEMA')

    # 1. 读取并核对每个 deb。
    files, licenses, sources = {}, {}, []
    for entry in lock['packages']:
        path = args.cache / entry['file']
        if not path.is_file():
            if not args.fetch:
                fail('CACHE_MISSING', '%s（在 %s；用 --fetch 下载）' % (entry['file'], args.cache))
            fetch(entry, path)
        if sha(path.read_bytes()) != entry['sha256']:
            fail('DEB_SHA256', entry['file'])
        members = deb_members(path)
        for rel in members:
            content = resolve(members, rel)
            if content is not None:
                files.setdefault(rel, content)
        copyright = 'share/doc/%s/copyright' % entry['package']
        if copyright in members and resolve(members, copyright) is not None:
            licenses[entry['package']] = resolve(members, copyright)
        sources.append({k: entry[k] for k in ('package', 'version', 'sha256', 'url')})

    # 2. 可执行文件与依赖闭包（必须恰好等于锁定的库清单）。
    out_jni = args.output / 'jniLibs/arm64-v8a'
    out_assets = args.output / 'assets'
    if args.output.exists():
        shutil.rmtree(args.output)
    out_jni.mkdir(parents=True)
    out_assets.mkdir(parents=True)
    executables = {}
    needed = set()
    for rel, packaged in sorted(lock['executables'].items()):
        data = files.get(rel)
        if data is None:
            fail('EXECUTABLE_MISSING', rel)
        (out_jni / packaged).write_bytes(data)
        executables[packaged] = {'from': rel, 'sha256': sha(data), 'bytes': len(data)}
        needed.update(elf_needed(data))
    queue, closure = list(needed), set()
    while queue:
        name = queue.pop()
        if name in lock['systemLibraries'] or name in closure:
            continue
        data = files.get('lib/' + name)
        if data is None:
            fail('LIBRARY_MISSING', name)
        closure.add(name)
        queue.extend(elf_needed(data))
    if sorted(closure) != sorted(lock['libraries']):
        fail('LIBRARY_CLOSURE', 'actual=%s locked=%s' % (sorted(closure), sorted(lock['libraries'])))

    # 3. DSH 树：取自本 APK 同一份 dsh-runtime.bin，删去全部 ELF（都是 linux/darwin 预编译），装受管包，打补丁。
    with tempfile.TemporaryDirectory(prefix='dsha-native-') as work:
        work = pathlib.Path(work)
        with tarfile.open(args.dsh_runtime, mode='r:gz') as archive:
            archive.extractall(work, filter='tar')
        modules = work / 'usr/local/lib/node_modules'
        if not (modules / '@deepseek-ai/dsh/lib/bin.js').is_file():
            fail('DSH_TREE')
        removed = []
        for path in sorted(modules.rglob('*')):
            if path.is_file() and not path.is_symlink():
                with path.open('rb') as handle:
                    if handle.read(4) == b'\x7fELF':
                        removed.append(path.relative_to(modules).as_posix())
                        path.unlink()
        policy = modules / '@deepseek-ai/dsh/node_modules/dsha-destructive-policy'
        policy.mkdir(parents=True, exist_ok=True)
        for name in ('package.json', 'index.js', 'destructive.js', 'workdir.js'):
            shutil.copyfile(ROOT / 'app/src/main/assets/destructive-policy' / name, policy / name)
        # 确认 SDK：与容器 /run/dsha-launcher 同一份 APK 字节，放进包内固定相对位置（补丁里再改凭据目录）。
        (policy / 'launcher').mkdir()
        for name in ('native-consent.cjs', 'bridge-port.cjs'):
            shutil.copyfile(ROOT / 'app/src/main/assets' / name, policy / 'launcher' / name)
        applied = []
        for patch in dsh_android_patches.PATCHES:
            target = modules / patch['path']
            data = target.read_bytes()
            if sha(data) != patch['original_sha256']:
                fail('PATCH_ORIGINAL', patch['id'])
            if 'replace_file' in patch:
                new = patch['replace_file'].encode('utf-8')
            else:
                text = data.decode('utf-8')
                for old, replacement in patch['replace_exact']:
                    if text.count(old) != 1:
                        fail('PATCH_ANCHOR', patch['id'])
                    text = text.replace(old, replacement)
                new = text.encode('utf-8')
            target.write_bytes(new)
            applied.append({'id': patch['id'], 'path': patch['path'], 'original': patch['original_sha256'], 'patched': sha(new)})

        # 4. 资产包：确定性 zip（排序、固定时间、0644），软链接（只有 .bin/ 快捷方式）跳过。
        manifest_files = {}
        zip_path = out_assets / 'native-runtime.zip'
        with zipfile.ZipFile(zip_path, 'w') as archive:
            for name in sorted(lock['libraries']):
                data = files['lib/' + name]
                zip_add(archive, 'lib/' + name, data)
                manifest_files['lib/' + name] = sha(data)
            cert = (ROOT / 'app/src/main/assets/ca-certificates.crt').read_bytes()
            zip_add(archive, 'etc/tls/cert.pem', cert)
            manifest_files['etc/tls/cert.pem'] = sha(cert)
            for package, text in sorted(licenses.items()):
                zip_add(archive, 'licenses/%s.copyright' % package, text)
            skipped = 0
            for path in sorted(modules.rglob('*')):
                if path.is_symlink():
                    skipped += 1
                    continue
                if path.is_file():
                    rel = 'node_modules/' + path.relative_to(modules).as_posix()
                    zip_add(archive, rel, path.read_bytes())
        archive_bytes = zip_path.read_bytes()
    manifest = {
        'schema': 1,
        'archive': {'name': 'native-runtime.zip', 'sha256': sha(archive_bytes), 'bytes': len(archive_bytes)},
        'executables': executables,
        'entry': 'node_modules/@deepseek-ai/dsh/lib/bin.js',
        'libraryDirectory': 'lib',
        'certificate': 'etc/tls/cert.pem',
        'verifiedFiles': manifest_files,
        'patches': applied,
        'removedElf': removed,
        'skippedSymlinks': skipped,
        'sources': sources,
        'dshRuntime': sha(args.dsh_runtime.read_bytes()),
    }
    (out_assets / 'native-runtime.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
    print('native runtime: %s (%d B), %d patches, %d ELF removed' % (manifest['archive']['sha256'], len(archive_bytes), len(applied), len(removed)))


if __name__ == '__main__':
    main()
