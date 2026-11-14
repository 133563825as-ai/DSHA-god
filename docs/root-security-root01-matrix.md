# Root-01 完整需求矩阵

日期：2026-11-14。源码提交与逐项 JUnit 结果绑定在 [证据清单](root-security-root01-evidence.json)。[交付报告](root-security-root01-delivery.md)说明范围和 P0。

每行输入/条件为左侧场景，预期统一是：撤销前按独立权限处理；撤销后未消费旧许可拒绝，未知结果不重放；要求实际交付原子性的行按实际字节事件判断。PASS 仅适用于“证据层/实际结果”列，不扩展到真机或其他入口。NOT_RUN 不是环境故障推断；BLOCKED 有明确设备阻断。没有将全部维度的笛卡尔积伪装成穷尽覆盖。

本轮组合：严格/Full→Strict、原生 exec/三类外接、helper 后 claim 前、关/开 ABA、一个/12 个并发消费者、成功/异常写、坏偏好；不执行真实有副作用命令。受控内存 payload 为 synthetic-request；真实 RootShell 模拟请求 id/合成外接 adb argv，su 候选替换为本机 Java，绝不提权。

## A：基础状态

| 编号 | 输入/条件 | 状态 | 证据层/实际结果 |
| --- | --- | --- | --- |
| A01 | Root 从未启用 | PASS | 单元 disabledRequestCannotObtainPermit，capture=null |
| A02 | 已启用但管理器未授权 | BLOCKED | 无本地设备，未取得当前管理器证据 |
| A03 | 管理器明确拒绝 | BLOCKED | 同上 |
| A04 | 管理器允许、UID 0 | BLOCKED | 旧报告不代替当前构建 |
| A05 | 正常执行后关闭 App 开关 | BLOCKED | 没有当前 APK 的真实正常执行；内存撤销单独见 B01 |
| A06 | 关闭后再次请求 | PASS | 单元 disabledRequestCannotObtainPermit/disabledBeforeHandoverWritesNoBytes；0 字节 |
| A07 | 关闭后重启 App | BLOCKED | 当前包未安装，历史重启不沿用 |
| A08 | 关闭后重新启用 | PASS | gate 旧许可拒绝，新许可写入；ConfigStore 跨实例 ABA 测试通过 |
| A09 | KernelSU 撤销 | BLOCKED | 无设备；不承诺已启动 UID 0 helper 即时退出 |
| A10 | 管理器状态变化 | BLOCKED | 无设备，无检测延迟证据 |
| A11 | 保存失败 | NOT_RUN | gate 同步抛错测试通过，真实 apply 磁盘失败未注入；P0 待验 |
| A12 | 配置损坏/读取异常 | PASS | 单元任意坏串、true/1 字串、整数 1 均拒绝；supplier 读取异常拒绝 |
| A13 | 管理器不可用 | BLOCKED | 无设备；候选路径静态测试不能替代管理器 |
| A14 | su 可见但非 UID 0 | BLOCKED | 未运行真实 Android helper UID 负向验证 |

## B：撤销窗口

| 编号 | 输入/条件 | 状态 | 证据层/实际结果 |
| --- | --- | --- | --- |
| B01 | 请求创建前撤销 | PASS | gate 禁止获取许可 |
| B02 | 上游排队、未批准 | NOT_RUN | Root 无内部队列；上游不同请求来源未逐一注入 |
| B03 | 审批弹窗显示时 | BLOCKED | 无当前设备/弹窗注入 |
| B04 | 已批准、尚未派发 | PASS | 仅 gate capture 后 update，再 write=false/0 字节；非全部审批 UI |
| B05 | 第一次检查后、最终校验前 | PASS | 真实 RootShell + Robolectric helper-start 屏障；精确拒绝码 |
| B06 | claim 后、实际首字节前 | FAIL | knownGapClaimedRequestCanWriteAfterRevocation：撤销完成时 0 字节，释放后仍写全 payload |
| B07 | Helper 启动后、命令交付前 | PASS | 普通 Root 关闭/ABA/降级三项；外接 argv/session/steps ABA 三项，模拟层 |
| B08 | 业务命令已派发 | NOT_RUN | gate 消费不可复用有单测，但业务侧副作用及撤销未端到端注入 |
| B09 | 命令执行中 | BLOCKED | 无当前 Root 设备；不以 claim 事件代替命令开始 |
| B10 | 完成但结果未返回 | NOT_RUN | 结果层 UNKNOWN 单测不代替此具体时间窗 |
| B11 | 结果返回过程中撤销 | NOT_RUN | 无受控端到端结果流注入 |
| B12 | 多个不同 Root 请求等待 | NOT_RUN | 当前仅验证同许可 12 消费者；不同业务请求批量撤销未覆盖 |

## C：并发与异常

