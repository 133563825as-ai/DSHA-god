#!/usr/bin/env python3
"""A-1/A-2 专用离线合成测试；所有工具执行均 mock，不调用 Rust/网络/设备。"""
from contextlib import ExitStack
import io
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build


LOCK = {"channel": "1.100.0", "rustc": {"version": "1.100.0", "commit": "0ef1bc386"},
        "cargo": {"version": "1.100.0"}, "androidApi": 23, "ndk": "26.3.11579264"}
FOUND = {"rustcPath": "/verified/bin/rustc", "cargoPath": "/verified/bin/cargo",
         "rustc": "1.100.0", "rustcCommit": "0ef1bc386" + "0" * 31,
         "cargo": "1.100.0", "host": "x86_64-unknown-linux-gnu"}


class BuildFixture(unittest.TestCase):
    def setUp(self):
        # 显式 /tmp 避免继承宿主 TMPDIR；清理前核对绝对路径与原目录身份。
        self.root = Path(tempfile.mkdtemp(prefix="dsha-build-env-test-", dir="/tmp"))
        self.identity = self.root.stat()
        self.addCleanup(self.cleanup_root)
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(build.tempfile, "gettempdir", return_value=str(self.root)))
        self.stack.enter_context(patch.object(build.subprocess, "run",
                                              side_effect=AssertionError("禁止真实子进程")))
        self.crate = self.root / "crate"
        (self.crate / ".cargo").mkdir(parents=True)
        (self.crate / ".cargo/config.toml").write_text('[source.crates-io]\nreplace-with="vendor"\n')
        self.target = self.crate / "target/rust-build"
        self.homes = []

    def cleanup_root(self):
        build.checked_directory(self.root)
        current = self.root.stat()
        self.assertEqual((current.st_dev, current.st_ino),
                         (self.identity.st_dev, self.identity.st_ino))
        self.assertEqual(self.root.parent, Path("/tmp"))
        shutil.rmtree(self.root)

    def fake_build(self, command, *, cwd, env):
        self.assertEqual(command, [FOUND["cargoPath"], "build", "--release", "--locked", "--offline"])
        self.assertEqual(env["RUSTC"], FOUND["rustcPath"])
        home = Path(env["CARGO_HOME"])
        self.assertTrue(home.is_absolute())
        self.assertFalse(home.is_relative_to(self.target))
        self.assertEqual(list(home.iterdir()), [])
        self.assertEqual(home.stat().st_mode & 0o077, 0)
        self.homes.append(home)
        # 模拟 Cargo 产生的配置/缓存必须随本次私有 home 清掉，而非下次重用。
        (home / "config.toml").write_text('[profile.release]\npanic="abort"\n')
        artifact = build.artifact_path(Path(env["CARGO_TARGET_DIR"]), "host")
        artifact.parent.mkdir(parents=True, exist_ok=True)
        artifact.write_bytes(b"synthetic-not-an-ELF")
        return ""

    def once(self):
        return build.build_once(target="host", target_dir=self.target, remap_source=self.crate,
                                ndk=None, lock=LOCK, found=FOUND, jobs=2)


