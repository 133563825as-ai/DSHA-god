# A8 P1：工程门禁准备实现与验证（2026-11-15）

本批已实现共享源码映射、全仓测试身份/新鲜报告门禁和可直接应用的构建约定，**不提取生产类，不创建产品模块，不解锁 P2–P7**。P1 完整出口尚未通过：开工主线的真实完整 CI 成功，但原始 JUnit artifact 下载返回 HTTP 403，历史执行身份尚不能冻结；最终受管输入登记、归档重建与 descriptor 仍由 main 串行完成。待完成这些步骤后再做候选完整 CI，不能把本页本机夹具测试写成整仓通过。

依据：[A8 基线 §9](a8-task-baseline-20261114.md#9-convention-plugin-与测试数硬门禁)、[§11](a8-task-baseline-20261114.md#11-实现阶段与逐提交回退点待批准)、[ADR-0004](../../adr/0004-kotlin-rust-direction.md)及[工具链](../../kotlin-toolchain.md)。用户本次直接授权的是独立 P1 工程准备；基线的 P2–P7 前置与业务收口不因此获准。

## 基线与职责范围

开工重新 fetch 后，真实 `origin/main` 已从最初提示的 `5ccc8aa3` 进入 `f9e8d867b695f8f862288fae00f2c0a89455a87f`。新工作树和分支为 `a8-p1-gates`，直接基于这个当时最新 main；未吸收 contract/M17 等侧分支，也未修改主检出或他人声明。最后发布仍是 build366 / `5ccc8aa3`，进入 main 不等于已发布。

实际改动限于 [共享构建脚本](../../../build-logic/README.md)、[应用构建](../../../app/build.gradle)、[布局清单](../../../ci/source-layout.json)、[映射解析](../../../tools/source_layout.py)、输入/边界/格式/测试工具和[单测工作流](../../../.github/workflows/_node-unit-tests.yml)。生产 Java/Kotlin 文件与测试源码的新增、删除、搬迁和修改均为 **0**；没有产品 API 调用点变化，没有偏好键、FQCN、JNI、协议、资源布局、Fragment/XML 或数据格式变化。

开工时当前源码计划来自真实跟踪清单：768 个 test 源文件，其中 1 个 Kotlin 文件、13 个没有直接 `@Test` 声明的辅助文件；解析到 5,075 个方法声明，全部生产/额外源集 Java/Kotlin 输入共 1,868 个。这些数字仅为该提交的源码声明口径，不是 XML testcase 数或实际执行数，也不成为以后允许减测的常数。

主会话核实的完整软件基线为 [full run 43661288065](https://github.com/SunsetRNE/DSHA-Sunset-mod/actions/runs/43661288065)：head 精确为上述 main，full、空筛选、forks=1、host Rust required、七个外部夹具要求均生效，运行/验证步骤 success。[Source checks 43661155033](https://github.com/SunsetRNE/DSHA-Sunset-mod/actions/runs/43661155033) 也成功。这是开工基线 CI，不是本批未推送代码的 CI。

真实 artifact 为 `unit-results` / id `14140142885`，API 摘要为 `sha256:c3be158f43b65d72a67d166777ac76c101bdf6a6dcfc5455233644ceadfb6eb0`。本机未下载到原 ZIP/XML，不能声称已核对 ZIP 摘要、精确执行总数或参数化/SDK 展开的全集。没有为获取材料重新 dispatch、换身份或放宽权限。

## 源码身份与物理布局接口

[SourceLayout](../../../tools/source_layout.py) 的逻辑源身份是 `sourceSet:包内路径.扩展名`，不含 Gradle 模块、物理目录或 java/kotlin 语言源根。清单目前仅有真实 `:app`，同时登记 main、standard、debug、androidTest、test、deviceAudit；格式仍精确覆盖原五个源集，不扩大上游/生成物格式所有权。新增源集有实际源文件却未登记时失败。

可用接口：

```python
layout = SourceLayout(root)
layout.source_files(source_sets=None, suffixes=(".java", ".kt", ".kts"))
layout.resolve("com/deepseekharness/app/util/Policy.java", source_set="main")
layout.resolve_class("util/Policy", source_set="main")
layout.package_files("runtime")
layout.format_files((".kt", ".kts"))
layout.test_tasks()
```

同一实例是一份布局观察，文件变更后新建实例，不跨运行缓存。映射拒绝重名、同职责 Java/Kotlin 并存、跨模块重复、路径越界与软链接。边界中的旧 backup→core 基线仍用旧逻辑路径核对，不因物理搬迁丢掉约束；没有扩张白名单。

[运行时输入解析](../../../tools/runtime_input_contract.py) 保留旧 launcher 逻辑键、扩展名、原生库树和生成器集合；Gradle 配置期直接消费下面的实际映射，不再维护另一套 app-only 源根推断：

```sh
python3 tools/source_layout.py --launcher-inputs
python3 tools/source_layout.py --test-tasks
```

当前单 app 的 launcher 键、文件路径及输入字节集合与旧算法一致，512 项没有删减。临时夹具中的跨模块、java/kotlin 源根搬迁、缺树、重复实现和 Kotlin 边界违规均有反例；它们不搬生产文件。[旧 javac runner](../../../tools/run-unit-tests.py) 同样读取布局；含 Kotlin 或多模块时明确要求 Gradle，不再静默漏编 Kotlin 或把 Java-only 汇总当成迁移出口。

## 新鲜报告与执行身份接口

[聚合器](../../../tools/unit_test_contract.py) 保存 XML 的原始 `(classname, name)`。参数化、SDK 后缀、Kotlin 反引号方法名均不裁剪，只有声明→执行覆盖检查允许按明确后缀匹配。全部旧身份必须仍存在，且在本次所有模块报告中恰好一次；源码、helper、编译输出和旧身份集合都单独核验，不能靠总数增加掩盖遗失。

完整路径为：

```sh
python3 tools/unit_test_contract.py plan --root "$PWD" --output out/unit-plan.json
python3 tools/unit_test_contract.py begin --root "$PWD" --plan out/unit-plan.json \
  --state out/unit-run --run-id "$GITHUB_RUN_ID" --run-attempt "$GITHUB_RUN_ATTEMPT" \
  --rust-library out/rust-host/libdsha_rs.so --rust-receipt out/rust-host/receipt.json
# 真实 Test 的 doFirst/task-begin 与成功 doLast/task-end 由共享 hook 执行。
python3 tools/unit_test_contract.py verify --root "$PWD" --state out/unit-run \
  --run-id "$GITHUB_RUN_ID" --run-attempt "$GITHUB_RUN_ATTEMPT" \
  --baseline ci/unit-test-baseline.json --output out/unit-tests.json
```

begin 校验计划、HEAD/源码输入、Rust 构建 receipt 和库摘要后，只清理已验证任务报告目录中的旧 XML；不删除源文件、binary 或 HTML。完整运行要求受管 Git 输入与 commit 一致，并检测运行中源码、资源和 Gradle 配置漂移。每个 Test 传入实际 source-only/Rust 属性、Gradle filters、文件 includes/excludes、扫描设置和编译输出目录。任务未执行、UP-TO-DATE、FROM-CACHE、NO-SOURCE、异常零测试或缺任一证据都会失败。

新鲜性绑定 commit/run/attempt/task、开始/结束时钟、XML 精确集合、大小、mtime/ctime 和 SHA-256；归档后仅使用已记录时间与当前字节核验，不能把解压时间冒充运行时间。full 始终要求零 failure/error/skipped、七个固定外部夹具各一次、真实 Rust required 与对应库。证据是受信构建流程的可审查记录，不是对恶意修改构建进程的密码学隔离。

完整路径还单独记录当前 593 项标准源集资源输入（该数是本基线实测，不是固定常数）：源集根文件与非 Java/Kotlin 子树全部进入清单，包含 res/resources/fixtures/aidl/native 资源；未跟踪、ignored 或未登记源集中的资源不能冒认该 commit。唯一豁免是既有 `:app/app/src/main/assets` 固定输入 owner，继续由原有锁和 prepare 契约负责。资源/Git blob 摘要均流式读取。

本轮独审关闭了 CI 读取器旧路径、未跟踪资源和 Kotlin 组注解/分类筛选三项问题。无法可靠解析的 Kotlin 注解组与 use-site 注解会明确拒绝；Java17 record 与嵌套 record helper 也要求真实编译输出。完整 Test 的实际 JUnit4 分类过滤与错误测试框架均被拒绝。

接口边界：工具只自动枚举声明的标准源集；仓库根 out、local.properties、module/build 不属于这份源资源清单。树外自定义 sourceDirs/manifest、java/kotlin 根内非源码文件不会自动取得输入证明；集成方须单独拒绝或登记明确 owner。实际构建审计已经拒绝非标准 res/resources 根，不能将此扩大描述为已覆盖任意自定义 Gradle 输入。

[历史基线文件](../../../ci/unit-test-baseline.json) 明确保持 pending，不填猜测的执行数字，不提供自动 bootstrap。历史导入必须读取成功 CI 元数据、同 run 的原 artifact 元数据与 ZIP、原 XML/summary 和真实 unit job log：

```sh
python3 tools/unit_test_contract.py import-ci-baseline --root "$PWD" \
  --plan out/unit-plan.json --ci-run out/ci-run.json \
  --reports-root /absolute/path/to/extracted-artifact \
  --unit-receipt /absolute/path/to/extracted-artifact/out/unit-tests.json \
  --run-log /absolute/path/to/unit-job.log --artifact /absolute/path/to/artifact-api.json \
  --archive /absolute/path/to/original-artifact.zip --output ci/unit-test-baseline.json
```

它校验 ZIP 摘要/原始成员字节、精确 CI commit 的源码集合与内容、full/Rust/空筛选日志、七夹具和原始用例；仓库工具夹具中的测试不混入产品源清单。历史导入只产 baseline，不生成候选 run/task 新鲜性凭据。已有冻结清单也不能靠 refreeze 吞掉旧身份。

[旧校验器](../../../tools/verify-unit-results.py) 的 `verify(reports, mode, ...)` API 保持；定向/source-only CLI 只写明确的 summary，不能当全仓完整回执。[共享工作流](../../../.github/workflows/_node-unit-tests.yml) 枚举布局中的全部真实任务和报告，full 强制实际重跑并核真实 Gradle 模块/任务图，上传所有模块 XML、计划和任务证据；定向/source-only 单独汇总。

## 构建约定与实际验证

[构建约定说明](../../../build-logic/README.md) 给出 Android library、Java library、Kotlin JVM 的实际入口和仓库外真实夹具。直接复用已 pin 的 AGP 9.1.1/KGP 2.2.10，Android 仍用 built-in Kotlin，JVM 17，不新增产品模块或第二套插件解析。应用发行身份、资源和生成资产任务保持在 app；library 约定实际统一 SDK37/minSdk30。测试资产支持显式 owner，纯 JVM 不隐式依赖应用。

当前机器是 aarch64/JDK17，共用缓存，所有 Gradle 经 `dsha-gradle` 串行；没有安装或手机操作。

| 验证 | 实际结果 |
|---|---|
| 开工 core/architecture/运行输入/本机 runner | core 25、architecture、输入 18、本机 runner 5 通过；首次输入 18 因归档缺失失败，按当前锁核验复制本机固定输入后重跑通过 |
| 新布局与当前单 app 等价 | 13 项；Git 清单、旧 launcher/格式算法对等，真实临时迁移/重名/越界/未登记源集及迁移后 Kotlin 边界反例 |
| 既有输入/格式清单/core/architecture | 输入 18、格式清单 4、core 25、architecture 保留全部旧规则 |
| 工作流与共享构建静态门禁 | 工作流 58、构建约定 17、CI 工具链读取器 6 通过；旧 app 任务硬编码断言改为布局枚举与全仓报告核验，没有删旧规则 |
| 全仓报告聚合器 | 独立复跑 47 项通过，覆盖真实文件/XML/Git/时钟/原 ZIP 负控；不把合成 JNI/class 夹具当真实生产编译或历史 CI |
| Gradle 实际配置负控 | app 正控与 app+三个临时模块正控均通过；分类包含/排除、错误框架、外部资源四项 app 负控及 JVM 外部资源负控均按指定错误拒绝，无 Test 执行 |
| Gradle 实际输入消费 | `:app:verifyRuntimeDescriptorInputs` 严格离线实际执行成功，103 安装项 / 205 资产 / 512 启动输入未缩 |
| 旧汇总 API、本机 runner | 旧 handoff 10 项保持；runner 6 项含新增漏 Kotlin 拒绝 |
| Java/Kotlin 格式 | Java 1,827 文件、59 批，0 changed；Kotlin 2 文件、0 changed，锁与保护/生成物规则不变 |
| 真实离线配置/双语言 JVM 测试 | 最终 `--offline --rerun-tasks` 成功，16 个任务实际执行；Java/Kotlin 各 1 个 JUnit 用例重新执行，0 failure/error/skipped；生产与测试 class major61 |
| Android library 生产 Kotlin/资源 R/依赖 | SDK37/min30 来自约定；生成 R、生产 Kotlin 编译和 AAR 测试依赖检查实际成功；并核生产 class major61 |
| Android library Robolectric 测试 | **未执行**：补齐依赖后到 `processDebugUnitTestResources`，x86_64 aapt2 在 aarch64 报 `bad machine`；不能记作测试通过 |
| 完整 app 单测、Lint、APK、设备/视觉 | 本批候选未跑；开工 main full CI 成功不替代本批候选完整 CI |

过程中的真实失败保留：applied script 最初不能解析 KGP 的 `JvmTarget`，修为从已应用扩展的 classloader 获取同版本枚举后真实重跑成功；首次离线 Android 图缺 Error Prone 2.36.0。只增补[依赖校验元数据](../../../gradle/verification-metadata.xml)中该注解 JAR、子 POM、父 POM 三份官方 HTTPS/SHA-1 交叉核对的精确 SHA-256，旧条目没有删除或重排，没有 force 传递版本。严格在线解析后，离线生产编译/依赖检查重跑成功。格式工具首轮下载曾被摘要检查拒绝，随后在不改变锁的情况下使用重新核验通过的缓存，离线门禁通过。

## 受管输入与 main 的精确集成接口

[输入差异登记](../../../ci/a8-p1-input-delta.json) 保存真实差异，不是生成 descriptor 的替代物。当前旧 manifest 下 512 项 launcher 保持；[固定归档配方](../../../tools/build-dsh-runtime.py)从 91 项变为 93 项：新增共享解析器和布局清单，修改配方脚本与原输入解析器两项摘要。不能把旧归档的旧来源记录手改成通过。

main 在串行集成树必须：

1. 将差异登记中的两个布局生成输入加入受管 generatorSources，核对无成员删除；不扩白名单或缩 tree。
2. 按真实新配方重建/核验固定 DSH 归档和来源证明，按固定输入流程处理所需归档资产。不得把旧输入证明当成新配方的回执。
3. 最后统一重算并核验 descriptor；不提升 baseVersion、dataWrite 或数据格式，不预填一个尚未生成的 runtimeId。
4. 取得开工 CI 的原始 ZIP/XML 并导入冻结基线，再对集成候选运行完整新鲜聚合、Standard Lint/APK 等出口。任何一步不满足，P1/首个模块提取继续锁定。

未来首个真实模块必须在同一原子提交中登记 settings、布局、约定、实际测试任务、测试/辅助类、资产 owner、格式 adoption、边界与 launcher 逻辑身份；保留原 FQCN 和展开测试身份。配置审计把真实 Gradle 产品集合与布局精确比较，不能漏登记模块，也不能用仓库内 fixture 标记逃过核验。

## 独立审查与边界

独立只读审查完整覆盖本批 diff 和新增文件，首次 REQUEST_CHANGES 的三项（CI 工具链旧锚点、未跟踪资源、隐藏 Kotlin 注解/JUnit 分类过滤）全部整改后增量结论为 **APPROVE，未发现剩余 P0/P1/P2**。审查者还只读核对了真实 Gradle 正负控日志、当前 plan、配方差异摘要及原先的输入/历史导入修复；没有把代码审查批准写成合入授权或 P1 软件出口通过。

最终父会话独立复跑报告契约 47 项、旧 handoff 10 项，均通过；没有把作者同一套重跑叠加成独特覆盖数。已审核心文件摘要和完整本机日志路径随会话交付保存。自定义 manifest 等外部 Gradle 输入与外部 fixture owner 的限制仍按上文保留，不能将标准 res/resources 源根拒绝扩大为任意自定义输入都已覆盖。

## 提交与回退

结构等价抽取单独为 `87a03970`；布局/报告/新门禁另行提交，最终 base/HEAD 和实际测试回执以本工作树的会话交付及 Git 日志为准。只创建本地提交，没有合并、push、tag、发布、安装或手机操作。

代码回退按依赖逆序 revert 新门禁/布局提交，再 revert 构建约定抽取，恢复旧 app 配置、扫描与汇总入口；不删除测试，不回滚用户数据。若 main 已登记并生成新受管输入，应在同一串行回退树恢复配套清单/配方并重新生成 descriptor；安装用户回退由 main 在同线更高 versionCode 安排，不能降级安装或清数据。
