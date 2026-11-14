# 独立Linux验证计划

> 待执行计划，不是已通过回执，不自动授予远端、联网安装、CI、APK或设备操作许可。

先读[阶段进展](README.md)与[交接身份](handoff-identities.md)。本资料分支只新增文档；下列命令针对接收方已应用精确冻结源码增量的新任务树，不应直接在本资料分支运行来验证尚未同步的代码。

## 1. 主机准入

- 真正独立Linux主机或VM，明确x64/arm64及内核、UID、capabilities、UTF-8、RAM/swap/cgroup、磁盘/文件系统和临时目录。资源未知不视为足够；不要与其他构建并行。
- 专属synthetic临时树先验只读0444/0555对象打开前后ctime、Git对象copytree字节/模式、真实双JVM FileChannel锁、浏览器起停；不能用真实用户profile探测。
- JDK17、Node24、Python3.12、Gradle9.3.1、AGP9.1.1、API37、build-tools36.0.0、NDK26.3.11579264按仓库工具链和wrapper摘要锁核验。存在路径/版本输出不是完整字节认证。
- 本容器新增dsha-gradle只是diagnostic-only，所有项目tasks硬拒绝，不能照搬为独立Ubuntu构建入口。接收宿主须由其维护者准入合法执行入口；不得借新名字绕过现有平台守卫。

## 2. 应用源码与输入

1. 接收者已有可信精确基线，另建干净任务树；检查HEAD/状态，应用前先git apply检查，新增文件只按manifest安全普通成员排他落地。应用后核完整路径集合、SHA与执行位。
2. 包不含固定二进制或依赖。按当前descriptor、输入锁和本次归档回执恢复；不能盲用参考最新bundle或仅git archive基线冒充冻结修改。
3. 冻结arm64归档与其.inputs.json单独校验；builder拒绝非arm64 ELF，x64 host raw不得重建Android归档。必要重建仅由获准执行方使用独立arm64输入。
4. host raw/managed按本机架构创建。prepare-test-runtime的`--platform host`可能执行锁定npm ci，即使`--offline`也不是纯读取，须执行者先获准；禁用`--allow-stale-archive`求绿，不手工伪造marker。
5. Playwright、pnpm、SemVer/React参考、framework、Robolectric instrumented SDK、backup jars等按各自锁准备。非instrumented android-all不能改名冒充instrumented SDK。安装/lifecycle只能在批准的synthetic环境执行，不使用真实凭据。

## 3. 分阶段验收

以现有候选工作流为顺序依据，但不直接dispatch其后续APK/上传步骤。全部串行，独立输出目录，保留真实退出码和失败。

| 阶段 | 要求 |
|---|---|
| L1源码 | 身份、descriptor `--check`、manifest覆盖、source hygiene、i18n、changelog、文档链接、格式门禁 |
| L1 fast | 完整fast102，不用`--only`或source-only替代 |
| L2 | 完整Standard unit，fresh XML经full/require-fixtures核验，零fail/error/skip；Lint及真实AGP source-map |
| L3 runtime | 完整runtime130，真实host架构fixture/native、浏览器和所有requires齐备 |
| L3 software | verify-stability的software阶段，按既有契约再次单测/Lint；不可仅runtime绿就称完整软件通过 |

命令模板中变量必须绑定接收方实际核验的绝对路径，不复制维护机私有路径：

```bash
python3 -B tools/run-host-tests.py --check-manifest
python3 -B tools/prepare-runtime-descriptor.py --check
python3 -B tools/test-runtime-descriptor-inputs.py
python3 -B tools/run-host-tests.py --stage fast --raw "$RAW" --managed "$MANAGED" --jdk "$JAVA_HOME" --toolchain-root "$TC" --pnpm-cli "$PNPM_CLI" --output "$OUT/fast"
python3 -B tools/run-host-tests.py --stage runtime --raw "$RAW" --managed "$MANAGED" --jdk "$JAVA_HOME" --toolchain-root "$TC" --pnpm-cli "$PNPM_CLI" --previous-runtime-apk "$PINNED_PREVIOUS_APK" --playwright "$PLAYWRIGHT" --browsers-path "$BROWSER_CACHE" --browser-engine chromium --browser-profile default --output "$OUT/runtime"
```

以上模板不是完整requires清单；仍可能需linux-host/native、generated recovery、mobile upstream、browser React和当前编译classes等。前序通过才进后序，资源/framework缺失只记缺证，不用旧classes或SDK桩替代L2。descriptor默认调用会写，验收只`--check`，失败不随意重写身份求绿。

新浏览器helper目前只接入两个目标fixture，其他直接launch测试未证统一消费executable/profile。不靠关闭Web安全或反复叠加兼容flag求绿。

`--stage all`会按环境隐式排除某些阶段，不等价完整门禁。package测试通过也不是APK构建或签名安装通过。原纯javac宿主验证和WebRun source-slice不替代完整Controller/Robolectric。

## 4. 必须保留的证据

完整源码身份、输入清单、宿主身份/资源及准入结果、每项命令/输出/退出码、fixture bindings、首尾完整byte proof、fresh unit XML、Lint结果与软件门禁摘要。不能拼不同代码/平台/轮次通过数求一个全绿结论。

本次不执行assemble/package APK/sign/publish/install/upload，不配置Secrets，不使用SSH凭据，不创建远端付费资源；剩余前提是一台明确获准且实际可访问的独立Linux执行环境。
