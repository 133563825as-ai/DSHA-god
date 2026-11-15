# Root 专项首轮审计与验收

> 当前 Root-01 交付状态以 [交付审查](root-security-root01-delivery.md) 为准：存在未消除 P0，只能 Draft；下文为修复前历史记录。

> 修复前的首轮快照。后续代码修复、负控回归及候选构建进度见 [Root-01 记录](root-security-root01.md)。

日期：2026-11-14。范围和固定基线见 [范围说明](root-security-scope.md)。

## 结论

方案可以分阶段实施。现有 CapabilityBroker、结果状态、Root 槽位和 watchdog 可复用，不应另建一套相同框架。首要工作是普通 Root 执行路径的撤销与策略变更失效，随后验证禁止跨通道重放。Full 模式的强隔离和强制人工批准需要单独设计及团队确认。

本轮完成源码入口核查、8 个现有纯 Java 测试类的运行，以及 Sunset Dev 的基本 Root 真机验证。没有修复生产代码；专项未完成安全验收。

## 调用链与边界

| 入口/层 | 位置 | 已核实行为 / 未覆盖部分 |
| --- | --- | --- |
| Root UI 与 id 验证 | `ui/DeviceChannelsFragment.java:64–99` | 开关写 ConfigStore；关闭时通知虚拟屏撤销；验证按钮先检查 Root 开关，再调用 RootShell.exec(id) |
| 权限持久化 | `core/ConfigStore.java:280–301` | 保存布尔开关及策略档位，使用 apply；这里没有普通 Root 请求授权代次失效逻辑 |
| 普通设备桥 | `HttpShellService.java:1090–1135` | 统一通道选择一次；Root 调 RootShell，Shizuku 单独执行，ADB 返回计划；异常不返回备用通道计划。尚未验证所有 guest 消费者及重连路径 |
| Root 普通执行 | `RootShell.java:102–124,555–618` | 入口检查开关与命令策略，启动固定 helper，再通过 stdin 交付命令；交付前没有复查原请求授权代次 |
| 外接 Root | `RootShell.java:379–435` | helper 启动后，交付 stdin 前有 enabled + dispatchCurrent 复核；check/write 仍不能单凭代码认定是无竞态原子派发 |
| 特权 helper | `RootShellMain.java:100,214` | root-unrestricted 路径调用 execUnrestricted；安装包身份核验不能替代实时操作授权 |
| 通道选择 | `util/DeviceChannelOrder.java` | Root 优先；只选一次；forceRoot 表示需要 Root 身份，可能选 ADB + su，不等于强制本机 su 通道 |
| 通用授权 | `capabilities/CapabilityBroker.java` | 代次撤销、单个确认槽、一次性 claim；适配器仍须复核运行时/目标/系统许可。不能把其测试通过当成普通 Root 已接入 |
| 结果 | `capabilities/CapabilityResult.java`、`device/DeviceTransportResult.java` | 区分执行事实、输出完整性和本地/远端退出证明；仅 NOT_SENT 具备显式重新选择资格，仍需新授权 |
| 并发/生存期 | `util/RootHelperSlots.java`、`util/RootHelperWatchdog.java` | Root helper 最多 3 槽；未真正退出仍占槽；owner 出生身份、截止时间与 PID 复用判定。不是所有 Root 子进程均已真机确认退出 |

上述路径均相对 `app/src/main/java/com/deepseekharness/app/`。插件、终端、SAF、备份、屏幕控制及外接设备的完整入口覆盖仍未完成。

## 首要源码发现：普通 Root 派发前复核缺口

状态：SOURCE_FINDING，优先级候选 P0，未完成稳定竞态复现，不作为已证实的手机漏洞。

`RootShell.exec` 在进入时检查 enabled 和策略，但 `runPrivileged` 在 `startHelper` 后直接写入命令。若权限关闭或 Full 降为 Strict 发生在检查后、命令发送前，当前普通路径没有对应的请求代次与策略版本复核。ConfigStore 的开关变更也没有使普通请求许可失效。外接路径已有二次复核，可作为对照，但不能直接复制布尔判断就宣布解决关→开 ABA 或原子性问题。

修复验收要求：

1. 用可控 barrier 暂停在 su/helper 启动和 stdin 交付之间，不靠随机 sleep 制造竞态。
2. 撤销后释放 barrier，断言未交付命令且副作用计数为 0。
3. 关→开后旧请求仍无效，新请求按新代次执行。
4. 降低策略、取消、运行时退出使旧许可失效；同一许可并发消费只能一次。
5. 明确定义派发线性化点；已经派发不能假报 NOT_SENT、不能声称撤销能回滚既有副作用。
6. 构建对应新仓库测试 APK，再做独立包真机复测。

