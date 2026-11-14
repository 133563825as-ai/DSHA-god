# 独立 Linux 验证交接

本文件是验证执行说明，不是CI/远端/下载/安装/构建APK授权。冻结基线为`8b42e20bea9a0049761fad7d0cd9948e48838e4a`，修复尚未提交。以交接包的逐文件SHA为冻结身份，不用分支名或旧工作树声明推断最新状态。

## 当前真实结论

- RC1 Python47、CJS63，web-search旧完整模块恢复恰1次，统一选集3/3通过；前阶段host64及消费者22分次通过，不能合称单run完整PASS。
- 最终完整fast102：95PASS/7FAILED。三项toolchain setup未执行，CAS两项、handoff copytree及plugin-downloads unknown-counter失败保持原记录。runtime130未完整通过；mobile/recovery当前浏览器GPU子进程exit5在业务断言前中止，旧业务缺陷未证解决。
- 最终runtimeId：97675c75a1577fdbacf5ef27e596788d6e393270991979d474bae6808598957a。当前arm64 runtime归档131056363B/SHA c1bd68330a10776f304a92a19d8f82c184c1abf523275a9e28dbb2c2e764e4fe；归档字节与旧版相同，新recipe/builder输入另有新证明。
- 当前容器共享Android15内核；没有已配置Docker/Podman/系统VM/KVM。unshare/nsenter未执行，用户态QEMU不算独立Linux。没有连接远端或读取凭据。

## 冻结变更交接包

交接包只包含：基线到冻结状态的tracked差异`changes.patch`、新增文件`new-files.tar.gz`、逐文件基线/结果SHA和文件模式`manifest.json`、本说明与包校验摘要。不包含完整仓库、Git对象、旧暂停草稿、输入归档、node_modules、APK、截图或凭据。

接收方须已有可信本地仓库中该精确基线（包不自行fetch）。另建干净任务树，不在main/正式树或暂停树应用；先核HEAD和clean，再`git apply --check changes.patch`，检查新增路径不与已有文件冲突，应用patch并仅提取manifest明确列出的普通新增文件。拒绝绝对路径、`..`、链接、特殊成员和重复成员。应用后逐文件SHA/执行位必须全部与manifest一致；完整Git差异/新增路径集合必须与manifest相等，不得用reset/clean修正冲突。

包内不是授权执行所有工具的指令，`tools/dsha-gradle`明确是本容器诊断工具，所有项目任务硬拒；不能把它当已获准的完整构建wrapper。包内旧AGENTS平台规则仍有效，真正独立Linux宿主的Gradle执行入口须由其维护者明确准入，禁止直接绕过当前容器守卫。

## 独立宿主准入（执行前先确认）

1. 真正Linux宿主/独立VM内核，明确x64或arm64；具备可核MemAvailable/cgroup限额、磁盘和进程管理。严格串行，不与其他构建共跑；依赖失败不fallback修改系统。只读打开0444/0555文件不应造成ctime变化，Git只读对象复制须可重复保字节，浏览器必须实际打开本地空页并正常关闭。
2. 按`ci/toolchain.lock.json`、wrapper SHA、npm锁及各输入锁准备JDK17、Node24+、Python3.9+、Gradle9.3.1、SDK37/build-tools等。固定字节SHA核验，不以版本输出自证；Linux x64不能复用arm64 raw的native依赖。
3. 下载公开依赖、npm/pnpm安装、Gradle缓存填充、Robolectric依赖准备都需执行者另有明确许可。完整runtime含synthetic安装及lifecycle；不允许安装时只能报告未执行，不能skip后称full。执行脚本前先审所需网络/文件写范围。不读真实profile，不使用签名密钥/API凭据。
4. `prepare-sprout-host-jars.py`的非instrumented android-all仅作framework输入，不可改名替代`robolectric-sdk.lock.json`的instrumented SDK。缺任一真实输入即缺证。

## 推荐顺序与命令模板（非本轮已执行）

所有命令在接收方新任务树运行，输出用全新目录，不覆盖原回执。`$JAVA_HOME`/`$GRADLE_USER_HOME`/`$ANDROID_HOME`由经准入的真实宿主指定；不得复制本容器绝对路径。

### A. 源码与固定输入

先应用验证包并验SHA。按既有固定输入锁还原忽略的大文件，不用参考最新bundle替代本线锁。对当前arm64发行输入执行：