class EnvironmentTest(BuildFixture):
    def test_old_home_profile_and_wrapper_never_reused(self):
        for name, body in (("config.toml", '[profile.release]\npanic="abort"\n'),
                           ("config", '[build]\nrustc-workspace-wrapper="/evil/wrapper"\n')):
            with self.subTest(name=name):
                old = self.target / "cargo-home"
                old.mkdir(parents=True, exist_ok=True)
                config = old / name
                config.write_text(body)
                with patch.object(build, "run", side_effect=self.fake_build):
                    self.once()
                self.assertEqual(config.read_text(), body)
                self.assertFalse(self.homes[-1].exists())

    def test_old_home_symlink_ignored_and_preserved(self):
        victim = self.root / "user-home"
        victim.mkdir()
        (victim / "config.toml").write_text("private user config")
        self.target.mkdir(parents=True)
        old = self.target / "cargo-home"
        old.symlink_to(victim, target_is_directory=True)
        with patch.object(build, "run", side_effect=self.fake_build):
            self.once()
        self.assertTrue(old.is_symlink())
        self.assertEqual((victim / "config.toml").read_text(), "private user config")

    def test_two_builds_fresh_home_and_target_cache_preserved(self):
        self.target.mkdir(parents=True)
        cache = self.target / "existing-cache"
        cache.write_bytes(b"keep")
        with patch.object(build, "run", side_effect=self.fake_build):
            self.once()
            self.once()
        self.assertNotEqual(*self.homes)
        self.assertTrue(all(not home.exists() for home in self.homes))
        self.assertEqual(cache.read_bytes(), b"keep")

    def test_failed_command_and_missing_artifact_clean_home(self):
        for failure in (True, False):
            def execute(command, *, cwd, env):
                self.homes.append(Path(env["CARGO_HOME"]))
                if failure:
                    raise build.BuildError("mock failure")
                return ""
            with self.subTest(failure=failure), patch.object(build, "run", side_effect=execute):
                with self.assertRaises(build.BuildError):
                    self.once()
            self.assertFalse(self.homes[-1].exists())

    def test_temp_parent_or_ancestor_symlink_rejected_before_creation(self):
        real = self.root / "real"
        real.mkdir()
        link = self.root / "linked"
        link.symlink_to(real, target_is_directory=True)
        (real / "child").mkdir()
        for parent in (link, link / "child"):
            with self.subTest(parent=parent), \
                    patch.object(build.tempfile, "gettempdir", return_value=str(parent)), \
                    patch.object(build.tempfile, "mkdtemp") as create:
                with self.assertRaisesRegex(build.BuildError, "DIRECTORY_UNSAFE"):
                    with build.private_cargo_home():
                        self.fail("不可进入链接 home")
                create.assert_not_called()

    def test_nonempty_or_link_home_rejected(self):
        with build.private_cargo_home() as home:
            (home / "config").write_text("injection")
            with self.assertRaisesRegex(build.BuildError, "NOT_PRIVATE_EMPTY"):
                build.sanitized_environment(cargo_home=home)
        link = self.root / "home-link"
        link.symlink_to(self.crate, target_is_directory=True)
        with self.assertRaisesRegex(build.BuildError, "DIRECTORY_UNSAFE"):
            build.sanitized_environment(cargo_home=link)

    def test_cleanup_does_not_follow_created_home_child_link(self):
        victim = self.root / "user-data"
        victim.mkdir()
        marker = victim / "keep"
        marker.write_bytes(b"do not delete")
        with build.private_cargo_home() as home:
            (home / "foreign-link").symlink_to(victim, target_is_directory=True)
        self.assertFalse(home.exists())
        self.assertEqual(marker.read_bytes(), b"do not delete")

    def test_dotdot_cannot_hide_link_ancestor(self):
        real = self.root / "real"
        real.mkdir()
        link = self.root / "linked"
        link.symlink_to(real, target_is_directory=True)
        with self.assertRaisesRegex(build.BuildError, "DIRECTORY_UNSAFE"):
            build.checked_directory(link / ".." / self.crate.name)

    def test_host_overrides_removed_and_guard_still_fail_closed(self):
        poisoned = {"CARGO_HOME": "/untrusted", "RUSTC_WRAPPER": "evil",
                    "RUSTC_WORKSPACE_WRAPPER": "evil", "CARGO_PROFILE_RELEASE_PANIC": "abort",
                    "CARGO_ENCODED_RUSTFLAGS": "evil", "CARGO_BUILD_TARGET": "evil",
                    "RUSTUP_TOOLCHAIN": "unlocked", "PATH": "/synthetic/bin"}
        with patch.dict(os.environ, poisoned, clear=True), build.private_cargo_home() as home:
            env = build.build_env("host", triples={}, ndk=None, found=FOUND,
                                  target_dir=self.target, remap_source=self.crate,
                                  cargo_home=home, jobs=2)
        for key in poisoned:
            if key not in ("PATH", "CARGO_HOME"):
                self.assertNotIn(key, env)
        for key in ("CARGO_PROFILE_RELEASE_PANIC", "RUSTC_WRAPPER", "UNKNOWN_OVERRIDE"):
            with self.subTest(key=key), self.assertRaises(build.BuildError):
                build.assert_fixed_environment({**env, key: "evil"}, target="host", flags=env["RUSTFLAGS"])

    def test_foreign_config_rejected(self):
        foreign = self.root / ".cargo"
        foreign.mkdir()
        (foreign / "config").write_text('[build]\nrustc-workspace-wrapper="evil"\n')
        with patch.object(build, "run") as execute:
            with self.assertRaisesRegex(build.BuildError, "CONFIG_UNEXPECTED"):
                self.once()
            execute.assert_not_called()

    def test_main_resolves_once_for_two_builds_and_receipt(self):
        sources = {"synthetic": True}
        seen = []
        actual_receipt = build.receipt
        def capture_receipt(**kwargs):
            seen.append(kwargs["found"])
            result = actual_receipt(**kwargs)
            self.assertEqual(result["toolchain"]["cargo"], FOUND["cargo"])
            self.assertEqual(result["toolchain"]["rustcCommit"], FOUND["rustcCommit"])
            return result
        with ExitStack() as stack:
            stack.enter_context(patch.object(build, "RUST", self.crate))
            stack.enter_context(patch.object(build, "load_json", side_effect=[LOCK, {}]))
            chain = stack.enter_context(patch.object(build, "toolchain", return_value=FOUND))
            stack.enter_context(patch.object(build, "cargo_binary", side_effect=AssertionError("不可重新解析")))
            stack.enter_context(patch.object(build, "run", side_effect=self.fake_build))
            stack.enter_context(patch.object(build.rust_artifact, "current_commit", return_value="a" * 40))
            stack.enter_context(patch.object(build.rust_artifact, "source_inputs", return_value=sources))
            stack.enter_context(patch.object(build.rust_artifact, "assert_committed_inputs"))
            stack.enter_context(patch.object(build.rust_artifact, "generation_metadata", return_value={}))
            stack.enter_context(patch.object(build, "describe_artifact", return_value={"size": 20, "sha256": "a" * 64, "budget": "PASS"}))
            stack.enter_context(patch.object(build, "receipt", side_effect=capture_receipt))
            stack.enter_context(patch("sys.stdout", new_callable=io.StringIO))
            self.assertEqual(build.main(["--host", "--check"]), 0)
        chain.assert_called_once_with(LOCK)
        self.assertEqual(len(self.homes), 2)
        self.assertNotEqual(*self.homes)
        self.assertTrue(all(not home.exists() for home in self.homes))
        self.assertEqual(len(seen), 1)
        self.assertIs(seen[0], FOUND)


