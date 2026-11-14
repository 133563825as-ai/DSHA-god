# Root-01 交付审查：未通过完整安全验收

日期：2026-11-14。此页取代早期记录对当前交付状态的描述；早期手机与电脑结果保留为历史证据。

## 结论与交付门禁

**只能创建 Draft PR，不可合并。** 本轮修复了三类外接 Root 请求在 helper 等待期间关→开后复活，以及错误类型 Root 偏好被兼容转换为允许的问题。继承并复验了普通 Root 模拟调用链，静态审阅虚拟屏的进程内代次门禁接入；没有将虚拟屏写成动态验收通过。

仍有两项 P0 验收阻断：

1. **已复现的实际交付窗口**：许可 claim 完成后，输出流尚未写入任何字节时可以完成撤销；释放屏障后旧载荷仍写入。claim 只是消费许可，不能称为已发送。`RootRequestGateTest.knownGapClaimedRequestCanWriteAfterRevocation` 是缺陷表征测试，JUnit 通过意味着反例稳定存在，**不是 INV-03 通过**。
2. **持久化保证缺失**：Root 开关与策略仍使用 `SharedPreferences.apply()`。同步异常使 gate 失败关闭，但无法取得异步磁盘写入成功证明；进程死亡后的旧磁盘状态没有得到安全排除。本轮未注入真实磁盘故障，不宣称已经证实实际手机权限复活。

未将“加一次锁外检查”当作第 1 项的修复，也未在 UI 线程把 apply 全量替换为阻塞 commit。前者保留相同竞态，后者会引入管道/磁盘阻塞和响应性问题。需要受控交付协议、期限和撤销生效点的共同设计，及独立持久化确认/异常恢复测试；完成前保持 Draft。

## 冻结基线与变更范围

- 开发仓库：`133563825as-ai/DSHA-god`。
- PR Base：`integration/sprout`；fetch 后 SHA 为 `4027e4c8a84fa25b09d9b3d7e6b8f833b898dd1e`。
- 独立分支：`codex/root01-revocation-delivery`。原 `sprout-root-audit` 工作树、活跃声明和未提交工作未改动。
- 仅复制已有 Root-01 的三个生产文件、三个测试文件、格式登记和历史专项记录；运行时描述符使用仓库脚本重新生成。
- 不修改正式仓库、正式签名、默认策略、Secrets，不合并或发布 APK。
- 证据源码提交见同目录 `root-security-root01-evidence.json`。其中每个 PASS 绑定该源码版本；文档后续提交不改变被测源码。

## 调用链与实际边界

| 阶段 | 当前路径与结论 |
| --- | --- |
| 来源 | 原生验证按钮、HTTP 设备桥、受管虚拟屏、三类外接 Root 请求 |
| App 授权 | ConfigStore 仅接受真实 Boolean true；默认关闭、读取异常拒绝。App 设置不等于管理器授权 |
| 策略 | 普通 exec 经过 HostDeviceCommandGuard、DeviceShellPolicy 和独立 SMS 授权；三档策略语义保持 |
| 管理器 | 固定候选 su 启动 RootShellMain；helper 核验 UID 0 与安装身份。没有本轮真实 KernelSU 状态证据 |
| 等待 | 普通 Root 无内部队列；最多 3 个 helper 槽位。上游批准等待与 su/helper 启动等待分别建模 |
| 派发前 | 所有 Root 入口捕获相同进程内 gate 的代次许可；设置变化使未消费旧许可失效。外接还检查 dispatchCurrent |
| 实际交付 | gate 消费后锁外写 JSON、关闭 stdin；helper 等待 EOF 后解析。消费、字节写入、EOF、helper 接受、业务开始是不同事件 |
| 结果 | 普通和外接路径保留超时/截断/缺结果标记 UNKNOWN；未改返回解析，不声称已完成所有损坏输出故障注入 |
| 撤销 | claim 前撤销、关开、降级在模拟路径拒绝；claim 后仍有上文 P0 窗口。管理器撤销没有即时杀死已有 UID 0 进程的保证 |
| 释放 | finally 关闭本地流并登记槽位；未确认退出的 helper 不因本地关闭而释放为已退出；watchdog 独立判断 owner/期限 |

