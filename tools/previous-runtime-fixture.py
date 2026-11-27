#!/usr/bin/env python3
"""历史升级夹具的单源 APK 身份；不下载、不接受调用方放宽摘要。"""
import hashlib
from pathlib import Path
from typing import NamedTuple


class ApkIdentity(NamedTuple):
    build: int
    generation: str
    sha256: str
    url: str


# 身份来源：docs/releases/v0.2.1-rc.3-build212-recovery-20261030.md。
# archive 有独立摘要，由消费方继续验证，不能用它替代 APK 身份。
CONTRACT = ApkIdentity(
    build=212,
    generation="0.2.1-rc.3",
    sha256="2a69a315eca22f8711a97122a54a2f8beb4d98e624a04d8bb3afa65a420e89d2",
    url="https://github.com/SunsetRNE/DSHA-Sunset-mod/releases/download/"
        "dsha-a-su-build212-20261030-150600/dsha-a-su-eb278988.apk",
)


def verify_apk(selected):
    """读取真实文件字节，只有精确历史 APK 摘要匹配才返回绝对路径。"""
    if not selected:
        raise ValueError("PREVIOUS_RUNTIME_APK_REQUIRED: exact build" + str(CONTRACT.build))
    path = Path(selected).resolve()
    if not path.is_file():
        raise ValueError("PREVIOUS_RUNTIME_APK_MISSING: " + str(path))
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != CONTRACT.sha256:
        raise ValueError("PREVIOUS_RUNTIME_APK_IDENTITY: expected build" + str(CONTRACT.build)
                         + " (" + CONTRACT.generation + ") sha256=" + CONTRACT.sha256
                         + "; actual=" + digest.hexdigest())
    return path