## 自动测试

使用 JDK 17 + 缓存 JUnit 4.13.2，直接编译仓库现有纯 Java 测试，生产源未使用替身。UI 文案由仓库官方 prepare-ui-languages.py 生成到仓库外审计目录。

| 测试类 | 证据 |
| --- | --- |
| CapabilityBrokerTest、RootHelperSlotsTest、RootHelperWatchdogTest、DeviceChannelOrderTest | 32 项通过 |
| RootPolicyModeTest | 8 项通过 |
| DeviceShellPolicyTest、DeviceShellPolicyDeleteTest、RootShellCandidatesTest | 29 项通过 |

以上合计 69 项，不包括 Android/Robolectric 全量测试、APK 构建、真实跨通道故障注入或完整安全矩阵。策略测试中的命令字符串只交给解析器，未在手机上执行。

## 真机验收矩阵（Sunset build364 Dev）

| 用例 | 结果 | 实际证据与限制 |
| --- | --- | --- |
| 定位 Root 原生入口 | PASS | 设置 → 设备能力授权 → 设备执行通道；严格档被选中 |
| Root 已开启时验证 id | PASS | 原生结果弹窗 `uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0`，EXIT=0 |
| 关闭 Root 开关 | PASS（UI） | 状态变为“未启用”，开关取消选中 |
| 关闭后点验证 | PASS（有限 UI 观察） | 保持“未启用”，未出现执行结果弹窗；未安装 native 派发计数探针，不能宣称证明底层完全无派发或无其他入口 |
| 重新开启并再次验证 | PASS | 新一次 id 弹窗返回相同 UID 0 和 EXIT=0；严格档保留 |
| Full 升级弹窗取消与防误触 | BLOCKED（工具审批） | 点击“完全放宽”被自动审批视作扩大 Root 执行权限而拒绝；未通过其他方式绕过。源码可见三项勾选及 10 秒倒计时，手机交互仍未验收 |
| helper 启动等待期间撤销 | NOT_RUN | 缺少可控派发探针；普通 UI 快速 id 不足以覆盖该竞态 |
| 关开后旧任务不复活 | NOT_RUN | 不用通用 broker 单测冒充普通 Root 集成结果 |
| Root 管理器拒绝/撤销 | NOT_RUN | 本轮未修改 KernelSU 管理器授权；既有 UID 0 helper 是否退出另需验证 |
| 发送后超时/断链禁止跨通道重放 | NOT_RUN | 未注入真实通道故障，没有实际分通道发送次数证据 |
| 真实隐私数据 / 破坏性系统命令 | NOT_RUN | 不使用真实敏感数据或破坏操作验收；后续使用合成目标 |

手机末态：Root 开启、严格档；Shizuku 和 ADB 设置保持初始值，外接设备关闭。没有创建测试目录、修改用户文件或更改 Root 管理器设置。

## 计划应调整的地方

- Root helper 没有队列；“排队撤销”应拆成上游请求等待与 helper 启动等待，不给不存在的 Root 队列写验收结果。
- 保留 PARTIAL；COMPLETED 与业务效果成功、输出完整、远端进程退出分别判断。
- 既有 Full 的持久化是现有产品设计，不能把“重启仍为用户选择的 Full”直接记作回归；临时批准和旧任务许可不得复活是另一要求。
- 用户取消、应用撤销、Root 管理器撤销和 runtime 停止分开建模。已有 UID 0 进程不保证随 Root 管理器取消授权立即结束。
- 同 UID 的不同进程不构成独立权限边界；真正强隔离要限制能够使用 su 的主体与操作接口，不依赖 caller 字符串或命令文本黑名单。
- 强制人工批准与禁止 AI Full 属于团队设计决定，独立评审；不混进普通 Root 撤销的小修复。

## 后续推进顺序

1. Root-01：先复现普通请求派发前撤销/ABA/策略降级，再接入授权代次及派发门禁；精确定义已派发语义。
2. Root-02：审查 guest 消费者、重连与异常转换，以每个通道的实际派发计数证明 UNKNOWN 不重放。
3. 团队确定 L0–L4 与 Full 用户专用边界后，再做强人工批准和执行器隔离设计。
4. 在新仓库可用的独立签名候选 APK 上复测；补 KernelSU 管理器撤销、后台与重启，再安排其他 Root 方案兼容性。

本轮未提交、未推送、未创建 PR；不能使用“Root 安全问题已修复”作为对外结论。
