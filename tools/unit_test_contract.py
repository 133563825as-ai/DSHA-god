#!/usr/bin/env python3
"""全仓 JVM 单测契约：plan → begin → task-begin/task-end → verify → freeze。

只有 SourceLayout.test_tasks() 声明的完整、未过滤运行能使用此入口。旧的
verify-unit-results.verify(reports, mode, ...) 仍服务定向/source-only 汇总。

缺少已冻结历史基线时 verify 必须失败；初次历史清单只从真实 CI 原始 XML 导入。
freeze 重新核对真实 XML 和完整任务证据，并要求 gh run view 的 completed /
success 回执，绝不从历史 summary/counts 反推 testcase。下载 artifact 后 freeze
加 --archived：核验记录时的 mtime 窗口与当前字节摘要；不将解压后的 mtime 冒充执行时间。

输入边界：Java/Kotlin 来自布局解析器；resourceInputs 枚举声明的 module/src/<set>
根直接文件和非代码子树，包含 res/resources/fixtures/aidl/jni/jniLibs/cpp/shaders 等。
未声明的实际源集若有同类输入，即使没有 Java/Kotlin，也必须失败。
唯一资产豁免为 :app 的 app/src/main/assets，由现有固定输入锁与准备契约负责；
其他 assets 根仍受 HEAD 绑定。仓库根 out/local.properties、模块 build/ 不在扫描根。
树外自定义 Gradle 输入路径（含 sourceDirs/manifest 等），以及 java/kotlin 根内非源码
文件，不能由本工具自动发现；集成方必须拒绝此类配置或补充显式输入 owner，不能据此声称已覆盖。

任务 hook 必须传实际 JVM 的 sourceOnlyTests/rust.required/rust.hostLib 和实际 filters；
这些回执是构建进程的证据，不是对恶意修改构建进程的密码学证明。
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
from git_guard import git_output

SCHEMA = 1
BASELINE = "ci/unit-test-baseline.json"
PREFIX = "com.deepseekharness.app."
REQUIRED_FIXTURES = (
    (PREFIX + "runtime.AdbWheelBundleTest", "realBundleRebuildsOnlyOmittedEntriesAndPreservesRestoredWheel"),
    *((PREFIX + "runtime.RuntimeToolsRegistryAndroidTest", name) for name in (
        "completeProductionChainAppliesAllSpecializedStagesAndIsRepeatable",
        "lanRetirementRejectsModuleDriftAndPreservesOriginalBytes",
        "lanRetirementRejectsUnknownVersionAndContractDrift",
        "oldLanExactReplacementAndIdempotenceRemainStrict",
        "previousRuntimeGenerationCannotReceiveCurrentOverlayInPlace")),
    (PREFIX + "util.ManagedPatchRegistryIntegrationTest", "everyActiveModuleRepairsAsAWholeAndRetiredUiNeverRuns"),
)


def fail(code, detail=""):
    raise ValueError(code + (": " + str(detail) if detail else ""))


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def object_sha(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def digest(path):
    value = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def git_blob_sha(path):
    path = Path(path)
    before = path.stat()
    value = hashlib.sha1(b"blob " + str(before.st_size).encode("ascii") + b"\0")
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    after = path.stat()
    if (before.st_size, before.st_mtime_ns, before.st_ctime_ns) != \
            (after.st_size, after.st_mtime_ns, after.st_ctime_ns):
        fail("INPUT_CHANGED_DURING_READ", path)
    return value.hexdigest()


def load(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        fail("CONTRACT_JSON", f"{path}: {error}")


def write_json(path, value, *, exclusive=False):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("x" if exclusive else "w", encoding="utf-8") as stream:
            stream.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    except FileExistsError:
        fail("EVIDENCE_ALREADY_EXISTS", path)


def head(root):
    value = git_output(["rev-parse", "HEAD"], cwd=root, text=True).strip()
    if not re.fullmatch(r"[0-9a-f]{40}", value):
        fail("COMMIT_INVALID", value)
    return value


def within(root, relative):
    """所有报告/清理目标必须是仓库内、无 symlink 的规范相对路径。"""
    root = Path(root).resolve()
    if not isinstance(relative, str) or not relative or "\\" in relative:
        fail("PATH_INVALID", relative)
    parts = PurePosixPath(relative).parts
    if PurePosixPath(relative).is_absolute() or any(p in (".", "..") for p in parts) \
            or PurePosixPath(relative).as_posix() != relative:
        fail("PATH_INVALID", relative)
    current = root
    for part in parts:
        current = current / part
        if current.is_symlink():
            fail("PATH_SYMLINK", relative)
    if not current.resolve().is_relative_to(root):
        fail("PATH_ESCAPE", relative)
    return current


def source_text(text):
    """保留换行与 Kotlin 反引号标识符，去掉注释、字符串里的伪 @Test/@Ignore。"""
    pattern = r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])\''
    return re.sub(pattern, lambda m: re.sub(r"[^\n]", " ", m.group()), text)


def source_contract(text, relative):
    """读取声明身份；执行身份永远取 XML 原文，不把参数/SDK 后缀裁成方法名。"""
    clean = source_text(text)
    if relative.endswith(".kt"):
        if re.search(r"@\s*(?:[A-Za-z_]\w*\s*:\s*)?\[", clean):
            fail("KOTLIN_ANNOTATION_GROUP_UNSUPPORTED", relative)
        for annotation in re.finditer(r"@\s*([A-Za-z_]\w*)\s*:\s*", clean):
            name = re.match(r"[\w.]+", clean[annotation.end():])
            # 只支持下面已解析过文件 facade 的两种注解；其余 use-site 不能吞掉 Test/Ignore。
            if annotation.group(1) != "file" or name is None or name.group() not in (
                    "JvmName", "kotlin.jvm.JvmName", "JvmMultifileClass", "kotlin.jvm.JvmMultifileClass"):
                fail("KOTLIN_USE_SITE_ANNOTATION_UNSUPPORTED", relative)
    package = re.search(r"\bpackage\s+([\w.]+)", clean)
    package = package.group(1) + "." if package else ""
    aliases = {"Test": "Test", "Ignore": "Ignore", "Disabled": "Disabled"}
    for annotation, alias in re.findall(
            r"\bimport\s+(?:org\.junit\.(?:jupiter\.api\.)?)(Test|Ignore|Disabled)\s+as\s+(\w+)", clean):
        aliases[alias] = annotation
    annotations = list(re.finditer(r"@\s*([\w.]+)", clean))
    for annotation in annotations:
        name = annotation.group(1).split(".")[-1]
        if aliases.get(name, name) in ("Ignore", "Disabled"):
            fail("IGNORED_SOURCE", relative)
    # 按大括号区间保留嵌套类的 JVM $ 身份；helper 同样进入源清单。
    classes = []
    depths, depth = [], 0
    for character in clean:
        depths.append(depth)
        depth += (character == "{") - (character == "}")
    declaration_kinds = r"enum\s+class|class|object|interface|enum"
    if relative.endswith(".java"):
        declaration_kinds += "|record"
    for match in re.finditer(r"\b(" + declaration_kinds + r")\s+([A-Za-z_$][\w$]*)", clean):
        parents = [c for c in classes if c["open"] < match.start() < c["end"]]
        # 方法里的 local class 的 JVM 名带编译器编号，不猜它的 binary name。
        # 它仍由所属 helper/test 文件的字节摘要覆盖。
        if parents and depths[match.start()] != depths[parents[-1]["open"]] + 1:
            continue
        owner = (parents[-1]["name"] + "$" if parents else package) + match.group(2)
        opening = clean.find("{", match.end())
        header = clean[match.end():opening] if opening >= 0 else clean[match.end():]
        bodyless = relative.endswith(".kt") and bool(re.search(r"\b(?:fun|val|var|class|object|interface)\b", re.sub(r"\([^()]*\)", "", header)))
        if opening < 0 or bodyless:
            classes.append({"name": owner, "start": match.start(),
                            "open": match.end(), "end": match.end(), "abstract": False})
            continue
        depth, ending = 1, opening + 1
        while ending < len(clean) and depth:
            depth += (clean[ending] == "{") - (clean[ending] == "}")
            ending += 1
        prefix = clean[max(0, match.start() - 100):match.start()]
        abstract = bool(re.search(r"\babstract\s*$", prefix)) or match.group(1) == "interface"
        classes.append({"name": owner, "start": match.start(), "open": opening,
                        "end": ending, "abstract": abstract})
    tests = []
    for annotation in annotations:
        if aliases.get(annotation.group(1).split(".")[-1]) != "Test":
            continue
        position = annotation.end()
        # 跳过 @Test 参数及随后其他注解，避免把 @Config(...) 误认成方法。
        while True:
            while position < len(clean) and clean[position].isspace():
                position += 1
            if position < len(clean) and clean[position] == "(":
                depth = 1
                position += 1
                while position < len(clean) and depth:
                    depth += (clean[position] == "(") - (clean[position] == ")")
                    position += 1
                continue
            other = re.match(r"@\s*[\w.]+", clean[position:])
            if other:
                position += other.end()
                continue
            break
        signature = clean[position:position + 2000]
        method = re.search(r"\bfun\s+(?:<[^>]+>\s*)?(`[^`]+`|[\w$]+)\s*\(", signature)
        if method is None:
            method = re.search(r"\b([\w$]+)\s*\(", signature)
        owners = [c for c in classes if c["open"] < annotation.start() < c["end"]]
        if method is None or not owners or "{" in signature[:method.start()]:
            fail("TEST_DECLARATION_UNSUPPORTED", relative)
        owner = owners[-1]
        tests.append({"classname": owner["name"], "method": method.group(1).strip("`"),
                      "abstract": owner["abstract"]})
    class_names = {c["name"] for c in classes}
    if relative.endswith(".kt"):
        parens, level = [], 0
        for character in clean:
            parens.append(level)
            level += (character == "(") - (character == ")")
        top_level = any(depths[m.start()] == 0 and parens[m.start()] == 0
                        for m in re.finditer(r"\b(?:fun|val|var)\s+", clean))
        if top_level:
            facade = Path(relative).stem
            facade = facade[:1].upper() + facade[1:] + "Kt"
            renamed = re.search(r"@file\s*:\s*(?:kotlin\.jvm\.)?JvmName\s*\(", clean)
            if renamed:
                name = re.match(r'\s*"([A-Za-z_$][\w$]*)"', text[renamed.end():])
                if name is None:
                    fail("KOTLIN_FACADE_UNSUPPORTED", relative)
                facade = name.group(1)
            if re.search(r"@file\s*:\s*(?:kotlin\.jvm\.)?JvmMultifileClass\b", clean):
                fail("KOTLIN_MULTIFILE_UNSUPPORTED", relative)
            class_names.add(package + facade)
    return {"classes": sorted(class_names), "tests": tests}


def source_set_roots(layout, input_sources):
    result = []
    for module in layout.modules:
        source_sets = module.get("sourceSets")
        if source_sets is None:
            # 注入式布局可省略完整 schema；仍覆盖 main、测试及实际源码所在源集。
            source_sets = {"main", *module.get("testSourceSets", []),
                           *(s["sourceSet"] for s in input_sources if s["module"] == module["path"])}
        if not isinstance(source_sets, (list, tuple, set)) or not source_sets \
                or any(not isinstance(value, str) or not re.fullmatch(r"[A-Za-z][\w]*", value)
                       for value in source_sets):
            fail("RESOURCE_SOURCE_SETS", module["path"])
        for source_set in sorted(set(source_sets)):
            result.append({"module": module["path"], "sourceSet": source_set,
                           "path": f"{module['directory']}/src/{source_set}",
                           "fixedAssetOwner": (module["path"], module["directory"], source_set)
                           == (":app", "app", "main")})
    return result


def resource_input_path(relative, roots):
    for owner in roots:
        prefix = owner["path"] + "/"
        if relative.startswith(prefix):
            first = relative[len(prefix):].split("/", 1)[0]
            return first not in ("java", "kotlin") and not (first == "assets" and owner["fixedAssetOwner"])
    return False


def source_set_resource_files(root, owner):
    directory = within(root, owner["path"])
    if not directory.exists():
        return
    if not directory.is_dir():
        fail("RESOURCE_DIRECTORY", owner["path"])
    for child in sorted(directory.iterdir()):
        if not resource_input_path(child.relative_to(root).as_posix(), [owner]):
            continue
        within(root, child.relative_to(root).as_posix())
        child_directory = child.is_dir()
        paths = sorted(child.rglob("*")) if child_directory else [child]
        for path in paths:
            relative = path.relative_to(root).as_posix()
            within(root, relative)
            if path.is_dir():
                continue
            if not path.is_file():
                fail("RESOURCE_NONFILE", relative)
            yield path, child.name if child_directory else ".", path.relative_to(
                child if child_directory else directory).as_posix()


def resource_inputs(root, layout, input_sources):
    """源集根文件及非代码子树；唯一豁免为现有固定输入 owner 的 app/main/assets。"""
    roots = source_set_roots(layout, input_sources)
    for module in layout.modules:
        declared = {owner["sourceSet"] for owner in roots if owner["module"] == module["path"]}
        source_directory = within(root, module["directory"] + "/src")
        if not source_directory.exists():
            continue
        if not source_directory.is_dir():
            fail("RESOURCE_DIRECTORY", source_directory)
        for directory in sorted(source_directory.iterdir()):
            within(root, directory.relative_to(root).as_posix())
            if not directory.is_dir() or directory.name in declared:
                continue
            # AGP 自动消费的变体源集可能只有资源；不能只删布局声明来隐藏它。
            owner = {"module": module["path"], "sourceSet": directory.name,
                     "path": directory.relative_to(root).as_posix(), "fixedAssetOwner": False}
            for path, kind, logical in source_set_resource_files(root, owner):
                fail("UNDECLARED_RESOURCE_SOURCE_SET", path.relative_to(root).as_posix())
    result = []
    for owner in roots:
        for path, kind, logical in source_set_resource_files(root, owner):
            result.append({"module": owner["module"], "sourceSet": owner["sourceSet"],
                           "resourceRoot": kind, "path": path.relative_to(root).as_posix(),
                           "logicalPath": logical, "sha256": digest(path)})
    return sorted(result, key=lambda item: item["path"])


def plan(root, layout=None):
    root = Path(root).resolve()
    if layout is None:
        from source_layout import SourceLayout
        layout = SourceLayout(root)
    tasks = layout.test_tasks()
    if not isinstance(tasks, list) or not tasks:
        fail("TASK_PLAN_EMPTY")
    selected, names, reports = set(), set(), set()
    normalized = []
    for row in tasks:
        module, task, report = row.get("module"), row.get("task"), row.get("reports")
        sets = row.get("sourceSets")
        if not isinstance(module, str) or not re.fullmatch(r":[\w-]+(?::[\w-]+)*", module) \
                or not isinstance(task, str) or not task.startswith(module + ":") \
                or not re.fullmatch(r":[\w:-]+", task) \
                or not isinstance(sets, list) or not sets or len(set(sets)) != len(sets):
            fail("TASK_PLAN_SCHEMA", row)
        # 清理只允许标准 build/test-results/<本任务名>；不接受由清单注入任意删除目标。
        report_path = PurePosixPath(report or "")
        suffix = ("build", "test-results", task.rsplit(":", 1)[1])
        if report_path.parts[-3:] != suffix:
            fail("TASK_REPORT_PATH", row)
        within(root, report)
        if task in names or report in reports:
            fail("DUPLICATE_TASK_OR_REPORT", task)
        names.add(task)
        reports.add(report)
        selected.update(sets)
        normalized.append({"module": module, "task": task, "reports": report,
                           "sourceSets": sorted(sets)})
    sources, seen, logical = [], set(), set()
    for source in layout.source_files(source_sets=sorted(selected), suffixes=(".java", ".kt")):
        if source.source_set not in selected:
            fail("SOURCE_SET_UNEXPECTED", source.relative)
        path = within(root, source.relative)
        if Path(source.path).resolve() != path.resolve() or not path.is_file():
            fail("SOURCE_PATH", source.relative)
        if path.suffix not in (".java", ".kt"):
            fail("SOURCE_SUFFIX", source.relative)
        owners = [t for t in normalized if t["module"] == source.module
                  and source.source_set in t["sourceSets"]]
        if not owners:
            fail("SOURCE_WITHOUT_TASK", source.relative)
        key = source.source_set + "/" + source.logical_path
        if source.relative in seen or key in logical:
            fail("DUPLICATE_SOURCE", source.relative)
        seen.add(source.relative)
        logical.add(key)
        contract = source_contract(path.read_text(encoding="utf-8"), source.relative)
        sources.append({"module": source.module, "sourceSet": source.source_set,
                        "path": source.relative, "logicalPath": source.logical_path,
                        "sha256": digest(path), **contract})
    if not sources or not any(s["tests"] for s in sources):
        fail("SOURCE_TESTS_EMPTY")
    input_sources = []
    declared_test_sets = {value for module in getattr(layout, "modules", [])
                          for value in module.get("testSourceSets", [])}
    # 全清单还有其他 test 源集时不能通过缩小任务集合把它隐藏。
    for source in layout.source_files(source_sets=None, suffixes=(".java", ".kt")):
        if (source.source_set in declared_test_sets or source.source_set == "test"
                or source.source_set.startswith("test")) and source.relative not in seen:
            fail("SOURCE_WITHOUT_TASK", source.relative)
        path = within(root, source.relative)
        input_sources.append({"module": source.module, "sourceSet": source.source_set,
                              "path": source.relative, "logicalPath": source.logical_path,
                              "sha256": digest(path)})
    for task in normalized:
        if not any(s["module"] == task["module"] and s["sourceSet"] in task["sourceSets"] for s in sources):
            fail("TASK_WITHOUT_SOURCES", task["task"])
    return {"schema": SCHEMA, "kind": "dsha-unit-plan", "mode": "full",
            "tasks": sorted(normalized, key=lambda t: t["task"]),
            "sources": sorted(sources, key=lambda s: s["path"]),
            "inputSources": sorted(input_sources, key=lambda s: s["path"]),
            "resourceInputs": resource_inputs(root, layout, input_sources)}


def report_files(root, task):
    directory = within(root, task["reports"])
    if directory.exists() and not directory.is_dir():
        fail("REPORT_DIRECTORY", directory)
    files = sorted(directory.glob("*.xml"))
    for path in files:
        within(root, path.relative_to(root).as_posix())
        if not path.is_file() or not path.name.startswith("TEST-"):
            fail("REPORT_FILE", path)
    return files


def file_evidence(root, path):
    path = Path(path)
    before = path.stat()
    sha = digest(path)
    after = path.stat()
    if (before.st_size, before.st_mtime_ns, before.st_ctime_ns) != \
            (after.st_size, after.st_mtime_ns, after.st_ctime_ns):
        fail("REPORT_CHANGED_DURING_READ", path)
    return {"path": path.relative_to(root).as_posix(), "sha256": sha, "size": after.st_size,
            "mtimeNs": after.st_mtime_ns, "ctimeNs": after.st_ctime_ns}


def rust_evidence(root, library, receipt, commit, run_id, attempt):
    if not library or not receipt:
        fail("RUST_REQUIRED", "full 需要主机库与真实 build.py receipt")
    library = Path(library)
    receipt = Path(receipt)
    if not library.is_absolute():
        library = root / library
    if not receipt.is_absolute():
        receipt = root / receipt
    if not library.is_file() or library.is_symlink() or library.stat().st_size == 0:
        fail("RUST_LIBRARY", library)
    body = load(receipt)
    if body.get("kind") != "dsha-rust-native" or body.get("target") != "host" \
            or body.get("commit") != commit or str(body.get("runId")) != run_id \
            or str(body.get("runAttempt")) != attempt:
        fail("RUST_RECEIPT_IDENTITY")
    artifact = body.get("artifact", {})
    if artifact.get("sha256") != digest(library) or artifact.get("size") != library.stat().st_size \
            or artifact.get("artifactFile") != library.name:
        fail("RUST_RECEIPT_ARTIFACT")
    cargo = root / "rust/Cargo.lock"
    if cargo.is_file() and body.get("sources", {}).get("cargoLockSha256") != digest(cargo):
        fail("RUST_RECEIPT_SOURCE")
    return {"library": str(library.resolve()), "sha256": artifact["sha256"],
            "size": artifact["size"], "receipt": body, "receiptSha256": digest(receipt)}


def commit_blobs(root, commit):
    entries = {}
    for row in git_output(["ls-tree", "-r", "-z", commit], cwd=root).split(b"\0"):
        if not row:
            continue
        header, name = row.split(b"\t", 1)
        mode, kind, sha = header.split()
        if kind == b"blob":
            entries[name.decode("utf-8")] = sha.decode("ascii")
    return entries


def verify_checkout(root, planned, commit):
    """完整回执绑定实际 HEAD；也逐项拒绝未跟踪/ignored 的源集输入。"""
    changed = git_output(["diff", "--name-only", "-z", commit, "--"], cwd=root).decode("utf-8").split("\0")
    changed = [name for name in changed if name]
    if changed:
        fail("WORKTREE_DIRTY", changed)
    entries = commit_blobs(root, commit)
    for source in planned["inputSources"] + planned["resourceInputs"]:
        if entries.get(source["path"]) != git_blob_sha(within(root, source["path"])):
            fail("INPUT_NOT_IN_COMMIT", source["path"])


def begin(root, plan_path, state, run_id, run_attempt="1", *, rust_library=None, rust_receipt=None, layout=None):
    root, state = Path(root).resolve(), Path(state).resolve()
    if state.exists():
        fail("RUN_ALREADY_EXISTS", state)
    if not run_id or not re.fullmatch(r"[A-Za-z0-9_.-]+", str(run_id)) \
            or not re.fullmatch(r"[1-9][0-9]*", str(run_attempt)):
        fail("RUN_ID_INVALID")
    expected = plan(root, layout)
    if load(plan_path) != expected:
        fail("PLAN_DRIFT")
    commit = head(root)
    verify_checkout(root, expected, commit)
    rust = rust_evidence(root, rust_library, rust_receipt, commit, str(run_id), str(run_attempt))
    started = time.time_ns()
    prior = [file_evidence(root, path) for task in expected["tasks"] for path in report_files(root, task)]
    # 先核对每个绝对目标，再只删除任务报告；源文件、binary/、HTML 均不触碰。
    for row in prior:
        target = within(root, row["path"])
        if not any(target.parent == within(root, task["reports"]) for task in expected["tasks"]) \
                or not target.name.startswith("TEST-") or target.suffix != ".xml":
            fail("REPORT_CLEAN_TARGET", target)
    state.mkdir(parents=True)
    clock = state / "clock.json"
    write_json(clock, {"commit": commit, "runId": str(run_id), "runAttempt": str(run_attempt),
                       "startedNs": started}, exclusive=True)
    run = {"schema": SCHEMA, "kind": "dsha-unit-run", "commit": commit,
           "runId": str(run_id), "runAttempt": str(run_attempt), "startedNs": started,
           "reportClockStartNs": clock.stat().st_mtime_ns, "clockSha256": digest(clock),
           "plan": expected, "planSha256": object_sha(expected), "rust": rust,
           "priorReports": prior}
    write_json(state / "run.json", run, exclusive=True)
    for row in prior:
        within(root, row["path"]).unlink()
    return run


def read_run(root, state, layout=None):
    root, state = Path(root).resolve(), Path(state).resolve()
    run = load(state / "run.json")
    if run.get("kind") != "dsha-unit-run" or run.get("schema") != SCHEMA:
        fail("RUN_SCHEMA")
    clock = load(state / "clock.json")
    if digest(state / "clock.json") != run.get("clockSha256") or any(
            clock.get(key) != run.get(key) for key in ("commit", "runId", "runAttempt", "startedNs")):
        fail("RUN_CLOCK")
    if head(root) != run.get("commit"):
        fail("RUN_COMMIT")
    expected = plan(root, layout)
    if run.get("planSha256") != object_sha(expected) or run.get("plan") != expected:
        fail("PLAN_DRIFT", "任务、源码或声明源集资源已变化")
    verify_checkout(root, expected, run["commit"])
    return root, state, run


def task_record(state, task, stage):
    return state / "tasks" / (hashlib.sha256(task.encode("utf-8")).hexdigest() + "." + stage + ".json")


def task_row(run, task):
    rows = [row for row in run["plan"]["tasks"] if row["task"] == task]
    if len(rows) != 1:
        fail("TASK_UNPLANNED", task)
    return rows[0]


def binding(run, task):
    return {"commit": run["commit"], "runId": run["runId"], "runAttempt": run["runAttempt"],
            "planSha256": run["planSha256"], "task": task}


def class_directory_evidence(root, plan_value, task, directories, *, includes, excludes, scan):
    if not isinstance(includes, list) or not isinstance(excludes, list) or includes or excludes \
            or scan is not True:
        fail("FULL_TASK_CLASS_FILTER", task["task"])
    if not isinstance(directories, list) or not directories:
        fail("TEST_CLASS_DIRECTORIES", task["task"])
    module_build = within(root, task["reports"]).parents[1]
    outputs, names = [], Counter()
    for value in directories:
        if not isinstance(value, str) or not value:
            fail("TEST_CLASS_DIRECTORIES", value)
        directory = Path(value)
        if not directory.is_absolute():
            directory = root / directory
        if not directory.resolve().is_relative_to(module_build):
            fail("TEST_CLASS_DIRECTORY_OUTSIDE_MODULE", directory)
        within(root, directory.relative_to(root).as_posix())
        classes = []
        if directory.exists():
            if not directory.is_dir():
                fail("TEST_CLASS_DIRECTORIES", directory)
            for path in sorted(directory.rglob("*.class")):
                within(root, path.relative_to(root).as_posix())
                name = path.relative_to(directory).as_posix()[:-6].replace("/", ".")
                names[name] += 1
                classes.append({"name": name, "sha256": digest(path)})
        outputs.append({"path": directory.relative_to(root).as_posix(),
                        "exists": directory.is_dir(), "classes": classes})
    sources = [s for s in plan_value["sources"] if s["module"] == task["module"]
               and s["sourceSet"] in task["sourceSets"]]
    for source in sources:
        for name in source["classes"]:
            if names[name] != 1:
                fail("SOURCE_CLASS_NOT_COMPILED", f"{source['path']}: {name} count={names[name]}")
    return outputs


def task_begin(root, state, task, *, source_only, filters, rust_required, rust_library,
               includes, excludes, scan_for_test_classes, test_classes_dirs, layout=None):
    root, state, run = read_run(root, state, layout)
    row = task_row(run, task)
    if source_only is not False or filters != [] or rust_required is not True:
        fail("FULL_TASK_CONFIGURATION", task)
    if not rust_library:
        fail("RUST_REQUIRED", task)
    library = Path(rust_library)
    if not library.is_absolute():
        library = root / library
    if not library.is_file() or library.is_symlink() or digest(library) != run["rust"]["sha256"] \
            or str(library.resolve()) != run["rust"]["library"]:
        fail("TASK_RUST_LIBRARY", task)
    if report_files(root, row):
        fail("TASK_REPORTS_NOT_EMPTY", task)
    directories = class_directory_evidence(root, run["plan"], row, test_classes_dirs,
                                           includes=includes, excludes=excludes,
                                           scan=scan_for_test_classes)
    started = time.time_ns()
    clock = task_record(state, task, "clock-start")
    write_json(clock, {**binding(run, task), "wallNs": started}, exclusive=True)
    record = {"schema": SCHEMA, "kind": "dsha-unit-task-start", **binding(run, task),
              "startedNs": started, "reportClockStartNs": clock.stat().st_mtime_ns,
              "clockSha256": digest(clock), "sourceOnly": source_only, "filters": filters,
              "rustRequired": rust_required, "rustSha256": digest(library),
              "includes": includes, "excludes": excludes,
              "scanForTestClasses": scan_for_test_classes, "classDirectories": directories,
              "reportsEmpty": True}
    write_json(task_record(state, task, "start"), record, exclusive=True)
    return record


def parse_xml(path):
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as error:
        fail("JUNIT_XML", f"{path}: {error}")
    if root.tag not in ("testsuite", "testsuites"):
        fail("JUNIT_SCHEMA", path)
    cases = list(root.iter("testcase"))
    if not cases:
        fail("ZERO_TEST_REPORT", path)
    # 每一层声明计数均独立核对，不能把一份含失败的 XML 藏在额外 testsuite 里。
    for suite in root.iter():
        if suite.tag not in ("testsuite", "testsuites"):
            continue
        contained = list(suite.iter("testcase"))
        actual = {"tests": len(contained), "failures": sum(c.find("failure") is not None for c in contained),
                  "errors": sum(c.find("error") is not None for c in contained),
                  "skipped": sum(c.find("skipped") is not None for c in contained)}
        for key, value in actual.items():
            if suite.tag == "testsuites" and key not in suite.attrib:
                continue
            try:
                declared = int(suite.get(key, "0"))
            except ValueError:
                fail("JUNIT_COUNTS", path)
            if declared != value:
                fail("JUNIT_COUNTS", f"{path}: {key}={declared}, actual={value}")
    identities = []
    for case in cases:
        owner, name = case.get("classname"), case.get("name")
        if not owner or not name or not owner.strip() or not name.strip():
            fail("JUNIT_IDENTITY", path)
        statuses = [tag for tag in ("failure", "error", "skipped") if case.find(tag) is not None]
        if statuses:
            fail("FULL_UNIT_TESTS_FAILED", {"classname": owner, "name": name, "status": statuses})
        identities.append((owner, name))
    return identities


def fresh_xml(record, started, ended):
    # 同文件系统探针消除 kernel coarse clock 与 wall clock 的微小偏差；没有任意宽限秒数。
    for key in ("mtimeNs", "ctimeNs"):
        if not isinstance(record.get(key), int) or not started <= record[key] <= ended:
            fail("STALE_XML", record.get("path"))


def task_end(root, state, task, *, outcome, layout=None):
    root, state, run = read_run(root, state, layout)
    row = task_row(run, task)
    start = load(task_record(state, task, "start"))
    for key, value in binding(run, task).items():
        if start.get(key) != value:
            fail("TASK_BINDING", task)
    if outcome != "SUCCESS":
        fail("TASK_NOT_EXECUTED", f"{task}: {outcome}")
    files = report_files(root, row)
    if not files:
        fail("JUNIT_MISSING", task)
    reports = []
    for path in files:
        parse_xml(path)
        reports.append(file_evidence(root, path))
    clock = task_record(state, task, "clock-end")
    write_json(clock, {**binding(run, task), "wallNs": time.time_ns()}, exclusive=True)
    clock_ended = clock.stat().st_mtime_ns
    ended = time.time_ns()
    for report in reports:
        fresh_xml(report, start["reportClockStartNs"], clock_ended)
    record = {"schema": SCHEMA, "kind": "dsha-unit-task-end", **binding(run, task),
              "startSha256": digest(task_record(state, task, "start")),
              "reportClockEndNs": clock_ended, "clockSha256": digest(clock),
              "startedNs": start["startedNs"], "endedNs": ended, "outcome": outcome,
              "reports": reports}
    write_json(task_record(state, task, "end"), record, exclusive=True)
    return record


def method_matches(name, method):
    # 仅用于声明→执行覆盖检查；身份清单始终保存完整 XML name。
    return name == method or name.startswith(method + "[") or name.startswith(method + "(")


def coverage(plan_value, task, cases):
    sources = [s for s in plan_value["sources"] if s["module"] == task["module"]
               and s["sourceSet"] in task["sourceSets"]]
    owners = {name for source in sources for name in source["classes"]}
    for owner, name in cases:
        if owner not in owners:
            fail("REPORTED_CLASS_WITHOUT_SOURCE", f"{task['task']}: {owner}.{name}")
    for source in sources:
        for test in source["tests"]:
            if test["abstract"]:
                fail("ABSTRACT_TEST_UNSUPPORTED", source["path"])
            if not any(owner == test["classname"] and method_matches(name, test["method"])
                       for owner, name in cases):
                fail("SOURCE_TEST_NOT_EXECUTED", f"{source['path']}: {test['classname']}.{test['method']}")


def baseline_check(path, identities, sources):
    body = load(path)
    if body.get("schema") != SCHEMA or body.get("kind") != "dsha-unit-test-baseline":
        fail("BASELINE_SCHEMA")
    if body.get("status") == "awaiting-real-full-ci":
        fail("BASELINE_PENDING", "需要真实完整 CI 原始 XML；不允许免除历史身份对照")
    if body.get("status") != "frozen":
        fail("BASELINE_STATUS")
    ci_provenance(body.get("provenance", {}))
    old = body.get("identities")
    if not isinstance(old, list) or not old or any(not isinstance(r, list) or len(r) != 2
                                                 or not all(isinstance(v, str) and v for v in r) for r in old):
        fail("BASELINE_IDENTITIES")
    if len({tuple(r) for r in old}) != len(old):
        fail("BASELINE_DUPLICATE")
    if body.get("tests") != len(old) or any(body.get(key) != 0 for key in ("failures", "errors", "skipped")) \
            or body.get("requiredFixtureTestsPassed") != len(REQUIRED_FIXTURES):
        fail("BASELINE_COUNTS")
    missing = sorted(set(map(tuple, old)) - set(identities))
    if missing:
        fail("BASELINE_TEST_MISSING", missing)
    # helper、Kotlin 也必须保留；物理模块位置改变不改变逻辑源键。
    current = {(s["sourceSet"], s["logicalPath"]) for s in sources}
    previous = body.get("sources")
    if not isinstance(previous, list) or not previous:
        fail("BASELINE_SOURCES")
    lost = [(s.get("sourceSet"), s.get("logicalPath")) for s in previous
            if (s.get("sourceSet"), s.get("logicalPath")) not in current]
    if lost:
        fail("BASELINE_SOURCE_MISSING", lost)
    return "matched"


def verify_run(root, state, *, run_id=None, run_attempt=None, baseline=None,
               archived=False, layout=None, _freezing=False):
    root, state, run = read_run(root, state, layout)
    if run_id is not None and str(run_id) != run["runId"] \
            or run_attempt is not None and str(run_attempt) != run["runAttempt"]:
        fail("RUN_IDENTITY")
    if run["startedNs"] > time.time_ns():
        fail("RUN_START_IN_FUTURE")
    expected_files, tasks, identities = set(), [], []
    for task in run["plan"]["tasks"]:
        name = task["task"]
        start_path, end_path = task_record(state, name, "start"), task_record(state, name, "end")
        expected_files.update((start_path.name, end_path.name))
        for stage in ("clock-start", "clock-end"):
            expected_files.add(task_record(state, name, stage).name)
        if not start_path.is_file() or not end_path.is_file():
            fail("TASK_EVIDENCE_MISSING", name)
        start, end = load(start_path), load(end_path)
        for stage, record in (("clock-start", start), ("clock-end", end)):
            clock_path = task_record(state, name, stage)
            clock = load(clock_path)
            if record.get("clockSha256") != digest(clock_path) or any(
                    clock.get(key) != value for key, value in binding(run, name).items()):
                fail("TASK_CLOCK", name)
        if not run["reportClockStartNs"] <= start["reportClockStartNs"] <= end["reportClockEndNs"]:
            fail("TASK_CLOCK", name)
        for record in (start, end):
            for key, value in binding(run, name).items():
                if record.get(key) != value:
                    fail("TASK_BINDING", name)
        if start.get("kind") != "dsha-unit-task-start" or end.get("kind") != "dsha-unit-task-end" \
                or start.get("schema") != SCHEMA or end.get("schema") != SCHEMA:
            fail("TASK_SCHEMA", name)
        if start.get("sourceOnly") is not False or start.get("filters") != [] \
                or start.get("rustRequired") is not True or start.get("reportsEmpty") is not True \
                or start.get("rustSha256") != run["rust"]["sha256"] \
                or start.get("includes") != [] or start.get("excludes") != [] \
                or start.get("scanForTestClasses") is not True:
            fail("FULL_TASK_CONFIGURATION", name)
        compiled = Counter(c["name"] for directory in start.get("classDirectories", [])
                           for c in directory.get("classes", []))
        for source in run["plan"]["sources"]:
            if source["module"] == task["module"] and source["sourceSet"] in task["sourceSets"]:
                for owner in source["classes"]:
                    if compiled[owner] != 1:
                        fail("SOURCE_CLASS_NOT_COMPILED", owner)
        if end.get("startSha256") != digest(start_path) or end.get("outcome") != "SUCCESS":
            fail("TASK_NOT_EXECUTED", name)
        if not isinstance(start.get("startedNs"), int) or not isinstance(end.get("endedNs"), int) \
                or not run["startedNs"] <= start["startedNs"] <= end["endedNs"] <= time.time_ns() \
                or end.get("startedNs") != start["startedNs"]:
            fail("TASK_TIME", name)
        files = report_files(root, task)
        recorded = end.get("reports")
        if not files or not isinstance(recorded, list) or not recorded:
            fail("JUNIT_MISSING", name)
        by_path = {r["path"]: r for r in recorded}
        if len(by_path) != len(recorded) or set(by_path) != {p.relative_to(root).as_posix() for p in files}:
            fail("XML_SET_CHANGED", name)
        task_cases = []
        for path in files:
            row = by_path[path.relative_to(root).as_posix()]
            fresh_xml(row, start["reportClockStartNs"], end["reportClockEndNs"])
            current = file_evidence(root, path)
            keys = ("sha256", "size") if archived else ("sha256", "size", "mtimeNs", "ctimeNs")
            if any(current[key] != row[key] for key in keys):
                fail("XML_CHANGED", row["path"])
            task_cases.extend(parse_xml(path))
        coverage(run["plan"], task, task_cases)
        identities.extend(task_cases)
        tasks.append({"module": task["module"], "task": name, "tests": len(task_cases),
                      "reports": recorded, "evidenceSha256": digest(end_path)})
    actual_files = {p.name for p in (state / "tasks").iterdir()}
    if actual_files != expected_files:
        fail("TASK_EVIDENCE_SET", sorted(actual_files ^ expected_files))
    duplicates = [pair for pair, count in Counter(identities).items() if count != 1]
    if duplicates:
        fail("DUPLICATE_TEST_IDENTITY", duplicates)
    if not identities:
        fail("ZERO_TESTS")
    for pair in REQUIRED_FIXTURES:
        if pair not in identities:
            fail("REQUIRED_FIXTURE", pair)
    if not any(owner == PREFIX + "util.RustDigestDifferentialTest" for owner, name in identities):
        fail("RUST_TESTS_MISSING")
    status = "freeze-candidate" if _freezing else baseline_check(
        Path(baseline) if baseline else root / BASELINE, identities, run["plan"]["sources"])
    return {"schema": SCHEMA, "kind": "dsha-unit-aggregate", "mode": "full",
            "commit": run["commit"], "runId": run["runId"], "runAttempt": run["runAttempt"],
            "startedNs": run["startedNs"], "verifiedNs": time.time_ns(),
            "planSha256": run["planSha256"], "baselineStatus": status,
            "freshness": "recorded-ci-artifact" if archived else "live-task-evidence",
            "tests": len(identities), "failures": 0, "errors": 0, "skipped": 0,
            "requiredFixtureTestsPassed": len(REQUIRED_FIXTURES), "rustRequired": True,
            "rust": run["rust"], "tasks": tasks, "identities": [list(p) for p in sorted(identities)],
            "sources": run["plan"]["sources"]}


def preserve_baseline(output, body):
    if not Path(output).exists():
        return
    previous = load(output)
    if previous.get("kind") != "dsha-unit-test-baseline" or previous.get("schema") != SCHEMA:
        fail("BASELINE_SCHEMA")
    if previous.get("status") == "frozen":
        baseline_check(output, list(map(tuple, body["identities"])), body["sources"])
    elif previous.get("status") == "awaiting-real-full-ci":
        pending = previous.get("pendingProvenance")
        if pending:
            ci_provenance(body["provenance"], commit=pending["commit"], run_id=pending["runId"],
                          attempt=pending["runAttempt"])
    else:
        fail("BASELINE_STATUS")


def freeze(root, state, ci_run, output, *, archived=False, layout=None):
    # summary 无论声称多少通过数，都不能进入此入口。
    result = verify_run(root, state, archived=archived, layout=layout, _freezing=True)
    ci = ci_metadata(load(ci_run))
    ci_provenance(ci, commit=result["commit"], run_id=result["runId"], attempt=result["runAttempt"])
    body = {"schema": SCHEMA, "kind": "dsha-unit-test-baseline", "status": "frozen",
            "provenance": ci, "runStartedNs": result["startedNs"],
            "planSha256": result["planSha256"], "identityFormat": ["classname", "name"],
            "identities": result["identities"], "sources": result["sources"],
            "tasks": result["tasks"], "rust": result["rust"],
            "tests": result["tests"], "failures": 0, "errors": 0, "skipped": 0,
            "requiredFixtureTestsPassed": result["requiredFixtureTestsPassed"]}
    preserve_baseline(output, body)
    write_json(output, body)
    return body


def ci_metadata(body):
    """兼容 gh run view JSON 与 GitHub REST 原始字段，不要求手改成功回执。"""
    return {"databaseId": body.get("databaseId", body.get("id")),
            "headSha": body.get("headSha", body.get("head_sha")),
            "attempt": body.get("attempt", body.get("runAttempt", body.get("run_attempt"))),
            "status": body.get("status"), "conclusion": body.get("conclusion"),
            "url": body.get("html_url", body.get("url"))}


def ci_provenance(ci, *, commit=None, run_id=None, attempt=None):
    found_attempt = ci.get("attempt", ci.get("runAttempt"))
    identifier = str(ci.get("databaseId", ""))
    if ci.get("status") != "completed" or ci.get("conclusion") != "success" \
            or not re.fullmatch(r"[1-9][0-9]*", identifier) \
            or not re.fullmatch(r"[1-9][0-9]*", str(found_attempt)) \
            or not re.fullmatch(r"[0-9a-f]{40}", str(ci.get("headSha", ""))) \
            or (commit is not None and ci.get("headSha") != commit) \
            or (run_id is not None and identifier != str(run_id)) \
            or (attempt is not None and str(found_attempt) != str(attempt)) \
            or not isinstance(ci.get("url"), str) \
            or not re.fullmatch(r"https://github\.com/[^/]+/[^/]+/actions/runs/" + identifier, ci["url"]):
        fail("BASELINE_CI_PROVENANCE", "需要匹配 commit/run/attempt 的成功完整 CI 回执")
    return identifier, str(found_attempt)


def archive_members(root, archive, xml_files, receipt):
    """不解压 ZIP；把已读取 XML/summary 的字节重新绑定到 API 摘要对应的原归档。"""
    try:
        with zipfile.ZipFile(archive) as source:
            names = source.namelist()
            if len(set(names)) != len(names):
                fail("CI_ARCHIVE_DUPLICATE_MEMBER")
            for name in names:
                plain = name.rstrip("/")
                parts = PurePosixPath(plain).parts
                if not plain or "\\" in name or PurePosixPath(plain).is_absolute() \
                        or ".." in parts or PurePosixPath(plain).as_posix() != plain:
                    fail("CI_ARCHIVE_MEMBER", name)
            xml_names = {name for name in names if "/build/test-results/" in name and name.endswith(".xml")}
            if xml_names != {row["path"] for row in xml_files}:
                fail("CI_ARCHIVE_XML_SET")
            for row in xml_files:
                body = source.read(row["path"])
                if hashlib.sha256(body).hexdigest() != row["sha256"] or len(body) != row["size"]:
                    fail("CI_ARCHIVE_XML_CHANGED", row["path"])
            try:
                name = Path(receipt).resolve().relative_to(root).as_posix()
            except ValueError:
                fail("CI_ARCHIVE_RECEIPT")
            if name not in names or hashlib.sha256(source.read(name)).hexdigest() != digest(receipt):
                fail("CI_ARCHIVE_RECEIPT")
    except (zipfile.BadZipFile, KeyError) as error:
        fail("CI_ARTIFACT_ARCHIVE", error)


def import_ci_baseline(root, plan_path, ci_run, reports_root, unit_receipt, run_log, artifact,
                       output, *, archive, layout=None):
    """历史导入只有 baseline 产物；不能生成 run/task 证据或候选 freshness PASS。"""
    root, reports_root = Path(root).resolve(), Path(reports_root).resolve()
    expected = plan(root, layout)
    if load(plan_path) != expected:
        fail("PLAN_DRIFT")
    ci = ci_metadata(load(ci_run))
    identifier, attempt = ci_provenance(ci)
    commit = ci["headSha"]
    # 不能把当前新增/遗漏的源文件追认给旧 commit；Java/Kotlin/helper 逐字节核对。
    blobs = commit_blobs(root, commit)
    names = blobs.keys()
    if layout is None:
        from source_layout import SourceLayout
        modules = SourceLayout(root).modules
    else:
        modules = layout.modules
    prefixes = tuple(module["directory"] + "/src/" + source_set + "/" + language + "/"
                     for module in modules for source_set in module["testSourceSets"]
                     for language in ("java", "kotlin"))
    historical = {name for name in names if name.startswith(prefixes)
                  and PurePosixPath(name).suffix in (".java", ".kt")}
    current = {s["path"] for s in expected["sources"]}
    if historical != current:
        fail("HISTORICAL_SOURCE_SET", sorted(historical ^ current))
    roots = source_set_roots(layout if layout is not None else SourceLayout(root), expected["inputSources"])
    historical_resources = {name for name in names if resource_input_path(name, roots)}
    if historical_resources != {item["path"] for item in expected["resourceInputs"]}:
        fail("HISTORICAL_RESOURCE_SET", sorted(historical_resources ^ {item["path"] for item in expected["resourceInputs"]}))
    for source in expected["inputSources"] + expected["resourceInputs"]:
        if blobs.get(source["path"]) != git_blob_sha(within(root, source["path"])):
            fail("HISTORICAL_SOURCE_CHANGED", source["path"])
    # GitHub API artifact 条目来自同一次 run，保存可追溯 id/name/digest。
    metadata = load(artifact)
    if isinstance(metadata.get("artifacts"), list):
        selected = [row for row in metadata["artifacts"] if row.get("digest") == "sha256:" + digest(archive)]
        if len(selected) != 1:
            fail("CI_ARTIFACT_SELECTION", "需要唯一匹配原 ZIP 摘要的 artifact 元数据条目")
        metadata = selected[0]
    workflow = metadata.get("workflow_run", {})
    if not isinstance(metadata.get("id"), int) or metadata["id"] <= 0 \
            or not isinstance(metadata.get("name"), str) or not metadata["name"] \
            or metadata.get("expired") is not False \
            or str(workflow.get("id")) != identifier or workflow.get("head_sha") != commit:
        fail("CI_ARTIFACT_PROVENANCE")
    archive_digest = metadata.get("digest")
    if not isinstance(archive_digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", archive_digest) \
            or "sha256:" + digest(archive) != archive_digest:
        fail("CI_ARTIFACT_DIGEST")
    receipt = load(unit_receipt)
    if receipt.get("mode") != "full" or receipt.get("skipped"):
        fail("HISTORICAL_NOT_FULL")
    log = Path(run_log).read_text(encoding="utf-8")
    # 必须使用 unit job 原始日志；引用一段含 flag 的 workflow 文本不能代替实际 env。
    for key, value in (("MODE", "full"), ("TESTS", ""), ("RUST_HOST", "true")):
        values = re.findall(r"(?:^|[ \t])" + key + r":[ \t]*([^\r\n]*)", log, re.M)
        if not values or any(item.strip() != value for item in values):
            fail("CI_UNIT_LOG_CONFIGURATION", key)
    if "-Pdsha.rust.required=true" not in log or "-Pdsha.sourceOnlyTests=false" not in log:
        fail("CI_UNIT_LOG_RUST_REQUIRED")
    files, tasks, identities = [], [], []
    for task in expected["tasks"]:
        paths = report_files(reports_root, task)
        if not paths:
            fail("JUNIT_MISSING", task["task"])
        task_cases, xml = [], []
        for path in paths:
            task_cases.extend(parse_xml(path))
            row = {"path": path.relative_to(reports_root).as_posix(), "sha256": digest(path),
                   "size": path.stat().st_size}
            xml.append(row)
            files.append(row)
        coverage(expected, task, task_cases)
        identities.extend(task_cases)
        tasks.append({"module": task["module"], "task": task["task"], "tests": len(task_cases),
                      "reports": xml})
    duplicates = [pair for pair, count in Counter(identities).items() if count != 1]
    if duplicates:
        fail("DUPLICATE_TEST_IDENTITY", duplicates)
    for pair in REQUIRED_FIXTURES:
        if pair not in identities:
            fail("REQUIRED_FIXTURE", pair)
    native = [pair for pair in identities if pair[0] == PREFIX + "util.RustDigestDifferentialTest"]
    if not native:
        fail("RUST_TESTS_MISSING")
    counts = receipt.get("flavors", {}).get("Standard", {})
    if counts.get("tests") != len(identities) or any(counts.get(k) != 0 for k in ("failures", "errors", "skipped")):
        fail("HISTORICAL_SUMMARY_DISAGREES")
    archive_members(reports_root, archive, files, unit_receipt)
    body = {"schema": SCHEMA, "kind": "dsha-unit-test-baseline", "status": "frozen",
            "acquisition": "historical-ci-import-no-freshness-attestation",
            "provenance": ci, "rawCiSha256": digest(ci_run), "artifact": metadata, "rawXml": files,
            "unitJobLogSha256": digest(run_log), "unitReceiptSha256": digest(unit_receipt),
            "planSha256": object_sha(expected), "identityFormat": ["classname", "name"],
            "identities": [list(pair) for pair in sorted(identities)], "sources": expected["sources"],
            "tasks": tasks, "tests": len(identities), "failures": 0, "errors": 0, "skipped": 0,
            "requiredFixtureTestsPassed": len(REQUIRED_FIXTURES),
            "rust": {"required": True, "evidence": "ci-unit-job-log-and-executed-native-tests",
                     "runId": identifier, "runAttempt": attempt, "nativeTests": len(native)}}
    preserve_baseline(output, body)
    write_json(output, body)
    return body


def bool_arg(text):
    if text not in ("true", "false"):
        raise argparse.ArgumentTypeError("必须为 true 或 false（实际 JVM 属性值）")
    return text == "true"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("plan", "begin", "task-begin", "task-end", "verify", "freeze", "import-ci-baseline"):
        command = commands.add_parser(name)
        command.add_argument("--root", type=Path, default=TOOLS.parent)
        if name not in ("plan", "import-ci-baseline"):
            command.add_argument("--state", type=Path, required=True)
        if name in ("plan", "verify", "freeze", "import-ci-baseline"):
            command.add_argument("--output", type=Path, required=True)
        if name in ("begin", "verify"):
            command.add_argument("--run-id", required=True)
            command.add_argument("--run-attempt", default="1")
        if name == "begin":
            command.add_argument("--plan", type=Path, required=True)
            command.add_argument("--rust-library", type=Path, required=True)
            command.add_argument("--rust-receipt", type=Path, required=True)
        if name.startswith("task-"):
            command.add_argument("--task", required=True)
        if name == "task-begin":
            command.add_argument("--source-only", type=bool_arg, required=True)
            command.add_argument("--filters-json", type=json.loads, required=True)
            command.add_argument("--rust-required", type=bool_arg, required=True)
            command.add_argument("--rust-library", type=Path, required=True)
            command.add_argument("--includes-json", type=json.loads, required=True)
            command.add_argument("--excludes-json", type=json.loads, required=True)
            command.add_argument("--scan-for-test-classes", type=bool_arg, required=True)
            command.add_argument("--test-classes-dirs-json", type=json.loads, required=True)
        if name == "task-end":
            command.add_argument("--outcome", required=True)
        if name == "verify":
            command.add_argument("--baseline", type=Path)
        if name in ("verify", "freeze"):
            command.add_argument("--archived", action="store_true")
        if name in ("freeze", "import-ci-baseline"):
            command.add_argument("--ci-run", type=Path, required=True)
        if name == "import-ci-baseline":
            command.add_argument("--plan", type=Path, required=True)
            command.add_argument("--reports-root", type=Path, required=True)
            command.add_argument("--unit-receipt", type=Path, required=True)
            command.add_argument("--run-log", type=Path, required=True)
            command.add_argument("--artifact", type=Path, required=True)
            command.add_argument("--archive", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "plan":
            result = plan(args.root)
            write_json(args.output, result)
        elif args.command == "begin":
            result = begin(args.root, args.plan, args.state, args.run_id, args.run_attempt,
                           rust_library=args.rust_library, rust_receipt=args.rust_receipt)
        elif args.command == "task-begin":
            result = task_begin(args.root, args.state, args.task, source_only=args.source_only,
                                filters=args.filters_json, rust_required=args.rust_required,
                                rust_library=args.rust_library, includes=args.includes_json,
                                excludes=args.excludes_json, scan_for_test_classes=args.scan_for_test_classes,
                                test_classes_dirs=args.test_classes_dirs_json)
        elif args.command == "task-end":
            result = task_end(args.root, args.state, args.task, outcome=args.outcome)
        elif args.command == "verify":
            result = verify_run(args.root, args.state, run_id=args.run_id, run_attempt=args.run_attempt,
                                baseline=args.baseline, archived=args.archived)
            write_json(args.output, result)
        elif args.command == "import-ci-baseline":
            result = import_ci_baseline(args.root, args.plan, args.ci_run, args.reports_root,
                                        args.unit_receipt, args.run_log, args.artifact, args.output,
                                        archive=args.archive)
        else:
            result = freeze(args.root, args.state, args.ci_run, args.output, archived=args.archived)
    except (ValueError, OSError, ImportError) as error:
        raise SystemExit(str(error)) from None
    print(json.dumps({"kind": result["kind"], **{key: result[key] for key in (
        "tests", "baselineStatus", "commit", "runId", "runAttempt") if key in result}}))


if __name__ == "__main__":
    main()
