"""检查源码行尾、显式依赖身份与工作流钉住；不扫私有数据或历史日志。"""
import hashlib
import json
from pathlib import Path
import re
from git_guard import git_output

ROOT = Path(__file__).resolve().parents[1]
names = git_output(["ls-files", "-z"], cwd=ROOT).decode("utf-8").split("\0")
errors = []
byte_pinned = {'app/src/main/assets/ubuntu-tools.manifest.json', 'app/src/main/assets/builtin-plugins/dsh-auto-review/package.json', 'app/src/main/assets/builtin-plugins/dsh-device-shell-guide/package.json', 'app/src/main/assets/lan-settings-upstream-contract.json', 'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/index.js', 'app/src/main/assets/builtin-plugins/dsh-computer-use-android/package.json', 'app/src/main/assets/ubuntu-tools.layout', 'app/src/main/assets/startup-mcp-timing-patch.json', 'app/src/main/assets/managed-runtime-inputs.json'}
byte_pinned.add("tools/mobile-server/index.js")
byte_pinned.add("app/src/main/assets/plugin-manager-policy-patch.json")
byte_pinned.add("app/src/main/assets/plugin-manager.py")
history_fixture_pins = {
    "tools/fixtures/session-interaction-v3/session-interaction-patch.json": "37f53270df43f737ec7c1d38d55cbf7e30e0cd8f21418ca8831b1582da590b37",
    "tools/fixtures/session-interaction-v3/web-integration/session-interaction.js": "df67935e0ab81e23b494119cb7b4c97e2263cd6d01a6cf9069ca6004e6e3a790",
    "tools/fixtures/rc2-upgrade-source/dsh-client-ui-workspace.js": "52765aacb6c7f96327da5d8709d8cbb30dd2957d4744a42da04c18e07fe264e5",
    "tools/fixtures/rc2-upgrade-source/dsh-client-ui-agent-preset.js": "6ecdf173de66fce620e588f10ed1e3b624725febab059fb867beefef9ae4ddb8",
    "tools/fixtures/rc2-upgrade-source/dsh-client-ui-settings-models.js": "190498e15358bcd3fe6810fe6368023a20b0403e3ea493bc979eca9bdb593b28",
    "tools/fixtures/rc2-upgrade-source/dsh-web-search-deepseek.js": "fc13dd4eaba3c9298af41a4d0559a1b8bb61614462fe18ba6e76d8c598c332bd",
    "tools/fixtures/plugin-management-rc5-historical/canonical.js": "1b8bf9eb266d7b3b7f899175e3f435c89ed8baa8aabc677212759f8c6cd23132",
}
# These frozen language sources retain their reviewed bytes. Runtime generators
# use the signed descriptor; native catalogues and notes have explicit CI pins.
language_generator_pins = {
    "tools/i18n/web-translations/ar.json",
    "tools/i18n/web-translations/ko.json",
    "tools/i18n/web-translations/la.json",
    "tools/i18n/web-translations/zh-Hant.json",
}
language_source_pins = {
    "docs/releases/build197-changes.ar.md": "a96ff6d4f58886938ada1197aa758da75a19ef378c2347830128ca71eda00030",
    "docs/releases/build197-changes.la.md": "24a72be4658c4874920e25f430bbbfdd912ce7d6deb74b428dd9cd32daabcaaa",
    "docs/releases/build197-changes.pt.md": "9c84cad387a0496aa7a0affa9c8a2ee6617ff5d747236e1ebcce84723cbf8500",
    "tools/i18n/translations-extra/ar.json": "6d2a9a59aefc7f8162e94d1eef511e5021ac22a4b2610945c5d56bc7ddaf9b49",
    "tools/i18n/translations-extra/fr.json": "e86ffac142977e34caf69050e25403233914f1c05884b8e9395f8168e774d036",
    "tools/i18n/translations-extra/ja.json": "e7457f2f408f23cbcdff1d7ddc11bcdec50901cca122fb922b2b363f9679d146",
    "tools/i18n/translations-extra/ko.json": "97ae1aa7f317a7800081513b9b940d1afb56863a555433224fb52621a4575b52",
    "tools/i18n/translations-extra/la.json": "472fd95707e53b9ebdfb2d36b4d7ea593b51959a05cefb3237b63fa352a8e257",
    "tools/i18n/translations-extra/pt.json": "cb5dd1af66e3a4bcb1efdd668ae7c0ad456c0efebdeac6bd1dd7bd413b94b4ee",
    "tools/i18n/translations-extra/ru.json": "dbab033b21feb18541247fe5062de1c7f948d54fd67e59675c473b1b90bbe21f",
    "tools/i18n/translations-extra/zh-Hant.json": "6b3b7eecec364b0c7c57be998dd312cf7a5057d44ff51ac0baefa8d5a915e4f2",
    "tools/i18n/translations/ar.json": "9ae717af2ae39a745490a81df37a8a5968ef9351f9c2ae9d3838a93147f4173e",
    "tools/i18n/translations/fr.json": "f8cfe5e6252035248079bd0803cc98e387ee98367ba270e10c97de2bec319931",
    "tools/i18n/translations/ja.json": "2eb1e78cbb3bbd822fd0d93bc1600c62913be9f1c0038a71ebc4a139a89fc7fa",
    "tools/i18n/translations/ko.json": "00b0153873119d7ea83d9b0264bb3264aa1a1680929e91994a9748681c7bb83d",
    "tools/i18n/translations/pt.json": "a1c32658903377c20adaa1d67f43898f72d0e746aa329c58d69889eb84daf0de",
    "tools/i18n/translations/ru.json": "98f06f71c9e6378dd6180f34c012692bae2cc57dcd74607dde54a62e09dcf2a2",
}
descriptor = json.loads((ROOT / "app/src/main/assets/runtime-descriptor.json").read_text(encoding="utf-8"))
binary = {".so", ".bin", ".jar", ".aar", ".gz", ".tgz", ".zip", ".png", ".jpg", ".webp", ".pdf", ".keystore"}
for name in names:
    path = ROOT / name
    # Rust vendor 是上游原始字节树（行尾由 .cargo-checksum.json 绑定），不参与 CRLF 规范化检查；
    # 许可证副本按 vendor 精确字节保存，同样豁免。
    if name.startswith('rust/vendor/') or name.startswith('app/src/main/assets/licenses/rust/'):
        continue
    if not path.is_file() or path.suffix in binary or name.startswith('tools/history/') or name in byte_pinned or name in history_fixture_pins or name in language_generator_pins or name in language_source_pins:
        continue
    value = path.read_bytes()
    if b"\0" not in value and b"\r\n" in value:
        errors.append("CRLF: " + name)
