#!/usr/bin/env python3
"""共享构建约定的静态门禁；不启动 Gradle。

--emit-gradle-fixtures DIR 只在仓库外生成 Android library / Java / Kotlin JVM
真实编译测试夹具及 init 脚本，供主会话用 dsha-gradle 串行、离线验证。
--emit-test-options-negative-init FILE 只生成仓库外 JUnit 分类/框架过滤或资源源根负控；
--case exclude-category|include-category|junit-platform|external-resources，--task 指定真实 Test 路径。
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
LOGIC = ROOT / "build-logic"
WORKFLOW = ROOT / ".github/workflows/_node-unit-tests.yml"


def text(path):
    return path.read_text(encoding="utf-8")


def require(source, *patterns):
    for pattern in patterns:
        if re.search(pattern, source, re.MULTILINE) is None:
            raise ValueError("BUILD_CONVENTION_MISSING: " + pattern)


def test_contract(source):
    require(source,
            r"testTask\.maxHeapSize = '2048m'", r"testTask\.maxParallelForks = 1$",
            r"testImplementation 'junit:junit:4\.13\.2'",
            r"file\('src/main/assets'\)\.absolutePath",
            r"project\.ext\.has\('dshaTestAssetsDirectory'\)",
            r"configuredAssetsDirectory\.isAbsolute\(\)", r"configuredAssetsDirectory\.isDirectory\(\)",
            r"testAssetsDirectory = configuredAssetsDirectory\.absolutePath",
            r"testTask\.inputs\.property 'dsha\.sourceOnlyTests', sourceOnlyTests",
            r"testTask\.systemProperty 'dsha\.sourceOnlyTests', sourceOnlyTests",
            r"sourceOnlyTests in \['true', 'false'\]",
            r"rustRequiredProperty in \['true', 'false'\]",
            r"testTask\.systemProperty 'dsha\.rust\.hostLib', rustHostLibProperty\.toString\(\)",
            r"testTask\.systemProperty 'dsha\.rust\.required', rustRequiredProperty",
            r"else if \(rustRequiredProperty == 'true'\)",
            r"testTask\.systemProperty 'dsha\.rust\.required', 'true'",
            r"testTask\.jvmArgs '--add-opens=java\.base/java\.io=ALL-UNNAMED'",
            r"System\.getenv\('DSHA_ROBOLECTRIC_DEPENDENCY_DIR'\)",
            r"testTask\.systemProperty 'robolectric\.offline', 'true'",
            r"testTask\.systemProperty 'robolectric\.dependency\.dir', frameworkDirectory")


def android_contract(source):
    require(source, r"coreLibraryDesugaringEnabled true",
            r"sourceCompatibility JavaVersion\.VERSION_17", r"targetCompatibility JavaVersion\.VERSION_17",
            r"unitTests\.includeAndroidResources = true",
            r"apply from: rootProject\.file\('build-logic/kotlin-target\.gradle'\)",
            r"coreLibraryDesugaring 'com\.android\.tools:desugar_jdk_libs:2\.0\.4'",
            r"testImplementation 'org\.robolectric:robolectric:4\.15\.1'",
            r"apply from: rootProject\.file\('build-logic/test\.gradle'\)")


def android_library_contract(source):
    require(source, r"apply plugin: 'com\.android\.library'",
            r"compileSdk\s*\{\s*version = release\(37\)",
            r"defaultConfig\s*\{\s*minSdk 30\s*\}",
            r"apply from: rootProject\.file\('build-logic/android\.gradle'\)")


def kotlin_target_contract(source):
    require(source, r"extensions\.getByName\('kotlin'\)",
            r"kotlinExtension\.getClass\(\)\.getClassLoader\(\)\.loadClass\(",
            r"'org\.jetbrains\.kotlin\.gradle\.dsl\.JvmTarget'",
            r"java\.lang\.Enum\.valueOf\(jvmTargetType, 'JVM_17'\)",
            r"kotlinExtension\.compilerOptions\.jvmTarget\.set\(jvmTarget17\)")


def junit_options_contract(hooks, audit):
    require(hooks, r"def actualJUnitOptions = testTask\.options",
            r"if \(!\(actualJUnitOptions instanceof org\.gradle\.api\.tasks\.testing\.junit\.JUnitOptions\)\)\s*"
            r'throw new GradleException\("DSHA_TEST_FRAMEWORK:',
            r"\+ actualJUnitOptions\.includeCategories\.collect \{ 'junit4-include-category:' \+ it \}",
            r"\+ actualJUnitOptions\.excludeCategories\.collect \{ 'junit4-exclude-category:' \+ it \}",
            r"'--filters-json', groovy\.json\.JsonOutput\.toJson\(actualFilters\)")
    require(audit, r"def actualJUnitOptions = test\.options",
            r"if \(!\(actualJUnitOptions instanceof org\.gradle\.api\.tasks\.testing\.junit\.JUnitOptions\)\)\s*"
            r'throw new GradleException\("DSHA_TEST_FRAMEWORK:',
            r"if \(!actualJUnitOptions\.includeCategories\.isEmpty\(\) \|\| "
            r"!actualJUnitOptions\.excludeCategories\.isEmpty\(\)\)\s*"
            r'throw new GradleException\("DSHA_TEST_CATEGORIES:')


def resource_roots_contract(audit):
    require(audit,
            r"def auditResourceRoots = \{ resourceModule, sourceSetName, resourceKind, actualRoots ->",
            r'def standardRelativeRoot = "src/\$\{sourceSetName\}/\$\{resourceKind\}"',
            r"resourceModule\.projectDir\.absoluteFile\.toPath\(\)\.normalize\(\)\s*"
            r"\.resolve\(standardRelativeRoot\)\.normalize\(\)",
            r"resourceModule\.projectDir\.canonicalFile\.toPath\(\)\s*"
            r"\.resolve\(standardRelativeRoot\)\.normalize\(\)",
            r"if \(resourceRoot\.absoluteFile\.toPath\(\)\.normalize\(\) != declaredStandardRoot\s*"
            r"\|\| resourceRoot\.canonicalFile\.toPath\(\) != canonicalStandardRoot\)\s*"
            r'throw new GradleException\("DSHA_RESOURCE_SOURCE_ROOT:',
            r"def resourceSourceSets = androidModule \? module\.extensions\.getByName\('android'\)\.sourceSets\s*"
            r": module\.extensions\.getByName\('sourceSets'\)",
            r"resourceSourceSets\.each \{ sourceSet ->",
            r"auditResourceRoots\(module, sourceSet\.name, 'resources', sourceSet\.resources\.srcDirs\)",
            r"if \(androidModule\) auditResourceRoots\(module, sourceSet\.name, 'res', sourceSet\.res\.srcDirs\)")


def shell_blocks(source):
    blocks = []
    lines = source.splitlines()
    for index, line in enumerate(lines):
        match = re.fullmatch(r"(\s*)run: \|", line)
        if not match:
            continue
        indent = len(match.group(1)) + 2
        body = []
        for candidate in lines[index + 1:]:
            if candidate.strip() and len(candidate) - len(candidate.lstrip()) < indent:
                break
            body.append(candidate[indent:] if candidate.strip() else "")
        blocks.append("\n".join(body) + "\n")
    return blocks


def groovy_string(value):
    value = str(value)
    if any(character in value for character in "\r\n\0"):
        raise ValueError("FIXTURE_PATH_CONTROL")
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


def emit_gradle_fixtures(destination):
    """仅写新目录；夹具是临时验证工程，不进 settings/source-layout 或产品依赖。"""
    destination = Path(destination).absolute()
    resolved = destination.resolve()
    if resolved.is_relative_to(ROOT) or destination.exists() or destination.is_symlink():
        raise ValueError("FIXTURE_REQUIRES_NEW_DIRECTORY_OUTSIDE_REPOSITORY")
    destination.mkdir()
    files = {
        "java/build.gradle": "ext.dshaTestAssetsDirectory = file('src/test/fixtures')\n"
                             "apply from: rootProject.file('build-logic/jvm-library.gradle')\n",
        "java/src/test/fixtures/owner.txt": "java fixture owner\n",
        "java/src/main/java/fixture/JavaValue.java":
            "package fixture; public final class JavaValue { public static int answer() { return 42; } }\n",
        "java/src/test/java/fixture/JavaConventionTest.java":
            "package fixture; import org.junit.Test; import static org.junit.Assert.*; "
            "public class JavaConventionTest { @Test public void usesJava17AndJunit4() { "
            "assertEquals(42, JavaValue.answer()); assertEquals(\"17\", System.getProperty(\"java.specification.version\")); "
            "assertTrue(new java.io.File(System.getProperty(\"dsha.assetsDir\"), \"owner.txt\").isFile()); } }\n",
        "kotlin/build.gradle": "apply from: rootProject.file('build-logic/kotlin-jvm-library.gradle')\n",
        "kotlin/src/main/kotlin/fixture/KotlinValue.kt":
            "package fixture\nobject KotlinValue { fun answer(): Int = 42 }\n",
        "kotlin/src/test/kotlin/fixture/KotlinConventionTest.kt":
            "package fixture\nimport org.junit.Test\nimport org.junit.Assert.assertEquals\n"
            "class KotlinConventionTest { @Test fun usesPinnedKotlinAndJava17() { "
            "assertEquals(42, KotlinValue.answer()); assertEquals(\"2.2.10\", KotlinVersion.CURRENT.toString()); "
            "assertEquals(\"17\", System.getProperty(\"java.specification.version\")) } }\n",
        "android/build.gradle":
            "apply from: rootProject.file('build-logic/android-library.gradle')\n"
            "android { namespace 'fixture.android' }\n",
        "android/src/main/AndroidManifest.xml": '<manifest xmlns:android="http://schemas.android.com/apk/res/android" />\n',
        "android/src/main/res/values/strings.xml": '<resources><string name="fixture_value">fixture resource</string></resources>\n',
        "android/src/main/java/fixture/android/AndroidValue.kt":
            "package fixture.android\nobject AndroidValue { @JvmStatic fun answer(): Int = 42 }\n",
        "android/src/test/java/fixture/android/AndroidConventionTest.java":
            "package fixture.android; import org.junit.Test; import static org.junit.Assert.*; "
            "import org.junit.runner.RunWith; import org.robolectric.RobolectricTestRunner; "
            "import org.robolectric.RuntimeEnvironment; import org.robolectric.annotation.Config; "
            "@RunWith(RobolectricTestRunner.class) @Config(sdk = 34) public class AndroidConventionTest { "
            "@Test public void builtInKotlinAndPackagedResourcesWork() { assertEquals(42, AndroidValue.answer()); "
            "assertEquals(\"fixture resource\", RuntimeEnvironment.getApplication().getString(R.string.fixture_value)); } }\n",
    }
    includes = ["gradle.settingsEvaluated { settings ->",
                "    if (settings.rootDir.canonicalPath != " + groovy_string(ROOT.resolve()) + ")",
                "        throw new GradleException('FIXTURE_WRONG_ROOT')"]
    projects = ((":conventionJava", "java"), (":conventionKotlin", "kotlin"), (":conventionAndroid", "android"))
    for project, directory in projects:
        files[directory + '/build.gradle'] = 'ext.dshaConventionFixture = true\n' + files[directory + '/build.gradle']
        includes += ["    settings.include " + groovy_string(project),
                     "    settings.project(" + groovy_string(project) + ").projectDir = new File("
                     + groovy_string(destination / directory) + ")"]
    files["include.init.gradle"] = "\n".join(includes + ["}", ""])
    for name, body in files.items():
        path = destination / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
    return {"directory": str(destination), "initScript": str(destination / "include.init.gradle"),
            "tasks": [":conventionJava:test", ":conventionKotlin:test", ":conventionAndroid:testDebugUnitTest",
                      "verifyDshaBuildConventions"]}


def emit_test_options_negative_init(destination, case="exclude-category", task=":app:testStandardDebugUnitTest"):
    """只生成仓库外的新 init 文件；不执行 Gradle，不改结构约定或产品源码。"""
    if case not in ("exclude-category", "include-category", "junit-platform", "external-resources"):
        raise ValueError("NEGATIVE_INIT_CASE")
    if not re.fullmatch(r":[A-Za-z][\w-]*(?::[A-Za-z][\w-]*)+", task):
        raise ValueError("NEGATIVE_INIT_TASK")
    destination = Path(destination).absolute()
    if destination.resolve().is_relative_to(ROOT) or destination.exists() or destination.is_symlink():
        raise ValueError("NEGATIVE_INIT_REQUIRES_NEW_FILE_OUTSIDE_REPOSITORY")
    if case == 'external-resources':
        mutation = '\n'.join((
            '        def resourceProject = testTask.project',
            "        def android = resourceProject.extensions.findByName('android')",
            "        def sourceSets = android != null ? android.sourceSets : resourceProject.extensions.getByName('sourceSets')",
            "        sourceSets.getByName('test').resources.srcDir('/tmp/dsha-external-resources-fixture')",
        ))
        expected_failure = 'DSHA_RESOURCE_SOURCE_ROOT'
    elif case == "junit-platform":
        mutation = "        testTask.useJUnitPlatform()"
        expected_failure = "DSHA_TEST_FRAMEWORK"
    else:
        method = "excludeCategories" if case == "exclude-category" else "includeCategories"
        mutation = "\n".join(("        testTask.useJUnit { junitOptions ->",
                              "            junitOptions." + method + "('fixture.HiddenCategory')",
                              "        }"))
        expected_failure = "DSHA_TEST_CATEGORIES"
    source = "\n".join((
        "// 真实 Gradle 配置负控：根审计应先拒绝分类/框架变化，不执行 Test。",
        "gradle.projectsEvaluated {",
        "    if (gradle.rootProject.projectDir.canonicalPath != " + groovy_string(ROOT.resolve()) + ")",
        "        throw new GradleException('NEGATIVE_INIT_WRONG_ROOT')",
        "    def selected = gradle.rootProject.allprojects.collectMany { module ->",
        "        module.tasks.withType(org.gradle.api.tasks.testing.Test).findAll { testTask ->",
        "            testTask.path == " + groovy_string(task),
        "        }.toList()",
        "    }",
        "    if (selected.size() != 1) throw new GradleException('NEGATIVE_INIT_TASK_MISSING')",
        "    selected.each { testTask ->",
        mutation,
        "    }",
        "}",
        "",
    ))
    with destination.open("x", encoding="utf-8") as stream:
        stream.write(source)
    return {"initScript": str(destination), "case": case, "task": task, "expectedFailure": expected_failure,
            "command": ["dsha-gradle", "--offline", "--no-parallel", "--max-workers=1", "--init-script",
                        str(destination), "verifyDshaBuildConventions"]}


class BuildConventions(unittest.TestCase):
    def test_versions_and_nonproduct_boundary(self):
        settings, root = text(ROOT / "settings.gradle"), text(ROOT / "build.gradle")
        require(settings, r"gradle\.ext\.kotlinVersion = '2\.2\.10'")
        require(root, r"id 'com\.android\.application' version '9\.1\.1' apply false",
                r'classpath "org\.jetbrains\.kotlin:kotlin-gradle-plugin:\$\{gradle\.ext\.kotlinVersion\}"')
        self.assertNotRegex(settings, r"(?m)^\s*includeBuild\b")
        self.assertNotRegex(settings, r"(?m)^\s*include\b[^\n]*build-logic")
        self.assertFalse((LOGIC / "settings.gradle").exists())
        self.assertFalse((LOGIC / "build.gradle").exists())
        for path in [ROOT / "app/build.gradle", *LOGIC.glob("*.gradle")]:
            self.assertNotRegex(text(path), r"(?m)^\s*(?:id|apply plugin:)\s*['\"](?:org\.jetbrains\.kotlin\.android|kotlin-android)['\"]")
            self.assertNotRegex(text(path), r"(?m)^\s*(?:implementation|api)\s+['\"]org\.jetbrains\.kotlin:kotlin-stdlib")

    def test_existing_app_applies_real_shared_configuration(self):
        app = text(ROOT / "app/build.gradle")
        require(app, r"apply from: rootProject\.file\('build-logic/android\.gradle'\)")
        android_contract(text(LOGIC / "android.gradle"))
        test_contract(text(LOGIC / "test.gradle"))
        self.assertNotIn("testOptions {", app)
        self.assertNotIn("tasks.withType(Test)", app)
        self.assertNotIn("testImplementation 'junit:", app)

    def test_app_identity_resources_and_asset_roots_remain_local(self):
        app = text(ROOT / "app/build.gradle")
        require(app, r"(?m)^\s*versionCode [0-9]+$", r'(?m)^\s*versionName "[^"]+"$')
        for fragment in (
            "applicationId releaseIdentity.packageName", "flavorDimensions 'androidSupport'",
            "minSdk 30", "targetSdk 37", "version = release(37)", "ndkVersion '26.3.11579264'",
            "noCompress += ['gz', 'xz', 'bin', 'ja', 'tar', 'whl']",
            'main.assets.setSrcDirs([layout.buildDirectory.dir("generated/standardAssets").get().asFile,',
            'layout.buildDirectory.dir("generated/recoveryAssets").get().asFile])',
            'main.java.srcDir(layout.buildDirectory.dir("generated/uiLanguage").get().asFile)',
            'main.jniLibs.srcDir(layout.buildDirectory.dir("rust-jniLibs").get().asFile)',
            'debug.assets.srcDir(rootProject.file("tools/fixtures/web-state"))',
            'androidTest.assets.srcDir(rootProject.file("tools/fixtures/web-state"))',
            '"--source", file("src/main/assets").absolutePath',
            '"--output", layout.buildDirectory.dir("generated/standardAssets").get().asFile.absolutePath',
            '"--shared-assets", layout.buildDirectory.dir("generated/standardAssets").get().asFile.absolutePath',
        ):
            self.assertIn(fragment, app, fragment)
        android = text(LOGIC / "android.gradle")
        for local_setting in ("applicationId", "signingConfigs", "sourceSets", "packagingOptions", "noCompress"):
            self.assertNotIn(local_setting, android)

    def test_library_and_jvm_interfaces_apply_existing_plugins(self):
        library = text(LOGIC / "android-library.gradle")
        android_library_contract(library)
        java = text(LOGIC / "jvm-library.gradle")
        require(java, r"apply plugin: 'java-library'", r"languageVersion = JavaLanguageVersion\.of\(17\)",
                r"sourceCompatibility = JavaVersion\.VERSION_17", r"targetCompatibility = JavaVersion\.VERSION_17",
                r"apply from: rootProject\.file\('build-logic/test\.gradle'\)")
        kotlin = text(LOGIC / "kotlin-jvm-library.gradle")
        require(kotlin, r"gradle\.ext\.kotlinVersion != '2\.2\.10'", r"apply plugin: 'org\.jetbrains\.kotlin\.jvm'",
                r"apply from: rootProject\.file\('build-logic/jvm-library\.gradle'\)", r"jvmToolchain\(17\)",
                r"apply from: rootProject\.file\('build-logic/kotlin-target\.gradle'\)")
        kotlin_target_contract(text(LOGIC / "kotlin-target.gradle"))
        for path in LOGIC.glob("*.gradle"):
            self.assertNotRegex(text(path), r"(?m)^\s*(?:buildscript|repositories|plugins)\s*\{")

    def test_library_sdk_defaults_are_audited_and_not_supplied_by_fixture(self):
        library = text(LOGIC / "android-library.gradle")
        android_library_contract(library)
        audit = text(LOGIC / "verify.gradle")
        require(audit, r"android\.compileSdk != 37", r"android\.compileSdkPreview != null",
                r"android\.defaultConfig\.minSdk != 30", r"android\.defaultConfig\.minSdkPreview != null")
        for mutation in (library.replace("release(37)", "release(36)"), library.replace("minSdk 30", "minSdk 29")):
            with self.assertRaisesRegex(ValueError, "BUILD_CONVENTION_MISSING"):
                android_library_contract(mutation)
        with tempfile.TemporaryDirectory(prefix="dsha SDK defaults ") as folder:
            target = Path(folder) / "fixtures"
            emit_gradle_fixtures(target)
            fixture = text(target / "android/build.gradle")
            self.assertNotRegex(fixture, r"compileSdk|minSdk")
            self.assertIn("namespace 'fixture.android'", fixture)

    def test_negative_controls_reject_lost_resources_fork_memory_and_inputs(self):
        android, tests = text(LOGIC / "android.gradle"), text(LOGIC / "test.gradle")
        target = text(LOGIC / "kotlin-target.gradle")
        mutations = [(android_contract, android.replace("unitTests.includeAndroidResources = true", "")),
                     (kotlin_target_contract, target.replace("'JVM_17'", "'JVM_11'")),
                     (test_contract, tests.replace("maxParallelForks = 1", "maxParallelForks = 2")),
                     (test_contract, tests.replace("'2048m'", "'512m'")),
                     (test_contract, tests.replace("testTask.inputs.property 'dsha.sourceOnlyTests', sourceOnlyTests", "")),
                     (test_contract, tests.replace("testTask.systemProperty 'dsha.rust.required', 'true'", "")),
                     (test_contract, tests.replace("testTask.systemProperty 'robolectric.offline', 'true'", ""))]
        for check, mutation in mutations:
            with self.subTest(check=check.__name__, source=mutation[-120:]):
                with self.assertRaisesRegex(ValueError, "BUILD_CONVENTION_MISSING"):
                    check(mutation)

    def test_full_contract_uses_actual_task_and_jvm_settings(self):
        hooks = text(LOGIC / "unit-test-contract.gradle")
        require(hooks, r"System\.getenv\('DSHA_UNIT_TEST_STATE'\)", r"testTask\.doFirst", r"testTask\.doLast",
                r"'task-begin'", r"'task-end'", r"'--task', testTask\.path", r"'--outcome', 'SUCCESS'",
                r"testTask\.systemProperties\['dsha\.sourceOnlyTests'\]",
                r"testTask\.systemProperties\['dsha\.rust\.required'\]",
                r"testTask\.systemProperties\['dsha\.rust\.hostLib'\]",
                r"testTask\.filter\.includePatterns", r"testTask\.filter\.commandLineIncludePatterns",
                r"testTask\.filter\.excludePatterns", r"assertNormalExitValue\(\)",
                r"'--includes-json'", r"'--excludes-json'", r"'--scan-for-test-classes'",
                r"'--test-classes-dirs-json'", r"testTask\.testClassesDirs\.files",
                r"testTask\.dependsOn\(rootProject\.tasks\.named\('verifyDshaBuildConventions'\)\)")
        root = text(ROOT / "build.gradle")
        require(root, r"apply from: rootProject\.file\('build-logic/unit-test-contract\.gradle'\)",
                r"apply from: rootProject\.file\('build-logic/verify\.gradle'\)")
        audit = text(LOGIC / "verify.gradle")
        for actual_property in ("test.maxHeapSize", "test.maxParallelForks", "test.inputs.properties", "test.systemProperties",
                                "android.testOptions.unitTests.includeAndroidResources", "compilerOptions.jvmTarget"):
            self.assertIn(actual_property, audit)
        require(audit, r"products\.collect \{ it\.path \} as Set\) != declaredPaths",
                r"expectedTest instanceof Test", r"expectedTest\.reports\.junitXml\.outputLocation",
                r"fixture\.projectDir\.canonicalFile\.toPath\(\)\.startsWith\(rootDirectory\)",
                r"gradle\.taskGraph\.allTasks\.findAll \{ it instanceof Test \}",
                r"actualTasks != expectedTasks")

    def test_full_run_binds_actual_junit4_options_and_categories(self):
        hooks, audit = text(LOGIC / 'unit-test-contract.gradle'), text(LOGIC / 'verify.gradle')
        junit_options_contract(hooks, audit)
        self.assertLess(hooks.index('def actualJUnitOptions = testTask.options'), hooks.index("'task-begin'"))
        self.assertLess(audit.index('def actualJUnitOptions = test.options'), audit.index('test.maxHeapSize'))

    def test_junit_categories_and_framework_mutations_are_rejected(self):
        hooks, audit = text(LOGIC / 'unit-test-contract.gradle'), text(LOGIC / 'verify.gradle')
        mutations = (
            (hooks.replace("+ actualJUnitOptions.includeCategories.collect { 'junit4-include-category:' + it }", '', 1), audit),
            (hooks.replace("+ actualJUnitOptions.excludeCategories.collect { 'junit4-exclude-category:' + it }", '', 1), audit),
            (hooks.replace('org.gradle.api.tasks.testing.junit.JUnitOptions',
                           'org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions', 1), audit),
            (hooks.replace('JsonOutput.toJson(actualFilters)', 'JsonOutput.toJson([])', 1), audit),
            (hooks, audit.replace('org.gradle.api.tasks.testing.junit.JUnitOptions',
                                  'org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions', 1)),
            (hooks, audit.replace('!actualJUnitOptions.includeCategories.isEmpty() || ', '', 1)),
            (hooks, audit.replace('actualJUnitOptions.excludeCategories', 'actualJUnitOptions.includeCategories', 1)),
            (hooks, audit.replace(' || !actualJUnitOptions.excludeCategories.isEmpty()',
                                  ' && !actualJUnitOptions.excludeCategories.isEmpty()', 1)),
        )
        for number, (mutated_hooks, mutated_audit) in enumerate(mutations):
            with self.subTest(mutation=number):
                self.assertNotEqual((hooks, audit), (mutated_hooks, mutated_audit))
                with self.assertRaisesRegex(ValueError, 'BUILD_CONVENTION_MISSING'):
                    junit_options_contract(mutated_hooks, mutated_audit)

    def test_negative_init_emitter_targets_actual_options_and_never_overwrites(self):
        with tempfile.TemporaryDirectory(prefix='dsha options # 中文 ') as folder:
            for case, method, failure in (
                ('exclude-category', 'excludeCategories', 'DSHA_TEST_CATEGORIES'),
                ('include-category', 'includeCategories', 'DSHA_TEST_CATEGORIES'),
                ('junit-platform', 'useJUnitPlatform', 'DSHA_TEST_FRAMEWORK'),
            ):
                path = Path(folder) / (case + '.init.gradle')
                result = emit_test_options_negative_init(path, case, ':conventionJava:test')
                body = text(path)
                self.assertEqual(failure, result['expectedFailure'])
                self.assertIn('gradle.projectsEvaluated', body)
                self.assertIn("testTask.path == ':conventionJava:test'", body)
                self.assertIn('NEGATIVE_INIT_WRONG_ROOT', body)
                self.assertIn('NEGATIVE_INIT_TASK_MISSING', body)
                self.assertIn(method, body)
                self.assertEqual('verifyDshaBuildConventions', result['command'][-1])
                self.assertIn(str(path), result['command'])
                if case != 'junit-platform':
                    self.assertIn("junitOptions." + method + "('fixture.HiddenCategory')", body)
                with self.assertRaisesRegex(ValueError, 'NEW_FILE_OUTSIDE'):
                    emit_test_options_negative_init(path, case)
                self.assertEqual(body, text(path))
            for case, task, error in (
                ('not-a-case', ':app:testStandardDebugUnitTest', 'NEGATIVE_INIT_CASE'),
                ('exclude-category', ':app:test --tests hidden', 'NEGATIVE_INIT_TASK'),
            ):
                path = Path(folder) / 'invalid.init.gradle'
                with self.assertRaisesRegex(ValueError, error):
                    emit_test_options_negative_init(path, case, task)
                self.assertFalse(path.exists())
        with self.assertRaisesRegex(ValueError, 'NEW_FILE_OUTSIDE'):
            emit_test_options_negative_init(ROOT / 'build-logic/unwanted-negative.init.gradle')

    def test_negative_init_cli_only_generates_file_and_returns_expected_failure(self):
        with tempfile.TemporaryDirectory(prefix='dsha options CLI ') as folder:
            path = Path(folder) / 'exclude.init.gradle'
            command = [sys.executable, str(Path(__file__).resolve()), '--emit-test-options-negative-init', str(path)]
            completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
            self.assertEqual(0, completed.returncode, completed.stderr)
            result = json.loads(completed.stdout)
            self.assertEqual('exclude-category', result['case'])
            self.assertEqual(':app:testStandardDebugUnitTest', result['task'])
            self.assertEqual('DSHA_TEST_CATEGORIES', result['expectedFailure'])
            self.assertTrue(path.is_file())
            self.assertNotIn('BUILD SUCCESSFUL', completed.stdout)

    def test_actual_android_and_jvm_resource_roots_are_standard(self):
        audit = text(LOGIC / 'verify.gradle')
        resource_roots_contract(audit)
        helper = audit.split('def auditResourceRoots =', 1)[1].split('def checked =', 1)[0]
        for unrelated in ('assets', 'jniLibs', 'java.srcDirs', 'generated/standardAssets', 'generated/uiLanguage'):
            self.assertNotIn(unrelated, helper)
        self.assertNotIn('setSrcDirs', helper)

    def test_resource_source_root_mutations_are_rejected(self):
        audit = text(LOGIC / 'verify.gradle')
        mutations = (
            audit.replace("auditResourceRoots(module, sourceSet.name, 'resources', sourceSet.resources.srcDirs)", '', 1),
            audit.replace("auditResourceRoots(module, sourceSet.name, 'res', sourceSet.res.srcDirs)", '', 1),
            audit.replace("module.extensions.getByName('android').sourceSets", '[]', 1),
            audit.replace("module.extensions.getByName('sourceSets')", '[]', 1),
            audit.replace('|| resourceRoot.canonicalFile.toPath() != canonicalStandardRoot',
                          '&& resourceRoot.canonicalFile.toPath() != canonicalStandardRoot', 1),
            audit.replace('resourceRoot.absoluteFile.toPath().normalize() != declaredStandardRoot',
                          'resourceRoot.absoluteFile.toPath().normalize() == declaredStandardRoot', 1),
            audit.replace('src/${sourceSetName}/${resourceKind}', 'src/main/${resourceKind}', 1),
        )
        for number, mutation in enumerate(mutations):
            with self.subTest(mutation=number):
                self.assertNotEqual(audit, mutation)
                with self.assertRaisesRegex(ValueError, 'BUILD_CONVENTION_MISSING'):
                    resource_roots_contract(mutation)

    def test_external_resources_init_cli_targets_real_android_or_jvm_source_set(self):
        with tempfile.TemporaryDirectory(prefix='dsha external resources ') as folder:
            path = Path(folder) / 'external.init.gradle'
            completed = subprocess.run([
                sys.executable, str(Path(__file__).resolve()), '--emit-test-options-negative-init', str(path),
                '--case', 'external-resources', '--task', ':conventionJava:test',
            ], cwd=ROOT, capture_output=True, text=True)
            self.assertEqual(0, completed.returncode, completed.stderr)
            result = json.loads(completed.stdout)
            self.assertEqual('external-resources', result['case'])
            self.assertEqual('DSHA_RESOURCE_SOURCE_ROOT', result['expectedFailure'])
            self.assertEqual('verifyDshaBuildConventions', result['command'][-1])
            body = text(path)
            self.assertIn('def resourceProject = testTask.project', body)
            self.assertIn("resourceProject.extensions.findByName('android')", body)
            self.assertIn("android != null ? android.sourceSets : resourceProject.extensions.getByName('sourceSets')", body)
            self.assertIn("sourceSets.getByName('test').resources.srcDir('/tmp/dsha-external-resources-fixture')", body)
            self.assertNotIn('.assets', body)
            self.assertNotIn('.java', body)
            with self.assertRaisesRegex(ValueError, 'NEW_FILE_OUTSIDE'):
                emit_test_options_negative_init(path, 'external-resources')
            self.assertEqual(body, text(path))

    def test_ci_uses_layout_tasks_all_reports_and_frozen_full_contract(self):
        workflow = text(WORKFLOW)
        for fragment in ("tools/source_layout.py --test-tasks", "tools/unit_test_contract.py plan",
                         "mapfile -t unit_tasks < out/unit-tasks.txt", 'for task in "${unit_tasks[@]}"',
                         'args+=("$task")', 'args+=(--tests "$pattern")', "args+=(--rerun-tasks)",
                         "tools/unit_test_contract.py begin", "--rust-receipt out/rust-host/receipt.json",
                         "tools/unit_test_contract.py verify", "--baseline ci/unit-test-baseline.json",
                         "inputs.mode == 'full' && inputs.tests == ''", "inputs.mode != 'full' || inputs.tests != ''",
                         "completeFixedInputRun=False", "**/build/test-results/**", "out/unit-run"):
            self.assertIn(fragment, workflow)
        self.assertNotIn("--bootstrap", workflow)
        self.assertNotIn("args=(:app:", workflow)
        self.assertNotIn("app/build/test-results/testStandardDebugUnitTest", workflow)
        self.assertLess(workflow.index('args+=("$task")'), workflow.index('args+=(--tests "$pattern")'))

    def test_workflow_shell_and_embedded_python_parse_without_execution(self):
        blocks = shell_blocks(text(WORKFLOW))
        self.assertGreaterEqual(len(blocks), 7)
        python_blocks = 0
        for block in blocks:
            result = subprocess.run(["bash", "-n"], input=block, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            for body in re.findall(r"python3 - <<'PY'\n([\s\S]*?)\nPY\n", block):
                compile(body, str(WORKFLOW), "exec")
                python_blocks += 1
        self.assertEqual(2, python_blocks)

    def test_emitted_fixtures_are_real_outside_products_and_never_overwritten(self):
        with tempfile.TemporaryDirectory(prefix="dsha conventions # 中文 ") as folder:
            target = Path(folder) / "fixtures"
            result = emit_gradle_fixtures(target)
            self.assertEqual(4, len(result["tasks"]))
            init = text(target / "include.init.gradle")
            self.assertIn(groovy_string(target / "android"), init)
            self.assertIn("settings.rootDir.canonicalPath", init)
            self.assertIn("kotlin-jvm-library.gradle", text(target / "kotlin/build.gradle"))
            self.assertIn("KotlinVersion.CURRENT", text(target / "kotlin/src/test/kotlin/fixture/KotlinConventionTest.kt"))
            self.assertIn("R.string.fixture_value", text(target / "android/src/test/java/fixture/android/AndroidConventionTest.java"))
            self.assertTrue((target / "java/src/test/java/fixture/JavaConventionTest.java").is_file())
            with self.assertRaisesRegex(ValueError, "NEW_DIRECTORY_OUTSIDE"):
                emit_gradle_fixtures(target)
        with self.assertRaisesRegex(ValueError, "NEW_DIRECTORY_OUTSIDE"):
            emit_gradle_fixtures(ROOT / "build-logic/unwanted-product")


if __name__ == "__main__":
    if sys.flags.optimize:
        raise SystemExit("BUILD_CONVENTIONS_OPTIMIZATION_FORBIDDEN")
    if any(argument.split('=', 1)[0] in ('--emit-gradle-fixtures', '--emit-test-options-negative-init')
           for argument in sys.argv[1:]):
        parser = argparse.ArgumentParser(description=__doc__)
        modes = parser.add_mutually_exclusive_group(required=True)
        modes.add_argument('--emit-gradle-fixtures', type=Path)
        modes.add_argument('--emit-test-options-negative-init', type=Path)
        parser.add_argument('--case', choices=('exclude-category', 'include-category', 'junit-platform', 'external-resources'),
                            default='exclude-category', help='真实 Test.options / 资源源根负控，默认 exclude-category')
        parser.add_argument('--task', default=':app:testStandardDebugUnitTest', help='负控目标 Test 的完整任务路径')
        args = parser.parse_args()
        if args.emit_test_options_negative_init:
            result = emit_test_options_negative_init(args.emit_test_options_negative_init, args.case, args.task)
        else:
            if any(argument.split('=', 1)[0] in ('--case', '--task') for argument in sys.argv[1:]):
                parser.error('--case / --task 只用于 --emit-test-options-negative-init')
            result = emit_gradle_fixtures(args.emit_gradle_fixtures)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        unittest.main(verbosity=2)
