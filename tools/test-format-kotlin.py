#!/usr/bin/env python3
"""Kotlin formatter 的隔离契约测试；只替换外部命令/下载，不运行 JVM。"""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

# 不让加载生产模块在工作树留下 __pycache__。
sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("format_kotlin", ROOT / "tools/format-kotlin.py")
formatter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(formatter)
PACKAGE = "com/deepseekharness/app/"
BEFORE = b"class Probe{val n=1}\n"
AFTER = b"class Probe { val n = 1 }\n"


class KotlinFormatTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="kotlin-format-中文 ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.layout = {"schema": 1, "modules": [{
            "path": ":app", "directory": "app", "kind": "android-application",
            "sourceSets": ["main", "standard", "debug", "androidTest", "test", "benchmark"],
            "formatSourceSets": ["main", "standard", "debug", "androidTest", "test"],
            "testSourceSets": ["test"], "testTask": "testStandardDebugUnitTest",
        }]}
        self.put("ci/source-layout.json", json.dumps(self.layout).encode())
        # 使用微型固定字节，不读取、下载或执行真实 jar。
        jar_bytes = b"fixture jar, never executable"
        self.lock = json.loads(formatter.LOCK_PATH.read_text(encoding="utf-8"))
        self.lock.update(size=len(jar_bytes), sha256=hashlib.sha256(jar_bytes).hexdigest())
        self.lock_path = self.put("tools/ktfmt.lock.json", json.dumps(self.lock).encode())
        self.cache = self.root / "app/build/kotlin-format"
        self.jar = self.put("app/build/kotlin-format/" + self.lock["artifact"], jar_bytes)
        self.java = self.put("jdk/bin/java", b"not an executable")
        # 默认参数在函数定义时绑定；改默认根而不替换被测路径算法。
        self.enter(patch.object(formatter, "ROOT", self.root))
        self.enter(patch.object(formatter, "CACHE", self.cache))
        self.enter(patch.object(formatter, "LOCK_PATH", self.lock_path))
        self.enter(patch.object(formatter.checked_path, "__defaults__", (self.root, ())))
        self.enter(patch.object(formatter.changed_files, "__defaults__", (self.root,)))
        self.network = self.enter(patch.object(formatter.urllib.request, "urlopen",
                                               side_effect=AssertionError("network forbidden")))
        self.command = self.enter(patch.object(formatter.subprocess, "run",
                                               side_effect=AssertionError("external command forbidden")))
        self.enter(patch.object(formatter, "git_output", side_effect=AssertionError("git forbidden")))

    def enter(self, manager):
        value = manager.__enter__()
        self.addCleanup(manager.__exit__, None, None, None)
        return value

    def put(self, name, data=BEFORE):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def source(self, tail="Probe.kt", source_set="main", language="kotlin"):
        return self.put(f"app/src/{source_set}/{language}/" + PACKAGE + tail)

    def relative(self, path):
        return path.relative_to(self.root).as_posix()

    def fake_java(self, command, **kwargs):
        if command == [str(self.java), "-version"]:
            return subprocess.CompletedProcess(command, 0, b"", b'openjdk version "17.0.12"\n')
        self.assertEqual(command[:5], [str(self.java), "-Dfile.encoding=UTF-8", "-jar",
                                          str(self.jar), self.lock["flags"][0]])
        self.assertEqual(command[4:6], self.lock["flags"])
        for name in command[6:]:
            path = Path(name)
            self.assertTrue(path.is_relative_to(self.cache))
            # 这里模拟外部 formatter 的字节输出，生产复制/幂等/写回逻辑保持真实。
            path.write_bytes(path.read_bytes().replace(BEFORE, AFTER))
        return subprocess.CompletedProcess(command, 0, b"", b"")

    def main(self, mode, paths, *extra):
        with contextlib.redirect_stdout(io.StringIO()):
            return formatter.main([mode, "--files", *map(str, paths), "--java", str(self.java),
                                   "--offline", *extra])

    def test_owned_sets_language_roots_and_moved_module_positive_controls(self):
        for source_set in self.layout["modules"][0]["formatSourceSets"]:
            for language, suffix in (("java", ".kt"), ("kotlin", ".kts")):
                path = self.source(source_set + language + suffix, source_set, language)
                self.assertEqual((path, self.relative(path)), formatter.checked_path(path))
        self.layout["modules"].append({
            "path": ":policy", "directory": "core/policy", "kind": "jvm",
            "sourceSets": ["main", "test"], "formatSourceSets": ["main", "test"],
            "testSourceSets": ["test"], "testTask": "test"})
        self.put("ci/source-layout.json", json.dumps(self.layout).encode())
        moved = self.put("core/policy/src/main/kotlin/Policy.kt")
        self.assertEqual(moved, formatter.checked_path(moved)[0])
        self.command.assert_not_called()

    def test_unowned_non_format_set_forbidden_parts_suffix_and_directory_refused(self):
        paths = [self.put("foreign/src/main/kotlin/Foreign.kt"),
                 self.source("Bench.kt", "benchmark"), self.source("NotKotlin.java")]
        paths += [self.source(part + "/Hidden.kt") for part in
                  ("history", "upstream", "vendor", "third_party", "third-party", "generated")]
        directory = self.root / "app/src/main/kotlin/Directory.kt"
        directory.mkdir()
        paths.append(directory)
        for path in paths:
            with self.subTest(path=path), self.assertRaisesRegex(
                    formatter.FormatError, "^FORMAT_UNOWNED_OR_PROTECTED:"):
                formatter.checked_path(path)
        self.assertTrue(all(p.is_dir() or p.read_bytes() == BEFORE for p in paths))

    def test_protected_generated_casefold_exact_match_not_prefix(self):
        protected, generated = self.source("Keep.kt"), self.source("Machine.kt")
        sibling = self.source("Keep.kt.extra.kt")
        lock = dict(self.lock, protectedFiles=[self.relative(protected).upper()],
                    generatedFiles=[self.relative(generated)])
        for path in (protected, generated):
            with self.subTest(path=path), self.assertRaisesRegex(
                    formatter.FormatError, "^FORMAT_UNOWNED_OR_PROTECTED:"):
                formatter.select_files([str(path)], lock)
            self.assertEqual(BEFORE, path.read_bytes())
        self.assertEqual([(sibling, self.relative(sibling))], formatter.select_files([str(sibling)], lock))

    def test_path_traversal_outside_missing_and_symlink_ancestors_refused(self):
        source = self.source()
        paths = [(source.parent / ".." / source.parent.name / source.name, "PATH_TRAVERSAL"),
                 (self.root.parent, "PATH_OUTSIDE_OR_MISSING"),
                 (self.root / "Missing.kt", "PATH_OUTSIDE_OR_MISSING")]
        link = source.parent / "Linked.kt"
        link.symlink_to(source)
        ancestor = source.parent / "alias"
        ancestor.symlink_to(source.parent, target_is_directory=True)
        paths += [(link, "LINK_REFUSED"), (ancestor / source.name, "LINK_REFUSED")]
        for path, code in paths:
            with self.subTest(path=path), self.assertRaisesRegex(formatter.FormatError, "^FORMAT_" + code):
                formatter.checked_path(path)
        self.assertEqual(BEFORE, source.read_bytes())

    def test_redirected_resolution_is_refused_without_replacing_path_algorithm(self):
        source, other = self.source(), self.source("Other.kt")
        resolve = Path.resolve

        def redirected(path, *args, **kwargs):
            return other if path == source else resolve(path, *args, **kwargs)

        # 非链接重定向无法用普通 POSIX 文件系统制造；只注入 resolve 的 OS 返回。
        with patch.object(Path, "resolve", redirected), self.assertRaisesRegex(
                formatter.FormatError, "^FORMAT_REDIRECTED_PATH_REFUSED:"):
            formatter.checked_path(source)
        self.assertEqual(BEFORE, source.read_bytes())

    def test_changed_selector_boundary_mixed_duplicate_limit_and_empty(self):
        first, second = self.source(), self.source("Second.kts")
        names = [self.relative(first), self.relative(second)]
        with patch.object(formatter, "git_output", side_effect=[
                (names[1] + "\0" + names[0] + "\0").encode(), (names[0] + "\0").encode()]) as git:
            selected = formatter.select_files(["changed"], self.lock)
            self.assertEqual(sorted(names), [name for _, name in selected])
            self.assertEqual(2, git.call_count)
            self.assertEqual(["diff", "--name-only", "-z", "--diff-filter=ACMRT", "HEAD", "--", "*.kt", "*.kts"],
                             git.call_args_list[0].args[0])
            self.assertEqual(["ls-files", "--others", "--exclude-standard", "-z", "--", "*.kt", "*.kts"],
                             git.call_args_list[1].args[0])
            self.assertTrue(all(call.kwargs["cwd"] == self.root for call in git.call_args_list))
        for files, code in ((["changed", names[0]], "SELECTOR_CANNOT_BE_MIXED"),
                            ([names[0], str(first)], "DUPLICATE_FILE"),
                            ([names[0]] * 257, "TOO_MANY_FILES"), ([42], "FILE_MUST_BE_STRING")):
            with self.subTest(files=files[:2]), self.assertRaisesRegex(formatter.FormatError, "^FORMAT_" + code):
                formatter.select_files(files, self.lock)
        many = [self.source(f"Many{n}.kt") for n in range(256)]
        self.assertEqual(256, len(formatter.select_files(list(map(str, many)), self.lock)))
        with patch.object(formatter, "git_output", return_value=b""):
            self.assertEqual(0, self.main("--check", ["changed"]))
        self.command.assert_not_called()

    def test_jdk_non_17_malformed_failed_and_missing_rejected(self):
        for rc, text in ((0, b'openjdk version "21.0.1"'), (0, b'java version "1.8.0"'),
                         (0, b"unparseable"), (1, b'openjdk version "17"')):
            self.command.return_value = subprocess.CompletedProcess([], rc, b"", text)
            self.command.side_effect = None
            with self.subTest(text=text), self.assertRaisesRegex(formatter.FormatError, "^FORMAT_REQUIRES_JDK17:"):
                formatter.java_command(str(self.java), self.lock)
        self.command.side_effect = self.fake_java
        self.assertEqual(self.java, formatter.java_command(str(self.java), self.lock)[0])
        with patch.dict(os.environ, {}, clear=True), patch.object(formatter.shutil, "which", return_value=None):
            with self.assertRaisesRegex(formatter.FormatError, "^FORMAT_JDK17_MISSING:"):
                formatter.java_command(None, self.lock)

    def test_cached_size_sha_and_offline_missing_never_download(self):
        valid = self.jar.read_bytes()
        self.assertEqual(self.jar, formatter.artifact(self.lock, offline=True))
        for corrupt in (valid + b"x", b"x" * len(valid)):
            self.jar.write_bytes(corrupt)
            with self.subTest(corrupt=corrupt), self.assertRaisesRegex(
                    formatter.FormatError, "^FORMAT_CACHE_DIGEST_MISMATCH:"):
                formatter.artifact(self.lock, offline=False)
            self.assertEqual(corrupt, self.jar.read_bytes())
        # 只移除已核对的临时 fixture jar，不触碰真实缓存。
        self.assertTrue(self.jar.is_relative_to(self.root))
        self.jar.unlink()
        with self.assertRaisesRegex(formatter.FormatError, "^FORMAT_OFFLINE_CACHE_MISSING:"):
            formatter.artifact(self.lock, offline=True)
        self.network.assert_not_called()

    def test_download_boundary_checks_size_sha_before_atomic_cache_publish(self):
        valid = self.jar.read_bytes()
        self.assertTrue(self.jar.is_relative_to(self.root))
        self.jar.unlink()
        for data in (valid + b"x", b"x" * len(valid), valid):
            with patch.object(formatter.urllib.request, "urlopen") as request:
                response = request.return_value.__enter__.return_value
                response.read.return_value = data
                if data == valid:
                    self.assertEqual(self.jar, formatter.artifact(self.lock, offline=False))
                    self.assertEqual(valid, self.jar.read_bytes())
                else:
                    with self.assertRaisesRegex(formatter.FormatError, "^FORMAT_DOWNLOAD_DIGEST_MISMATCH$"):
                        formatter.artifact(self.lock, offline=False)
                    self.assertFalse(self.jar.exists())
                response.read.assert_called_once_with(self.lock["size"] + 1)
                self.assertEqual(self.lock["artifactUrl"], request.call_args.args[0].full_url)
        self.network.assert_not_called()

    def test_report_outside_cache_traversal_and_link_refused_before_commands(self):
        source = self.source()
        outside = self.root / "evidence"
        outside.mkdir()
        self.cache.mkdir(exist_ok=True)
        (self.cache / "redirect").symlink_to(outside, target_is_directory=True)
        for report in (outside / "report.json", self.cache / ".." / "escape.json",
                       self.cache / "redirect/report.json"):
            with self.subTest(report=report), self.assertRaisesRegex(
                    formatter.FormatError, "^FORMAT_REPORT_OUTSIDE_CACHE$"):
                self.main("--write", [source], "--report", str(report))
            self.assertFalse(report.exists())
        self.command.assert_not_called()
        self.assertEqual(BEFORE, source.read_bytes())

    def test_check_write_check_reports_hashes_order_diff_and_mode_preserved(self):
        first, second = self.source(), self.source("Second.kts")
        first.chmod(0o640)
        report = self.cache / "report.json"
        self.command.side_effect = self.fake_java
        self.assertEqual(1, self.main("--check", [second, first], "--report", str(report)))
        evidence = json.loads(report.read_text())
        self.assertEqual([self.relative(second), self.relative(first)], [e["file"] for e in evidence["files"]])
        for entry in evidence["files"]:
            self.assertEqual(hashlib.sha256(BEFORE).hexdigest(), entry["beforeSha256"])
            self.assertEqual(hashlib.sha256(AFTER).hexdigest(), entry["formattedSha256"])
            self.assertTrue(entry["needsFormatting"])
            self.assertIn("-class Probe{val n=1}", entry["diff"])
            self.assertIn("+class Probe { val n = 1 }", entry["diff"])
        self.assertEqual(BEFORE, first.read_bytes())
        self.assertEqual(0, self.main("--write", [second, first], "--report", str(report)))
        self.assertEqual(AFTER, first.read_bytes())
        self.assertEqual(AFTER, second.read_bytes())
        self.assertEqual(0o640, first.stat().st_mode & 0o777)
        self.assertEqual("write", json.loads(report.read_text())["mode"])
        self.assertEqual(0, self.main("--check", [second, first], "--report", str(report)))
        self.assertTrue(all(not e["needsFormatting"] for e in json.loads(report.read_text())["files"]))
        self.network.assert_not_called()

    def test_source_change_before_write_is_not_overwritten(self):
        source = self.source()
        concurrent = b"class UserEdit\n"
        report = self.cache / "race.json"

        def race(command, **kwargs):
            result = self.fake_java(command, **kwargs)
            if any("/stable/" in name for name in command):
                source.write_bytes(concurrent)
            return result

        self.command.side_effect = race
        with self.assertRaisesRegex(formatter.FormatError, "^FORMAT_CONCURRENT_SOURCE_CHANGE:"):
            self.main("--write", [source], "--report", str(report))
        self.assertEqual(concurrent, source.read_bytes())
        self.assertFalse(report.exists())

    def test_idempotence_output_change_and_command_failure_never_write(self):
        source = self.source()
        for fail_rc in (False, True):
            def unstable(command, **kwargs):
                result = self.fake_java(command, **kwargs)
                if any("/stable/" in name for name in command):
                    if fail_rc:
                        return subprocess.CompletedProcess(command, 3, b"", b"fixture idempotence failure")
                    Path(command[-1]).write_bytes(b"unstable second output\n")
                return result
            self.command.side_effect = unstable
            with self.subTest(fail_rc=fail_rc), self.assertRaisesRegex(
                    formatter.FormatError, "^FORMAT_" + ("IDEMPOTENCE_RUN_FAILED" if fail_rc else "NOT_IDEMPOTENT")):
                self.main("--write", [source])
            self.assertEqual(BEFORE, source.read_bytes())

    def test_batch_parse_localization_and_generic_failure_never_write(self):
        source = self.source()
        for stdin_rc in (0, 4):
            def failed(command, **kwargs):
                if command[-1] == "-":
                    self.assertEqual(BEFORE, kwargs["input"])
                    self.assertIn("--stdin-name=" + self.relative(source), command)
                    return subprocess.CompletedProcess(command, stdin_rc, AFTER, b"fixture parse failure")
                if "-version" in command:
                    return self.fake_java(command, **kwargs)
                return subprocess.CompletedProcess(command, 5, b"", b"fixture batch failure")
            self.command.side_effect = failed
            with self.subTest(stdin_rc=stdin_rc), self.assertRaisesRegex(
                    formatter.FormatError, "^FORMAT_" + ("PARSE_FAILED" if stdin_rc else "BATCH_FAILED")):
                self.main("--write", [source])
            self.assertEqual(BEFORE, source.read_bytes())

    def test_lock_schema_rejected_before_external_commands(self):
        source = self.source()
        for change in ({"schema": 2}, {"tool": "other"}, {"artifact": "unlocked.jar"}):
            self.lock_path.write_text(json.dumps(dict(self.lock, **change)))
            with self.subTest(change=change), self.assertRaisesRegex(formatter.FormatError, "^FORMAT_LOCK_SCHEMA$"):
                self.main("--write", [source])
        self.command.assert_not_called()
        self.assertEqual(BEFORE, source.read_bytes())


if __name__ == "__main__":
    unittest.main(verbosity=2)
