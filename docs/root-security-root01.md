# Root-01：普通请求派发前撤销门禁

> 历史阶段记录，不能用于当前构建验收。后续复验已证实本文将 claim 等同实际派发的表述不成立：撤销完成时仍可能为 0 字节，随后旧请求继续写入。当前结论是 P0 未消除、仅可 Draft，见 [交付审查](root-security-root01-delivery.md) 与 [完整矩阵](root-security-root01-matrix.md)。下文保留原阶段与历史 APK 的证据边界，不移植为本轮 PASS。

日期：2026-11-14。基线 `4027e4c8a84fa25b09d9b3d7e6b8f833b898dd1e`，分支 `codex/root-security-feasibility`。

状态：代码、电脑端定向验证及修复候选的基础真机验收完成。真实手机上的 helper 等待竞态尚未注入，完整 Root 安全专项仍未验收通过；尚未提交上游 PR。

## 修复行为

普通 `RootShell.exec` 及两个受管虚拟屏启动入口在检查命令前捕获本次授权许可。ConfigStore 的 Root 开关或策略 setter 每次更新都会使旧代次失效；关闭再开启、Full 降至 Strict 均不能复活已经检查过的请求。

helper 启动后、stdin 交付前消费一次性许可。若已经撤销，返回 `ROOT_AUTHORITY_CHANGED_BEFORE_DISPATCH`，不写命令；虚拟屏 typed 结果明确为 NOT_SENT。许可属于创建它的进程内 gate，不能由另一个 gate 使用或并发重复消费。

设置保存同步抛出异常时，进程内 gate 保持不可用，直至显式成功更新设置；读取权限异常按拒绝处理。该机制不保证 SharedPreferences.apply 的异步磁盘落盘成功，也不扩大为跨进程授权账本。

claim 是派发线性化点。claim 与设置变更使用同一锁；输出流 I/O 在锁外，避免卡住撤销。claim 后出现撤销或管道写入/关闭异常，不回滚已发生的副作用；写入异常仍报告 EXECUTION_UNKNOWN，许可不可复用，不自动重放。后续 Root 管理器才显示授权弹窗等平台行为，也不能把已经交付的请求重新称为未发送。

## 验证证据

### 红绿与负控

- 门禁组件先以布尔状态实现，回归测试发现 4 个失败：关开 ABA、策略变更、保存异常、读取异常；加入代次和失败关闭后通过。
- 实际调用真实 `RootShell.exec`，使用普通 Java 进程替代 su，在 `ProcessBuilder.start` 后的槽位登记边界暂停。撤销、关开、Full→Strict 三项均要求返回精确的拒绝标记，而不是任意错误。
- 负控临时恢复基线中的直接 `input.write` 行为，实际 RootShell 三项回归全部失败（3 tests、3 failures、0 errors）。脚本 finally 恢复 RootShell 和 runtime-descriptor 原字节，随后重新跑绿色回归。
- 这些测试在电脑模拟 Android 环境执行，不提权，不执行请求里的设备命令；不将其写为手机 KernelSU 竞态实测。

### 最终定向回归

真实 Gradle `testStandardDebugUnitTest` 运行 11 个测试类，共 **86 项，0 失败、0 错误**：

| 范围 | 测试数 |
| --- | --- |
| 既有 broker、槽位、watchdog、通道选择、命令策略及候选路径测试 | 69 |
| RootRequestGateTest：撤销、ABA、策略、异常、并发与锁外写入 | 11 |
| ConfigStoreRootRequestTest：真实设置入口、跨 ConfigStore 实例和损坏偏好 | 3 |
| RootShellRequestGateTest：真实执行路径的 helper 后交付检查 | 3 |

另有 core-boundaries 9 项通过；源码卫生通过，UI 文案门禁无新增问题。新增 Java 文件加入显式格式清单，使用锁定格式化器核验。未运行全仓全量测试或全量 lint；release 构建执行的 lintVital 不能替代全量 lint。

## 候选构建与本地交付

`assembleStandardRelease -Pdsha.identity=upstream -Pdsha.unsignedPackage=true -Pdsha.rebirthCandidate=true` 成功。该候选编译了当前生产改动；运行时描述符由官方脚本生成，runtimeId 为 `cc09e2b5ebe5c66c861a92c9ed02fe19dc8460bbcde854de86d712e1f2ddc1fa`。