HTTP `deviceExecute` 只选择一次通道，异常不会生成备用 ADB 计划。此静态结论与现有结果/通道测试不替代所有 guest 消费者的 Root-02 全链路重放验收。

## 本轮修复

1. 共同 RootRequestGate：代次变更、一次性消费、gate 身份、读/同步保存异常拒绝；普通 Root 和虚拟屏捕获许可后在交付前复核。
2. 三种外接操作 `external-argv`、`external-adb-session`、`external-adb-steps` 同样从请求入口携带 Root permit 到 helper 后交付点。外接自己的授权条件继续独立检查。
3. `ConfigStore.isRootShellAllowed` 不再对授权配置使用通用标量兼容转换。字符串 `true`、字符串 `1`、整数 `1` 都不能授权；`RootShell.enabled` 使用同一失败关闭读取。
4. 更正代码和记录中“claim 即实际派发”的错误表述；保留稳定反例，禁止把全绿 JUnit 当成全安全验收。

## 测试与构建证据

| 层次 | 实际执行结果 |
| --- | --- |
| 继承修复基线复验 | 11 类、86 项、0 失败、0 错误、0 跳过；在独立工作树重新运行 |
| 新增负控 RED | 12 项中 6 失败：三类外接 ABA、三种错误类型；均命中预期断言，无编译/夹具错误 |
| 修复后与最终回归 | 21 类、161 项、0 失败、0 错误、0 跳过；完整逐项清单在 evidence JSON |
| 重复竞态 | 25 轮 × 11 gate 测试 = 275 次，0 JUnit 失败；包括每轮确认 P0 仍存在的缺陷表征，不是穷尽调度 |
| 干净工作树重现 | 从源码提交独立检出，11 项纯 gate 测试通过，运行前后工作树干净；未重新执行全套 Gradle/Android 测试 |
| APK 构建 | `assembleStandardRelease` 成功；独立 Rebirth 未签名候选，未安装、未发布 |
| 当前设备 | Mobile MCP 返回空设备列表，提升权限后的 `adb devices -l` 也为空；本轮真机项 BLOCKED |
| 历史设备 | 早期本地报告包含 UID 0 与关闭/重启观察；不移植为本次源码 PASS，不宣称核验了当前 APK |

当前候选 APK：`app-standard-release-unsigned.apk`，316585176 字节，SHA-256 `e5e50da9ef198ca6c16c5c19eb6dbf34978197bcb25d9054698d20f703b8358a`。运行时身份 `970a385aa3a5bcfb6bfb168a68aaaaf4e9049b7facf5dd3939d4f04791c09566`。此文件不是可交付的正式签名 APK；源码构建通过不证明设备验收。

完整请求清单和验收状态见 [Root-01 验收矩阵](root-security-root01-matrix.md)。逻辑矩阵的 FAIL/BLOCKED/NOT_RUN 与 JUnit failures 是不同统计。

### 可复现命令

使用 Java 17、仓库锁定 Android SDK/Gradle、固定离线输入和 Python。Windows 设置 `JAVA_HOME`、`ANDROID_HOME`、`DSHA_PYTHON`，本轮 Robolectric 使用 Maven 官方仓库 URL。已有缓存可加 `--offline`；全新机器先按 BUILD.md 与固定输入流程准备，不跳过校验。

