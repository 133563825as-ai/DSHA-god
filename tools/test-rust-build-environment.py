#!/usr/bin/env python3
"""将隔离 Cargo home / 工具身份的纯合成测试接入显式 host 清单；不运行 Rust。"""
import runpy
from pathlib import Path

if __name__ == "__main__":
    runpy.run_path(str(Path(__file__).resolve().parent / "rust/test_build_environment.py"),
                   run_name="__main__")
