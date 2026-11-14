# Root 安全专项范围与基线

> 当前验收与版本绑定见 [Root-01 交付审查](root-security-root01-delivery.md)；此页 APK 与测试表为历史阶段，不能作为当前源码的重新验收。

记录日期：2026-11-14。状态：阶段 0 已记录，团队安全规则尚待确认。

## 分阶段独立证据线

| 项目 | 固定基线 | 本轮能证明什么 |
| --- | --- | --- |
| 新仓库源码 | `133563825as-ai/DSHA-god`，`integration/sprout`，`4027e4c8a84fa25b09d9b3d7e6b8f833b898dd1e` | 对该源码的静态审计、现有纯 Java 测试 |
| 本地工作分支 | `codex/root-security-feasibility` | 首轮范围与结果，以及后续 Root-01 小范围修复；详见 [Root-01 记录](root-security-root01.md) |
| 手机 APK | Sunset build364 Dev，`io.github.sunsetrne.dsha.dev`，源码 `1545e10467c8ca9e9862e5facad3c4a070227e79` | 该 APK 在此手机上的功能基线，不能替代新仓库候选验收 |
| APK 完整性 | SHA-256 `be404b19ccd2cfd2ee22e20dae1b851db37961d158439be332035dfb91463b0a` | 与发布方哈希一致，apksigner 验证通过；不等于完整安全审计 |
| APK 测试证书 | SHA-256 `ed9693a6779a92fd94f2f0f42ad4a3e60086b3c3aea2853beff1ed6fcff8c141` | 独立测试身份，与正式版隔离 |
| Root-01 修复候选 | `dsh.android.client.rebirth`，SHA-256 `723ff1722be1f7b0763894cf09cc9d4b4134ff5658800afe34f2440d8450638b` | 新仓库基线加本地修复，86 项 Gradle 定向回归及基础真机验证完成；具体范围见 [Root-01 记录](root-security-root01.md) |
| 测试设备 | Xiaomi 17 Pro Max，型号 2509FPN0BC，Android 17 | 单一设备，不外推到 Magisk、APatch 或其他系统 |

## 已获授权与测试边界

用户明确授权在新仓库推进 Root 专项并使用连接的手机验收；先使用 Sunset 独立 Dev 作基线，随后用户手动安装并授权新仓库 Root-01 独立候选。两阶段只运行 `id`、检查设置状态、关闭并重新开启相应测试包的 Root 开关；修复候选另验证终止/重启后的关闭状态持久化。始终保留严格策略。

不读取真实短信、验证码、联系人、通知正文、私人文件、密钥或其他应用私有数据。不刷写、不格式化、不清除应用数据、不关闭 SELinux、不修改全局 Root 管理器策略。不将单次 UID 0 成功视为撤销竞态或敏感数据隔离通过。

手机初始状态：Root 已开启、严格档；Shizuku 已连接；应用内 ADB 开关已开启但无线连接未验证；外接设备能力关闭。测试结束恢复 Root 开启、严格档，其余通道设置保持原值。

## 待团队确认的产品规则

原方案的 L0–L4 分级、L2 每次强制人工批准、L3 用户专用、L4 不提供给 AI，以及将 AI 与用户 Full Root 权限拆开，均为拟议规则。用户授权开展研发，不代表维护团队已接受改变现有 Full 模式语义。

普通弹窗不是不可伪造的人类身份凭据；同 UID 插件、终端和应用进程不构成可信安全隔离。若保留 AI 任意 UID 0 命令执行，不承诺以命令文本过滤实现私人数据的全面隔离。

## 验收状态规则

- PASS：只对指定测试、基线、入口及设备成立。
- FAIL：有可重复的实际失败证据。
- BLOCKED：测试被工具或外部条件阻止，不计为通过。
- NOT_RUN：未执行，不以源码推测替代。
- SOURCE_FINDING：已核对的源码缺口或设计风险，尚未完成端到端复现。

执行结果保留 `NOT_SENT / REJECTED / COMPLETED / PARTIAL / UNKNOWN`，包含原计划遗漏的 PARTIAL。COMPLETED 不代表业务效果一定成功；结果 UNKNOWN 不允许自动重放。Root helper 槽位满时拒绝，当前没有 Root 内部排队。

## 本轮交付与下一门槛

首轮结果见 [审计与验收记录](root-security-first-pass.md)。下一项 Root-01 必须先建立可控制 su 启动时点的执行器测试，复现撤销与关开竞态，再修改普通 Root 派发边界，最后构建与新仓库源码一致的独立签名测试 APK。未完成这些步骤，不宣称新生版已安全验收。