# 外部 action 必须钉到完整 commit；仓库内的 ./.github/actions 与 ./.github/workflows
# 节点随同一 commit 审阅，本身也在此扫描。
workflow_files = sorted((ROOT / ".github/workflows").glob("*.yml"))
workflow_files += sorted((ROOT / ".github/actions").glob("*/action.yml"))
for path in workflow_files:
    for selected in re.findall(r"uses:\s*([^\s#]+)", path.read_text(encoding="utf-8")):
        if (re.fullmatch(r"\./\.github/(?:actions/[\w-]+|workflows/_node-[\w-]+\.yml)", selected)
                or selected == "./.github/workflows/rebirth-validation.yml"):
            if not (ROOT / selected[2:]).exists():
                errors.append("MISSING_LOCAL_ACTION: " + selected)
            continue
        if not re.fullmatch(r"[\w.-]+/[\w.-]+@[0-9a-f]{40}", selected):
            errors.append("UNPINNED_ACTION: " + str(path.relative_to(ROOT)))
if "mavenLocal()" in (ROOT / "settings.gradle").read_text(encoding="utf-8"):
    errors.append("UNCONTROLLED_MAVEN_LOCAL")
for suffix in ("*.java", "*.kt"):
    for path in (ROOT / "app/src/main/java").rglob(suffix):
        if path.name not in ("Ids.java", "Ids.kt") and "[a-f0-9]{8}-" in path.read_text(encoding="utf-8"):
            errors.append("DUPLICATE_UUID_PATTERN: " + path.relative_to(ROOT).as_posix())
history_paths = ["tools/history/_byte-policy." + ext for ext in ("py", "java", "md", "txt", "sh", "xml", "json", "cjs")]
history_paths.append("tools/history/LICENSE")
history_paths.extend(sorted(byte_pinned))
history_paths.extend(sorted(history_fixture_pins))
history_paths.extend(sorted(language_generator_pins))
history_paths.extend(sorted(language_source_pins))
for name, expected in history_fixture_pins.items():
    path = ROOT / name
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        errors.append("HISTORICAL_FIXTURE_BYTES: " + name)
attributes = git_output(
    ["check-attr", "-z", "text", "eol", "--", *history_paths], cwd=ROOT
).rstrip(b"\0").split(b"\0")
for at in range(0, len(attributes), 3):
    if attributes[at + 2] != b"unset":
        errors.append("HISTORY_BYTE_NORMALIZATION: " + attributes[at].decode("utf-8"))
for name in sorted(byte_pinned):
    expected = (descriptor["launcherInputs"].get("generator:" + name) if name == "tools/mobile-server/index.js"
                else descriptor["inputs"].get(name.removeprefix("app/src/main/assets/")))
    if not re.fullmatch("[a-f0-9]{64}", expected or "") or hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != expected:
        errors.append("PINNED_BYTE_DIGEST: " + name)
language_pins = dict(language_source_pins)
for name in language_generator_pins:
    language_pins[name] = descriptor["launcherInputs"].get("generator:" + name)
for name, expected in sorted(language_pins.items()):
    path = ROOT / name
    if not re.fullmatch("[a-f0-9]{64}", expected or "") or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        errors.append("PINNED_LANGUAGE_BYTES: " + name)
toolchain = json.loads((ROOT / "ci/toolchain.lock.json").read_text(encoding="utf-8"))
wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
if "gradle-" + toolchain["gradle"] + "-" not in wrapper or "distributionSha256Sum=" not in wrapper:
    errors.append("GRADLE_IDENTITY")
if errors:
    raise SystemExit("\n".join(errors))
print("PASS tracked text LF or verified exact managed byte pins, pinned Actions, explicit Maven/Gradle identities, shared UUID validation and history byte policy")
