#!/usr/bin/env python3
"""Kotlin tree 门禁的真实布局、分批、报告与写前摘要回归；外部命令全隔离。"""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("format_kotlin_tree", ROOT / "tools/format-kotlin-tree.py")
tree = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tree)
PACKAGE = "com/deepseekharness/app/"
BEFORE = b"class Probe{val n=1}\n"
AFTER = b"class Probe { val n = 1 }\n"


class KotlinTreeTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="kotlin-tree-中文 ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.layout = {"schema": 1, "modules": [{
            "path": ":app", "directory": "app", "kind": "android-application",
            "sourceSets": ["main", "standard", "debug", "androidTest", "test", "benchmark"],
            "formatSourceSets": ["main", "standard", "debug", "androidTest", "test"],
            "testSourceSets": ["test"], "testTask": "testStandardDebugUnitTest"}]}
        self.put("ci/source-layout.json", json.dumps(self.layout).encode())
        self.lock = {"schema": 1, "tool": "ktfmt", "version": "fixture", "sha256": "0" * 64,
                     "style": "kotlinlang", "protectedFiles": [], "generatedFiles": []}
        self.lock_path = self.put("tools/ktfmt.lock.json", json.dumps(self.lock).encode())
        self.reports = self.root / "app/build/kotlin-format"
        for name, value in (("ROOT", self.root), ("LOCK", self.lock_path), ("REPORT_DIR", self.reports)):
            manager = patch.object(tree, name, value)
            manager.start()
            self.addCleanup(manager.stop)
        manager = patch.object(tree.inventory, "__defaults__", (self.root, (), ()))
        manager.start()
        self.addCleanup(manager.stop)
        manager = patch.object(tree.subprocess, "run", side_effect=AssertionError("external command forbidden"))
        self.command = manager.start()
        self.addCleanup(manager.stop)

    def put(self, name, data=BEFORE):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def source(self, tail="util/Probe.kt", source_set="main", language="kotlin"):
        path = self.put(f"app/src/{source_set}/{language}/" + PACKAGE + tail)
        return path.relative_to(self.root).as_posix()

    def main(self, mode, *extra):
        with contextlib.redirect_stdout(io.StringIO()):
            return tree.main([mode, "--offline", "--jobs", "1", *extra])

    def names(self, command):
        return command[command.index("--files") + 1:command.index("--report")]

    def fake_formatter(self, command, **kwargs):
        self.assertEqual([sys.executable, str(tree.FORMATTER)], command[:2])
        self.assertEqual(self.root, kwargs["cwd"])
        self.assertTrue(kwargs["capture_output"])
        self.assertTrue(kwargs["text"])
        names = self.names(command)
        report = Path(command[command.index("--report") + 1])
        self.assertTrue(report.is_relative_to(self.reports))
        report.parent.mkdir(parents=True, exist_ok=True)
        entries = []
        for name in names:
            path = self.root / name
            before = path.read_bytes()
            after = before.replace(BEFORE, AFTER)
            if command[2] == "--write":
                path.write_bytes(after)
            entries.append({"file": name, "beforeSha256": hashlib.sha256(before).hexdigest(),
                            "formattedSha256": hashlib.sha256(after).hexdigest(),
                            "needsFormatting": before != after})
        report.write_text(json.dumps({"files": entries}))
        rc = int(command[2] == "--check" and any(e["needsFormatting"] for e in entries))
        return subprocess.CompletedProcess(command, rc, "fixture formatter", "")

    def test_inventory_exact_owned_sets_roots_modules_and_exclusions(self):
        expected = []
        for source_set in self.layout["modules"][0]["formatSourceSets"]:
            for language, suffix in (("java", ".kt"), ("kotlin", ".kts")):
                expected.append(self.source(source_set + language + suffix, source_set, language))
        protected, generated = self.source("Keep.kt"), self.source("Machine.kt")
        sibling = self.source("Keep.kt.extra.kt")
        expected.append(sibling)
        self.source("Bench.kt", "benchmark")
        self.source("NotKotlin.java")
        for part in ("history", "upstream", "vendor", "third_party", "third-party", "generated"):
            self.source(part + "/Foreign.kt")
        self.put("foreign/src/main/kotlin/Foreign.kt")
        self.layout["modules"].append({
            "path": ":policy", "directory": "policy", "kind": "jvm",
            "sourceSets": ["main", "test"], "formatSourceSets": ["main"],
            "testSourceSets": ["test"], "testTask": "test"})
        self.put("ci/source-layout.json", json.dumps(self.layout).encode())
        expected.append("policy/src/main/kotlin/Policy.kt")
        self.put(expected[-1])
        self.put("policy/src/test/kotlin/NotFormatted.kt")
        self.assertEqual(sorted(expected), tree.inventory(protected=[protected], generated=[generated]))
        self.command.assert_not_called()

    def test_inventory_refuses_linked_file_ancestor_and_outside_before_commands(self):
        target = self.put("targets/Probe.kt")
        base = self.root / "app/src/main/kotlin"
        base.mkdir(parents=True)
        for ancestor in (False, True):
            link = base / ("alias" if ancestor else "Alias.kt")
            link.symlink_to(target.parent if ancestor else target, target_is_directory=ancestor)
            with self.subTest(ancestor=ancestor), self.assertRaisesRegex(ValueError, "SOURCE_LAYOUT_LINK"):
                tree.inventory()
            self.assertTrue(link.is_relative_to(self.root))
            link.unlink()
        outside_link = base / "escape"
        outside_link.symlink_to(self.root.parent, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "SOURCE_LAYOUT_OUTSIDE"):
            tree.inventory()
        self.assertEqual(BEFORE, target.read_bytes())
        self.command.assert_not_called()

    def test_package_batches_preserve_sorted_members_keys_and_128_limit(self):
        names = [f"app/src/main/kotlin/{PACKAGE}util/Class{n:03d}.kt" for n in range(300)]
        names += [f"app/src/main/java/{PACKAGE}Root.kts",
                  f"app/src/test/kotlin/{PACKAGE}util/Test.kt"]
        batches = tree.package_batches(list(reversed(names)))
        self.assertEqual(128, tree.MAX_BATCH)
        self.assertEqual([1, 128, 128, 44, 1], [len(files) for _, files in batches])
        self.assertEqual([("main", "(root)"), ("main", "util"), ("main", "util"),
                          ("main", "util"), ("test", "util")], [key for key, _ in batches])
        flattened = [name for _, files in batches for name in files]
        self.assertEqual(len(names), len(flattened))
        self.assertEqual(set(names), set(flattened))
        self.assertTrue(all(files == sorted(files) for _, files in batches))
        with self.assertRaisesRegex(tree.TreeFormatError, "FORMAT_UNEXPECTED_SOURCE"):
            tree.batch_key(f"app/src/main/kotlin/{PACKAGE}NotKotlin.java")

    def test_run_formatter_rc_matrix_and_argument_forwarding(self):
        names = [self.source(), self.source("util/Second.kts")]
        for mode, rc, accepted in (("--check", 0, True), ("--check", 1, True),
                                    ("--check", 2, False), ("--check", -9, False),
                                    ("--write", 0, True), ("--write", 1, False), ("--write", 2, False)):
            def response(command, **kwargs):
                completed = self.fake_formatter(command, **kwargs)
                completed.returncode = rc
                return completed
            self.command.side_effect = response
            with self.subTest(mode=mode, rc=rc):
                if accepted:
                    evidence = tree.run_formatter(mode, names, 7, "/fixture/jdk17", True, "preflight")
                    self.assertEqual(names, [e["file"] for e in evidence["files"]])
                else:
                    with self.assertRaisesRegex(tree.TreeFormatError, "^FORMAT_BATCH_FAILED 7:"):
                        tree.run_formatter(mode, names, 7, "/fixture/jdk17", True, "preflight")
            command = self.command.call_args.args[0]
            self.assertEqual(["--java", "/fixture/jdk17", "--offline"], command[-3:])
            self.assertEqual(self.reports / "tree-preflight-07.json", Path(command[command.index("--report") + 1]))

    def test_report_reordered_missing_duplicate_and_foreign_entries_refused(self):
        names = [self.source(), self.source("util/Second.kt")]
        for report_names in (list(reversed(names)), names[:1], names + [names[0]],
                             [names[0], "foreign/Injected.kt"]):
            def response(command, **kwargs):
                result = self.fake_formatter(command, **kwargs)
                report = Path(command[command.index("--report") + 1])
                report.write_text(json.dumps({"files": [{"file": name} for name in report_names]}))
                return result
            self.command.side_effect = response
            with self.subTest(report_names=report_names), self.assertRaisesRegex(
                    tree.TreeFormatError, "^FORMAT_BATCH_REPORT_MISMATCH$"):
                tree.run_formatter("--check", names, 1, None, True, "preflight")
        self.assertTrue(all((self.root / name).read_bytes() == BEFORE for name in names))

    def test_plan_empty_and_invalid_lock_or_jobs_do_not_spawn(self):
        self.assertEqual(0, self.main("--check"))
        self.source()
        self.assertEqual(0, self.main("--plan"))
        for change, code in (({"schema": 2}, "SCHEMA"), ({"tool": "other"}, "SCHEMA"),
                             ({"protectedFiles": "bad"}, "LISTS"), ({"generatedFiles": {}}, "LISTS")):
            self.lock_path.write_text(json.dumps(dict(self.lock, **change)))
            with self.subTest(change=change), self.assertRaisesRegex(tree.TreeFormatError, "^FORMAT_LOCK_" + code):
                self.main("--check")
        for jobs in (0, 17):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as caught:
                self.main("--check", "--jobs", str(jobs))
            self.assertEqual(2, caught.exception.code)
        self.command.assert_not_called()

    def test_check_write_check_summary_and_sequential_write_batches(self):
        names = [self.source("ui/Ui.kt"), self.source("util/Util.kt")]
        self.command.side_effect = self.fake_formatter
        self.assertEqual(1, self.main("--check"))
        self.assertTrue(all((self.root / name).read_bytes() == BEFORE for name in names))
        summary = json.loads((self.reports / "tree-summary.json").read_text())
        self.assertEqual((2, 2, 2), (summary["files"], summary["batches"], summary["needsFormatting"]))
        self.assertEqual(["tree-preflight-01.json", "tree-preflight-02.json"], summary["reports"])
        self.command.reset_mock()
        self.assertEqual(0, self.main("--write"))
        modes = [call.args[0][2] for call in self.command.call_args_list]
        self.assertEqual(["--check", "--check", "--write", "--write"], modes)
        self.assertTrue(all((self.root / name).read_bytes() == AFTER for name in names))
        summary = json.loads((self.reports / "tree-summary.json").read_text())
        self.assertEqual("write", summary["mode"])
        self.assertEqual(["tree-write-01.json", "tree-write-02.json"], summary["reports"])
        self.assertEqual(0, self.main("--check"))
        self.assertEqual(0, json.loads((self.reports / "tree-summary.json").read_text())["needsFormatting"])

    def test_parallel_completion_does_not_reorder_summary_reports(self):
        first, second = self.source("a/First.kt"), self.source("b/Second.kt")
        second_done = threading.Event()
        completed_order = []

        def delayed(command, **kwargs):
            name = self.names(command)[0]
            if name == first:
                self.assertTrue(second_done.wait(timeout=10), "second batch did not execute concurrently")
            result = self.fake_formatter(command, **kwargs)
            completed_order.append(name)
            if name == second:
                second_done.set()
            return result

        self.command.side_effect = delayed
        self.assertEqual(1, self.main("--check", "--jobs", "2"))
        self.assertEqual([second, first], completed_order)
        summary = json.loads((self.reports / "tree-summary.json").read_text())
        self.assertEqual(["tree-preflight-01.json", "tree-preflight-02.json"], summary["reports"])
        self.assertEqual([first], [e["file"] for e in json.loads((self.reports / summary["reports"][0]).read_text())["files"]])

    def test_preflight_source_change_aborts_all_writes(self):
        name = self.source()
        concurrent = b"class UserEdit\n"

        def race(command, **kwargs):
            result = self.fake_formatter(command, **kwargs)
            (self.root / name).write_bytes(concurrent)
            return result

        self.command.side_effect = race
        with self.assertRaisesRegex(tree.TreeFormatError, "^FORMAT_SOURCE_CHANGED_DURING_PREFLIGHT$"):
            self.main("--write")
        self.assertEqual(["--check"], [call.args[0][2] for call in self.command.call_args_list])
        self.assertEqual(concurrent, (self.root / name).read_bytes())
        self.assertFalse((self.reports / "tree-summary.json").exists())

    def test_source_change_between_write_batches_preserves_later_edit(self):
        first, second = self.source("a/First.kt"), self.source("b/Second.kt")
        concurrent = b"class UserEdit\n"

        def race(command, **kwargs):
            result = self.fake_formatter(command, **kwargs)
            if command[2] == "--write" and self.names(command) == [first]:
                (self.root / second).write_bytes(concurrent)
            return result

        self.command.side_effect = race
        with self.assertRaisesRegex(tree.TreeFormatError, "^FORMAT_SOURCE_CHANGED_BEFORE_WRITE$"):
            self.main("--write")
        self.assertEqual(AFTER, (self.root / first).read_bytes())
        self.assertEqual(concurrent, (self.root / second).read_bytes())
        writes = [self.names(call.args[0]) for call in self.command.call_args_list if call.args[0][2] == "--write"]
        self.assertEqual([[first]], writes)
        self.assertFalse((self.reports / "tree-summary.json").exists())

    def test_postwrite_digest_mismatch_is_not_reported_as_success(self):
        name = self.source()

        def corrupt(command, **kwargs):
            result = self.fake_formatter(command, **kwargs)
            if command[2] == "--write":
                (self.root / name).write_bytes(b"incorrect write\n")
            return result

        self.command.side_effect = corrupt
        with self.assertRaisesRegex(tree.TreeFormatError, "^FORMAT_WRITTEN_BYTES_MISMATCH:"):
            self.main("--write")
        self.assertFalse((self.reports / "tree-summary.json").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