class ToolchainTest(BuildFixture):
    def tool_files(self, kind):
        directory = self.root / kind
        directory.mkdir()
        rustup = directory / "rustup"
        rustup.write_bytes(b"synthetic-rustup")
        rustc, cargo = directory / "rustc", directory / "cargo"
        for path in (rustc, cargo):
            if kind == "symlink":
                path.symlink_to(rustup)
            elif kind == "hardlink":
                os.link(rustup, path)
            else:
                path.write_bytes(b"synthetic-independent")
        return rustup, rustc, cargo

    def test_path_cargo_symlink_hardlink_independent_keep_argv0(self):
        for kind in ("symlink", "hardlink", "independent"):
            with self.subTest(kind=kind):
                _, rustc, cargo = self.tool_files(kind)
                def which(name):
                    return {"cargo": str(cargo), "rustc": str(rustc)}.get(name)
                def execute(command, *, cwd, env):
                    self.assertNotIn("RUSTUP_TOOLCHAIN", env)
                    if command == [str(rustc), "-vV"]:
                        return "release: 1.100.0\ncommit-hash: %s\nhost: %s\n" % (FOUND["rustcCommit"], FOUND["host"])
                    self.assertEqual(command, [str(cargo), "-V"])
                    return "cargo 1.100.0 (synthetic)"
                with patch.object(build.shutil, "which", side_effect=which), \
                        patch.object(build, "run", side_effect=execute):
                    found = build.toolchain(LOCK)
                self.assertEqual(found["cargoPath"], str(cargo))
                self.assertEqual(found["rustcPath"], str(rustc))

    def test_rustup_which_same_locked_toolchain_and_no_path_cargo(self):
        rustup, rustc, cargo = self.tool_files("rustup-selected")
        commands = []
        def execute(command, *, cwd, env):
            commands.append(command)
            if "which" in command:
                self.assertEqual(command[:4], [str(rustup), "which", "--toolchain", LOCK["channel"]])
                return str(rustc if command[-1] == "rustc" else cargo) + "\n"
            if command[0] == str(rustc):
                return "release: 1.100.0\ncommit-hash: %s\nhost: %s\n" % (FOUND["rustcCommit"], FOUND["host"])
            self.assertEqual(command, [str(cargo), "-V"])
            return "cargo 1.100.0 (synthetic)"
        with patch.object(build.shutil, "which", return_value=str(rustup)) as which, \
                patch.object(build, "run", side_effect=execute), \
                patch.object(build, "cargo_binary", side_effect=AssertionError("不可查PATH cargo")):
            found = build.toolchain(LOCK)
        which.assert_called_once_with("rustup")
        self.assertEqual(found["cargoPath"], str(cargo))
        self.assertEqual(len(commands), 4)

    def test_rustup_lookup_failure_does_not_fallback(self):
        with patch.object(build.shutil, "which", return_value="/mock/rustup") as which, \
                patch.object(build, "run", side_effect=build.BuildError("missing locked toolchain")):
            with self.assertRaisesRegex(build.BuildError, "missing locked toolchain"):
                build.toolchain(LOCK)
        which.assert_called_once_with("rustup")

    def test_rustup_version_not_accepted_as_cargo(self):
        rustup, rustc, cargo = self.tool_files("wrong-cargo")
        def execute(command, *, cwd, env):
            if "which" in command:
                return str(rustc if command[-1] == "rustc" else cargo)
            if command[0] == str(rustc):
                return "release: 1.100.0\ncommit-hash: %s\nhost: %s\n" % (FOUND["rustcCommit"], FOUND["host"])
            return "rustup 1.100.0 (not cargo)"
        with patch.object(build.shutil, "which", return_value=str(rustup)), \
                patch.object(build, "run", side_effect=execute):
            with self.assertRaisesRegex(build.BuildError, "TOOLCHAIN_MISMATCH: cargo"):
                build.toolchain(LOCK)


if __name__ == "__main__":
    unittest.main(verbosity=2)