| 编号 | 输入/条件 | 状态 | 证据层/实际结果 |
| --- | --- | --- | --- |
| C01 | 同一许可重复派发 | PASS | 12 并发消费者只写一次；不可使用其他 gate 许可 |
| C02 | 多个不同请求等待 | NOT_RUN | 不把同许可竞争当不同请求覆盖 |
| C03 | 关闭后立即开启 | PASS | gate/ConfigStore/普通 Root/三类外接的 claim 前 ABA 均拒绝 |
| C04 | 高并发反复撤销 | NOT_RUN | 25 轮固定屏障回归非完整多请求随机撤销 |
| C05 | 改授权时进程退出 | BLOCKED | 无当前 App 故障注入；持久化 P0 未关闭 |
| C06 | App 重启 | BLOCKED | 当前 APK 未设备验收 |
| C07 | Helper 超时 | PASS | 仅 RootHelperWatchdogTest 期限/owner 纯逻辑；实际 su 超时未运行 |
| C08 | Helper 槽位耗尽 | PASS | RootHelperSlotsTest 满槽拒绝、未退出仍占槽纯逻辑 |
| C09 | Helper 不完整输出 | NOT_RUN | DeviceTransportResult 缺标记 UNKNOWN 已回归；RootShell 原始 Base64/结果解析尚未注入 |
| C10 | 执行线程中断 | NOT_RUN | 静态异常路径保留 UNKNOWN，未当作动态 PASS |
| C11 | 授权持久化失败 | NOT_RUN | gate 同步异常单测不代替异步磁盘失败 |
| C12 | 用户取消确认 | BLOCKED | 无设备；broker 取消/拒绝单测见证据清单，仅为局部证据 |
| C13 | 旧回调迟到 | NOT_RUN | broker 旧代次回归不代替全部 Root 回调来源 |
| C14 | 旧请求被新生命周期接收 | NOT_RUN | gate 进程代次不是整个 runtime 生命周期代次 |

## INV：安全不变量

| 编号 | 目标 | 状态 | 证据与范围 |
| --- | --- | --- | --- |
| INV-01 | 关闭不取得新 Root 能力 | PASS | gate 禁止 capture；仅 App 内受控入口 |
| INV-02 | 无有效管理器授权不标已验证 | BLOCKED | 当前管理器与 helper 身份未真机验证 |
| INV-03 | 撤销后旧未发送请求不派发 | FAIL | B06 稳定反例；B05/B07 通过不能抵消 |
| INV-04 | 失效票据不可复用 | PASS | gate 旧代次/其他 owner/重复消费/并发竞争；不等于所有原生审批票据 |
| INV-05 | 重新开启不复活旧请求 | PASS | claim 前的普通及三类外接回归；claim 后仍受 B06 限制 |
| INV-06 | 已发送且未知不自动重放 | NOT_RUN | 同 gate 写异常不可重用通过；全跨通道链路留 Root-02 |
| INV-07 | 普通批准不扩大敏感授权 | NOT_RUN | DeviceGrants/策略局部回归通过；Full 任意 shell 不构成完整隔离 |
| INV-08 | 未知/损坏配置不变宽松 | PASS | Boolean 严格读取、非法档位回严格的单元回归；不证明旧磁盘状态安全 |
| INV-09 | 不完整输出不能推断成功 | NOT_RUN | typed legacy 结果单测通过，原始 helper framing 尚未完整注入 |
| INV-10 | 撤销/执行/释放证据分离 | NOT_RUN | 槽位、watchdog、typed 结果分别回归，跨进程全过程尚缺 |
| INV-11 | 许可绑定请求/能力/摘要/身份/期限 | NOT_RUN | 当前 gate 仅 owner/generation/consumed，不能宣称完整绑定 |
| INV-12 | 单请求至多消费且跨通道不重放 | NOT_RUN | 前半 gate 局部通过；全局业务 ID 与跨通道规则待 Root-02 |

## S：敏感权限回归

| 编号 | 目标 | 状态 | 证据与范围 |
| --- | --- | --- | --- |
| S01 | Root 不自动授予 SMS API | NOT_RUN | SMS/DeviceGrants 局部测试通过；未追加当前 Root 开关跨模块端到端验证 |
| S02 | Root 不打开敏感界面控制 | NOT_RUN | 敏感屏幕策略原有单测通过；完整跨模块路径未验 |
| S03 | 全局允许不覆盖显式拒绝 | PASS | DeviceGrants/ScreenCapabilityGate 等既有回归；不外推任意 raw shell |
| S04 | Android 原生系统权限独立 | BLOCKED | 无当前设备运行权限验收 |
| S05 | 严格/放宽/Full 语义无退化 | PASS | RootPolicyModeTest 与 DeviceShellPolicy 原有回归；Full→Strict 失效 |
| S06 | 人工拒绝不因旧请求失效 | NOT_RUN | broker 拒绝与旧代次已有局部回归，整链路未验 |
| S07 | 备份恢复不激活旧授权 | NOT_RUN | 静态核对白名单不含 Root 键；未运行备份恢复端到端 |
| S08 | 普通确认不扩大敏感能力 | NOT_RUN | 局部 broker/策略回归；Root-03 仍有限制 |
| S09 | UNKNOWN 重试不扩大范围 | NOT_RUN | Root-02 全链路未完成；本补丁没有新增 fallback |
| S10 | 测试不通过关闭检查造绿 | PASS | 源/差异/描述符门禁；Robolectric 只替换 su 候选与同步屏障，没有关闭生产策略 |

## 设备、构建与证据

| 编号 | 条件 | 状态 | 实际结果 |
| --- | --- | --- | --- |
| D01 | 当前源码 APK 构建 | PASS | 独立未签名 Rebirth Standard release 构建成功，哈希见交付报告 |
| D02 | 当前 APK 安装身份/哈希 | BLOCKED | ADB 和 Mobile MCP 均无设备 |
| D03 | 当前设备严格 id/关闭/重启/重新开启 | BLOCKED | 同上；没有移植历史 PASS |
| D04 | 当前 KernelSU 等待竞态 | BLOCKED | 同上；电脑同步屏障不能代替真实系统行为 |
| D05 | Magisk/APatch/其他系统 | NOT_RUN | 未进行兼容性验证 |
| D06 | 真实敏感数据/破坏性命令 | NOT_APPLICABLE | 用户明确禁止；替代为合成输入，不能以此证明 Full 隔离 |

状态计数由 evidence JSON 的 matrixCounts 从本表计算，不手算或与 JUnit 数量相加。每个 NOT_RUN 都是可恢复待办，不能当作“没有问题”。