```bash
python3 -B tools/run-host-tests.py --check-manifest
python3 -B tools/prepare-runtime-descriptor.py --check
python3 -B tools/test-runtime-descriptor-inputs.py
python3 -B tools/test-runtime-descriptor-source.py
```

descriptor `--check`失败先查真实输入，不能在接收方随意`--write`改身份求绿。

### B. 本机架构测试依赖与浏览器

执行者批准联网/安装后，按现有prepare脚本建立本机架构raw/managed；x64宿主用`prepare-test-runtime.py --platform host`，不要取arm64 native树。此脚本会执行锁定npm ci（ignore-scripts）并生成managed，必须先核其行为和对应归档来源；不要用`--allow-stale-archive`给正式门禁豁免。ARM64发行归档与x64宿主夹具分别记录身份。`build-dsh-runtime.py`明确拒绝非arm64 ELF，不能把x64 host raw拿来重建arm64发行归档；后者应复用精确冻结的arm64归档及新.inputs.json，host夹具另建并核验与该归档的recipe绑定。

按`tools/ci-browser`锁准备Playwright与真实浏览器，固定cache路径。新统一helper只接入rc1-browser-fixture及recovery，不代表所有既有直接launch测试均已支持新的executable/profile。优先标准正常Chromium，不靠关闭安全或无限兼容flag跑绿。

### C. L1完整fast、L2完整单测/Lint、L3完整runtime

以`.github/workflows/sprout-candidate.yml` L1/L2/L3为现有顺序依据，先read实际脚本，不直接dispatch该workflow（后续包含APK与artifact上传）。以下只示范测试阶段：

```bash
python3 -B tools/run-host-tests.py --stage fast --raw "$RAW" --managed "$MANAGED" --jdk "$JAVA_HOME" --toolchain-root "$TC" --pnpm-cli "$PINNED_PNPM" --output app/build/linux-admission/fast
```

`TC`内必须是实际SDK和共享Gradle cache布局，不是空目录。pnpm须按固定归档安全提取并验版本/字节。source hygiene、i18n、文档链接和本次Java格式检查也须执行，不只运行fast。

仅在独立宿主维护者批准且守卫准入后，运行现有workflow列出的完整Standard unit、verify-unit-results full/require-fixtures、Lint及真实AGP source-map准备；禁止source-only作为完整单测结果，纯javac adapter不能替代这些Android输入。此说明不提供绕过本容器`dsha-gradle`的命令。

```bash
python3 -B tools/run-host-tests.py --stage runtime --raw "$RAW" --managed "$MANAGED" --jdk "$JAVA_HOME" --toolchain-root "$TC" --pnpm-cli "$PINNED_PNPM" --previous-runtime-apk "$PINNED_PREVIOUS_APK" --playwright "$PLAYWRIGHT" --browsers-path "$BROWSER_CACHE" --output app/build/linux-admission/runtime
```

以上全stage模板不是runtime130的完整输入清单；执行前按manifest每项requires逐项准入，可能还需linux-host/native、generated recovery、mobileUpstream、browser React等真实输入，不能因参数模板可运行就推定全部依赖已齐。

正式阶段不带`--only`，当前清单fast102/runtime130。前置缺失/失败如实保留，不将单个子集PASS_FOR_SELECTED_STAGES改称全绿；保留每项命令、fixture bindings、完整输出、退出码、首尾完整byte proof及unit XML/lint报告。上述runtime命令仅为L3-runtime子阶段；要与现有workflow完整L3软件门禁等价，还需运行其第151–152行的`verify-stability.py --stage software`（内部再次单测/Lint），使用同代真实Java/SDK/输入并保留结果。`--stage all`并不等于这三个阶段，不用其隐式排除package的行为证明完整准入。

**到L3测试为止。** 不运行workflow后面的assemble/package/sign/publish/install/upload，不触发CI。若发现需改依赖锁、DSH版本、权限或数据格式，先报告具体差异，不能在接收机顺手变更。

## 当前缺的唯一外部条件

需要一台明确授权、可访问且满足上述条件的独立Linux测试主机/现成执行器。本机没有该接入事实；本包不提供主机、不读取SSH配置、不创建付费实例、不触发CI。不以反复在同一Android容器尝试替代这个条件。
