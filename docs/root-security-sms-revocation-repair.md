# Root 原生短信授权撤销修复：仍保持 Draft

日期：2026-11-23（Asia/Shanghai）。本增量只修复原生短信查询进入 Root helper 的 F02，不宣称完成整个 Root 安全专项。

## 修复行为

- 短信授权变更在保存前、保存后各使旧 Root 许可失效；与最终确认字节和 EOF 使用同一 Root gate。关闭再开启不能复活旧请求。
- 保存期间、保存失败或通知异常时，短信授权不可用。SharedPreferences 在 commit=false 时留下的内存 true 不构成有效授权；显式成功保存可以恢复。
- Root 普通执行路径在最终交付时再核对短信授权。通知与磁盘保存不持 Root gate；保存专锁不由最终交付获取，避免锁顺序反转。
- **保守影响：修改短信设置会取消所有尚未派发的旧 Root 许可，包括普通请求。Root 开关不会因此关闭；新普通请求仍可执行。** 已越过确认边界的请求不回滚，UNKNOWN 不自动重放。

## 版本与证据

验证源码提交：`f8d14fecf2e3d52b811feb6f29502e1c86aebf99`，基于既有 Root PR4 `ac7e86596a5073e7d603f4c57653fb5d4514bc2d`。应用生产源码、JVM测试和描述文件须与该验证源码逐字节一致。后续允许增加独立androidTest验收组件、修复旧androidTest编译接口、登记格式清单及修复CI固定依赖缓存；临时构建工作流不进入产品树，具体允许路径列在证据文件中。

| 阶段 | 真实结果 | 范围 |
| --- | --- | --- |
| RED，9b246a7a | 4542 tests / 4 failures / 0 errors / 0 skipped | 四个新增短信撤权/关开 ABA 断言失败；不是环境失败 |
| GREEN，f8d14fec 定向 | 49 tests / 0 failures / 0 errors / 0 skipped | RootShell、ConfigStore、DeviceGrants、Root gate 与设置授权 |
| GREEN，f8d14fec 源码模式 JVM | 4545 tests / 0 failures / 0 errors / 0 skipped | source-only 模式；不能扩写为完整发布或设备安全验收 |
| GREEN，f8d14fec 宿主 fast | 99 个回归入口全部 PASS | 入口数量，不是所有内部断言的用例总数 |
| 生成与门禁 | contracts、java-format、固定输入及官方描述文件生成均通过 | 无本地项目执行 |

- [RED Source checks](https://github.com/133563825as-ai/DSHA-god/actions/runs/45015086318)
- [精确验证源码 Source checks](https://github.com/133563825as-ai/DSHA-god/actions/runs/45016567533)
- [定向回归、官方规范化及未签名测试包](https://github.com/133563825as-ai/DSHA-god/actions/runs/45016567527)

运行时标识：`2ecad8c58e6e0f29861cd4866538ae71b73c01923836664d157bee961a8a5f23`。三处变更的生产 launcher 摘要与官方生成描述文件相符。第三份测试的规范化字节额外与官方格式报告 SHA256 核对，未在本地运行格式器、生成器、Gradle 或项目测试。

专用回归 run45015086375 曾因依赖下载 HTTP429 未执行测试，canonical 任务曾因缺少固定输入失败；这些不算 RED，也不算通过。RED 来自上表所列 Source checks。最终工作流已修正输入交接，精确 f8d14fec 的相关检查均已执行。

## 测试证明与限制

真实 RootShell 的两个新增测试使用普通 Java 进程替代 su，在 helper 启动边界暂停；只隔离 broker 通知，DeviceGrants、ConfigStore 和 RootShell 为生产实现。它们验证撤权与关开 ABA 的精确拒绝标记，不执行短信查询。

保存挂起测试验证旧缓存仍为 true 时，新短信请求被拒绝，而新普通 Root 请求可以完成内存协议交付、不等待磁盘。该测试在首次授权检查拒绝，不能单凭它声称真实 Android helper 的最终 ACK 端到端复核已测；最后 supplier 接线由专职完整源码审查确认。

失败测试分别覆盖 commit=false 但缓存已 true，以及通知回调抛异常。plain JVM 测试隔离新增进程内失败状态，未放宽生产失败关闭策略。

## 后续 CI 与独立测试组件

固定依赖缓存拆分为同一固定SHA的restore/save action。下载失败时保留partial缓存，完整准备成功才写完整key；准备失败仍使单测步骤跳过、任务失败，不用缓存成功替代测试成功。原Maven URL、锁定版本、大小与SHA256校验均未改变，恢复缓存后仍逐项核验。

在验证分支run45136625563中，最后一个固定jar下载HTTP429，前四个完整文件成功保存。随后run45137718766实际恢复这四个缓存，只下载缺失的第五个文件，完整核验后单测4545项、0失败/0错误/0跳过，host-fast也成功；该运行的格式门禁仍失败，不能把整个运行写成全绿。最终规范化提交另以当前PR检查为准。

新增androidTest runner仅发送无害id，使用目标APK里的ConfigStore/gate与真实RootShellMain，并在匹配暂存回执后改变授权；覆盖关开、短信门禁、无确认和明确旁路对照。旁路对照不是生产代码mutation负控；短信载荷仍为id，不是短信查询端到端验证。只编译独立测试组件，不在生产APK增加测试入口。旧RecoveryInstrumentation仅修复两处已失配的Context参数以恢复测试编译。

新增设备记录继续只留本地，本公开报告不包含设备日志、截图或本轮设备验收细节。

规范化候选988b860416183ed376553538c9186a902455111a的[Source checks / 45138846260](https://github.com/133563825as-ai/DSHA-god/actions/runs/45138846260)五个任务全部成功，JVM4545项、0失败/0错误/0跳过。独立测试组件的[构建运行45138846212](https://github.com/133563825as-ai/DSHA-god/actions/runs/45138846212)中Gradle编译成功，但摘要收集命令因shell关闭glob展开失败；该workflow整体为failure，不能写成全绿。always上传的产物包含已编译测试APK和绑定源码，安装前须重新核对产物摘要、清单、证书和DEX归属。最终产品树移除临时构建workflow。

## 尚未关闭

1. F01：外接目标锁、业务请求撤销和桥/会话生命周期与 Root 最终提交仍缺统一授权边界；晚读一个布尔值不能修复原子性。
2. guest/ADB+su 的跨进程授权绑定、UNKNOWN 跨通道重放，以及审批与完整请求绑定仍属后续专项。
3. 真实 su 等待竞态、管理器撤权后已有 UID0 helper 生命周期、真实磁盘故障及完整设备矩阵未因本次云端通过而获验收。
4. Full 任意 Root shell 不构成短信、密钥等敏感数据隔离。

F02 增量独立静态审查为 APPROVE，仅限修复范围；最终产品提交与完整 Root 状态以 PR 的精确提交及专职审查记录为准。PR 不转 Ready、不合并。临时工作流只用于验证分支，不进入最终产品树；无发布密钥、无正式 Release、无用户数据或设备日志入库。