```text
gradlew.bat --no-daemon -Pdsha.identity=upstream -Pdsha.unsignedPackage=true -Pdsha.rebirthCandidate=true :app:testStandardDebugUnitTest --tests *RootRequestGateTest --tests *RootShellRequestGateTest --tests *ConfigStoreRootRequestTest --tests *CapabilityBrokerTest --tests *RootHelperSlotsTest --tests *RootHelperWatchdogTest --tests *DeviceChannelOrderTest --tests *RootPolicyModeTest --tests *DeviceShellPolicyTest --tests *DeviceShellPolicyDeleteTest --tests *RootShellCandidatesTest --tests *ExternalTargetConfirmationTest --tests *ExternalToolPolicyTest --tests *DeviceTransportResultTest --tests *DeviceGrantsTest --tests *DeviceGrantsSensitiveScreenTest --tests *DeviceGrantsPreferenceRecoveryTest --tests *SensitiveScreenPolicyTest --tests *SmsQueryTest --tests *ScreenCapabilityGateTest --tests *TurnCapabilityGrantsTest
gradlew.bat --no-daemon -Pdsha.identity=upstream -Pdsha.unsignedPackage=true -Pdsha.rebirthCandidate=true :app:assembleStandardRelease
python tools/run-host-tests.py --check-manifest
python tools/check-source-hygiene.py
python tools/generate-changelog.py --check
python tools/check-document-links.py
python tools/format-java.py --check --files changed
```

RED 版本发生在此分支最终生产修复前：在被复制的原 gate 修复上增加六个负控。evidence JSON 保存负控测试名称与错误信息摘要，避免将后续绿色结果覆盖原始负控。本地完整日志在工作区外的 progress/2026-11-14/root01-delivery，公开提交只收录脱敏摘要和摘要校验，不包含原始设备日志或密钥。

### 环境失败分类

- 首次 Gradle 在沙箱中不能获取工作区外 wrapper 锁；使用已授权工具链权限后恢复。
- Windows 无 `python3` 命令导致 prepareBackupAssets exit 9009；设置仓库支持的 DSHA_PYTHON 后恢复。
- 修改 RootShell 后源摘要校验拒绝旧 runtime descriptor；使用官方 prepare-runtime-descriptor.py 重算后恢复，未关闭校验。
- 严格路径解析在沙箱对新文件返回 WinError 5；提升权限后锁定格式器通过，未修改格式器安全检查。
- 直接 JUnit 首次未能枚举沙箱外 jar，编译找不到 org.junit；在可读缓存的同一工具权限下通过。
- 以上均不是产品 PASS，也没有作为放宽安全规则的理由。

## 敏感能力与剩余范围

本次没有修改 DeviceGrants、原生系统权限申请、SMS 查询策略、敏感屏幕策略、备份恢复授权白名单或 Full 模式默认值。定向回归覆盖这些受控接口的既有拒绝、损坏配置、策略修订及恢复测试；不等于证明任意 Full Root Shell 无法接触敏感数据。

Root-03 的高风险边界仍在：Full 原始命令拥有任意 UID 0 执行能力；同 UID 插件不是隔离主体；受控 SMS API 的独立授权不能作为任意 root shell 的数据隔离证明。没有读取真实短信、银行数据或私钥验证这些风险。

INV-11 所要求的命令摘要/审批身份/期限绑定不是本 gate 的完整能力；gate 仅有 owner、generation、consumed。Root-02 的跨通道未知结果重放、Root-03 的 Full 隔离、Root-04 的不可绕过人工批准分别继续跟踪，不能标记已经完成。

## 独立审查与下一检查点

专职审查按 AGENTS.md 与 external-pr-review.md 逐行覆盖生产、测试、生成文件和文档。初审 REQUEST_CHANGES，指出两个 P0 阻断、外接代次遗漏和错误类型授权；后两项有本轮 RED→GREEN 修复。最终审查结论附在 evidence JSON，不能把作者自查当作独立 APPROVE。

下一步先设计并复现真实交付原子边界与撤销持久化，再解决二者的失败关闭行为；接回设备后，使用当前源码独立测试签名 APK，验证安装哈希、管理器拒绝/撤销、helper 等待竞态与进程生命周期。两项 P0 和真机证据齐备前，不转 Ready、不合并。