| 字段 | 值 |
| --- | --- |
| 应用 / 包名 | DSHA 新生 / `dsh.android.client.rebirth` |
| 版本 | build359，`0.2.1-rc.5-20261111.1200-dsh0.2.2-rc.1-Rebirth-Sprout.1`（沿用冻结基线版本号，不代表旧源码） |
| APK 文件 | `DSHA-Sprout-Root01-localtest.apk` |
| APK SHA-256 | `723ff1722be1f7b0763894cf09cc9d4b4134ff5658800afe34f2440d8450638b` |
| 本地测试证书 SHA-256 | `a990dd51923e18c68b89a80d1ad9a931ea8af91666411d3a0c9bcaa62ed397e0` |
| 签名身份 | CN=DSHA Root01 Local Test ONLY；非官方发布证书、非 Sunset 发布证书 |
| 手机文件 | `/sdcard/Download/DSHA-Sprout-Root01-localtest.apk`，设备端哈希一致 |

测试私钥在仓库外，仅用于本地验收，不提交或发布。现有原版及 Sunset Dev 不被此独立包覆盖。USB 安装曾返回 INSTALL_FAILED_USER_RESTRICTED；用户随后手动安装，已从实际安装位置读取 base.apk 哈希，确认与上表修复包一致。

## 修复候选基础真机验收

设备：Xiaomi 17 Pro Max / 2509FPN0BC / Android 17。使用 `dsh.android.client.rebirth` 本地测试签名包，非此前 Sunset 基线。新包需要用户在 Root 管理器中单独授权；用户完成授权并交还手机后开始以下测试。未修改 Root 管理器全局策略。

| 项目 | 结果 | 证据及限制 |
| --- | --- | --- |
| 已安装 APK 身份 | PASS | 从 /data/app 下实际 base.apk 读取 SHA-256，与修复 APK 完全一致；版本为 build359 / Rebirth-Sprout.1 |
| 运行环境与启动 | PASS | 原生启动页环境 READY，工作台可进入 |
| 严格档 Root id 验证 | PASS | 原生验证弹窗返回 `uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0`，`[EXIT=0]` |
| 关闭 Root 开关 | PASS | 开关取消选中，状态变为“未启用”；严格档保持选中 |
| 关闭后点击验证 | PASS（UI 范围） | 保持未启用，没有执行结果弹窗；未安装底层派发计数探针，不能将此观察外推为所有入口均无派发 |
| 关闭后终止并重启独立应用 | PASS | 重启后开关仍关闭、状态未启用、策略仍为严格；原工作台实例退出被启动页识别 |
| 重启后重新开启并验证 | PASS | 新一次原生 id 弹窗再次返回 UID 0 / ksu 上下文 / EXIT=0；此时工作台尚未启动，原生 Root 验证可以独立工作 |
| 恢复测试前工作台运行 | PASS | 重新启动到 READY、工作台可进入、Web 端口 41983；本次启动页显示用时 12.4s |

末态：Root 开启、严格档，工作台已就绪；没有更改其余通道设置、隐私能力开关或其他应用数据。未开启放宽/完全放宽档，未执行写文件、清理、刷写等命令。

这是修复候选的基础功能与状态持久化验收，不是手机 Root 管理器撤销、任意命令隔离或真实 helper 等待竞态的完整验收。三个可控竞态的红绿与负控证据仍来自前述真实 RootShell 代码的电脑模拟测试。

## 独立审查

独立审查未发现本次窄范围内的必须修复项，核对了普通请求入口、最终交付、Root 设置写入口、异常语义和备份恢复范围。补充审查确认真实执行链测试的暂停点和拒绝断言有效；采纳了失败路径等待 worker 有界退出的建议。

## 尚未完成

1. 手机上对 helper 等待期间撤销的受控故障注入；现有电脑模拟与负控不替代这一真实系统场景。
2. Root 管理器拒绝/撤销、用户取消、停止 runtime 与真实子进程退出等更大范围生命周期验收。
3. 短信独立授权撤销、外接 Root 路径、ADB/Shizuku 断链后禁止重放的全链路测试。
4. Full 用户专用边界、同 UID 插件隔离与强人工批准等团队设计问题。

本次不改变 Full 模式产品语义，不宣称能防恶意同 UID 插件或任意 UID 0 程序，不将电脑负控等同于真实手机漏洞利用。
