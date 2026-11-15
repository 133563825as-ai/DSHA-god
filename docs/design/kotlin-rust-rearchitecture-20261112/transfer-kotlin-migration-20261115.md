# transfer 整域 Kotlin 迁移回执（2026-11-15）

本批次完成本地实现和验证；不是发布、安装或手机验收。分支 `migrate-transfer-kotlin-r2`，基线为开工 fetch 后的 `origin/main`：`b3602555b39056279bc5131e7eaefc1a6be114ae`。已发布版本仍为 build366 / `5ccc8aa3`。提交号以分支 HEAD 与工作树 `.SESSION-DONE` 为准。

## 实现范围与 owner

生产源根仍为 `app/src/main/java`。以下 8 个生产 Java 已删除，由同名 Kotlin 实现整域替换，FQCN 不变：

- [DownloadEngine](../../../app/src/main/java/com/deepseekharness/app/transfer/DownloadEngine.kt)
- [DownloadService](../../../app/src/main/java/com/deepseekharness/app/transfer/DownloadService.kt)
- [DownloadTask](../../../app/src/main/java/com/deepseekharness/app/transfer/DownloadTask.kt)
- [PendingFileAction](../../../app/src/main/java/com/deepseekharness/app/transfer/PendingFileAction.kt)
- [TransferArtifact](../../../app/src/main/java/com/deepseekharness/app/transfer/TransferArtifact.kt)
- [TransferFiles](../../../app/src/main/java/com/deepseekharness/app/transfer/TransferFiles.kt)
- [TransferProtocol](../../../app/src/main/java/com/deepseekharness/app/transfer/TransferProtocol.kt)
- [TransferPublisher](../../../app/src/main/java/com/deepseekharness/app/transfer/TransferPublisher.kt)

仍由原 `DownloadEngine` 单例、`gate`、LinkedHashMap 队列和单线程执行器持有执行权；保留独立的清单释放执行器，不另建状态机。入队代次与 Service `ownedAdmission`、取消控制、持久任务日志、发布前后的锁边界及清理顺序沿用。Service 保留 `START_NOT_STICKY`、原通知动作/编号/频道、WakeLock、Observer 与退出收尾。

没有修改 SAF、M17 APK 更新域、HarnessController、S1b、T2 或 A8-P1 的实现。没有改 Java 调用方来适配迁移。

## Java/Kotlin 兼容结果

完整列表见 [API 对照](transfer-kotlin-api-20261115.md)。对 12 个原具名边界类型、93 个原非 private 非 synthetic 成员（含 3 个 `<clinit>`）做 class 文件和 `javap -p -s` 对照：原成员/descriptor 丢失 0、checked exception 差异 0、原 private 成员放宽 0、Record 超类型/属性丢失 0。原 private 的 Record 与 RuntimeSource canonical 构造另行核对。

直接字段使用原名、原类型及 `@JvmField`，静态常量使用 `const val`；原 `TransferArtifact` 6 个实例字段 + MAX_BYTES、`DownloadTask` 实际 13 个字段、State 3 个字段均保持直接字段访问。没有把 Task/State/Artifact 改为 data class。State 仍是原列表的不可修改视图，不偷偷复制，不允许通过 Java 修改。

`TransferArtifact` 接收可空构造参数，仍按原顺序抛出 `IOException("FILE_…")`；`safeName(null)` 仍为 false。空白判定明确使用 `Character.isWhitespace`，避免 Kotlin `isBlank` 额外拒绝 NBSP/U+2007/U+202F；UTF-8 字节上限不变。Task 的字符串、状态及其它 Java 可空字段不新增入口参数检查。

本批仅接受以下列出的兼容例外，不声称位级完全一致，也不声称无新增公开成员：

1. **10 个包内成员 + 4 个类型**：Kotlin `internal` 在 JVM 上为 public；Files/Publisher 原静态入口以 `@JvmStatic` + 稳定 `@JvmName` 保留原名。包含 Task/State/Result 构造、Files.FS/4 方法、Publisher 的 2 方法，以及 Files/Publisher/Result/Journal 四类型。不宣称异包 Java 负控通过。
2. **编译器基础设施**：12 个边界类型中新增成员总数 **23 = 5 个 Companion 字段 + 18 个方法/初始化入口**。5 字段并非 ACC_SYNTHETIC；其它条目的 synthetic 位逐项列明。它们不是业务字段/方法，不作为稳定 API；不能将 5 字段另加到 23 后误报 28 项。
3. **方法标志**：35 个方法新增 ACC_FINAL，Result.equals/hashCode 2 个方法去掉 ACC_FINAL；这些方法的原 owner 均为 final 类，不改变可继承边界。单例 get 从 ACC_SYNCHRONIZED 转为显式 `synchronized(DownloadEngine.class)`，类锁阻塞与重入的真实回归通过。
4. **Result**：采用 `@JvmRecord`，保持 `java.lang.Record`、canonical 组件名/类型/顺序、nullable 组件、equals/hashCode 与 Java record 风格 toString；data class 的 copy/component 辅助仅作编译器基础设施。

以上例外经主会话明确裁定接受；合入仍需独立审查及用户当次批准。合入批准应显式列出可见性、字段、Record 和编译器辅助例外。

实际 Java 消费者保持不动并参与全生产 javac：网页 [WebFileActions](../../../app/src/main/java/com/deepseekharness/app/ui/WebFileActions.java) 的 artifact 解析/入队/查询、[WebDownloadModel](../../../app/src/main/java/com/deepseekharness/app/ui/WebDownloadModel.java) 的 local 移交、[FileTransfersActivity](../../../app/src/main/java/com/deepseekharness/app/ui/FileTransfersActivity.java) 的字段/状态/恢复/目标选择，以及 [SafBrowseFragment](../../../app/src/main/java/com/deepseekharness/app/ui/SafBrowseFragment.java)、[SafWorkbenchUi](../../../app/src/main/java/com/deepseekharness/app/ui/SafWorkbenchUi.java) 对 safeName/Status 的使用。目录级 grep 在本机出现路径错误，因此没有用搜索命中数声称完成全仓调用点枚举；全生产编译是实际兼容正控。

## 真实编译与测试

K2JVMCompiler 2.2.10 + javac 17，目标字节码 17，生产编译使用 SDK37；主源码覆盖 main/standard/debug 及 AIDL 等价产物。只为编译生成实际 XML/resValue 的 R 符号、当前身份的 BuildConfig 和仓库 UiMessages，不替换任何生产协作者。这不证明 AAPT/资源打包成功，也不等同 Gradle 默认依赖图或严格依赖验证。

| 阶段 | 实际输入/结果 |
|---|---|
| 原 Java 初始基线 | 1060 Java + 26 Kotlin 生产源，原本域及相邻 40 项通过 |
| 迁移首次全生产编译 | 1052 Java + 34 Kotlin 生产源通过，原 Java 调用方零修改 |
| 最终原 Java 对照 | 1060 Java + 26 Kotlin；14 个显式 Java 测试源 + 1 个 Kotlin 测试源（及 NoApkRunner 传递 helper）；88/0 |
| 最终迁移后对照 | 1052 Java + 34 Kotlin；同上测试输入；88/0 |
| 同身份核验 | 15 份显式测试源逐文件 SHA-256 和 14 个测试 FQCN 两轮完全相同；同一组 88 案，不累计成 176 个不同覆盖 |

最终 88 项组成：

| 测试 | 项数 | 实际覆盖 |
|---|---:|---|
| [DownloadEngineBehaviorTest](../../../app/src/test/java/com/deepseekharness/app/transfer/DownloadEngineBehaviorTest.java) | 13 | FIFO/单 drain、恢复实际偏移及手动继续、pause/cancel/resume、发布前取消、提交锁中的迟到取消、Service 代次、显式 saveas、SHA 失败保留、已公开媒体恢复、损坏可见目标不删除、SAF 未知目标不重放、previous/坏日志保留、单例类锁 |
| [DownloadEngineNetworkTest](../../../app/src/test/java/com/deepseekharness/app/transfer/DownloadEngineNetworkTest.java) | 6 | 真实 loopback HTTP、resolve/content/release、鉴权头绑定、206 追加、200 从零替换、半包断连后手动续传、超界流、相同 URL 的页面代次切换 |
| [TransferPublisherBehaviorTest](../../../app/src/test/java/com/deepseekharness/app/transfer/TransferPublisherBehaviorTest.java) | 7 | pending→开流→回读→可见→锁内 complete、同长损坏、pending 日志失败、update=0、complete 日志失败、提交前取消、SAF 文档保留 |
| [DownloadServiceBehaviorTest](../../../app/src/test/java/com/deepseekharness/app/transfer/DownloadServiceBehaviorTest.java) | 5 | null restart/NOT_STICKY/频道、真实活动通知及 immutable pause/cancel 动作、WakeLock 复用/非引用计数/销毁释放、Observer 移除、旧 Service 与 timeout、前台拒绝 |
| [TransferFilesBehaviorTest](../../../app/src/test/java/com/deepseekharness/app/transfer/TransferFilesBehaviorTest.java) | 8 | 真实 writer 续传/从零截断、inode/FIFO/父类型/旧偏移拒绝、NOFOLLOW/独占创建、同步失败仍关 owner |
| [TransferJavaApiTest](../../../app/src/test/java/com/deepseekharness/app/transfer/TransferJavaApiTest.java) | 6 | Java 直接字段/静态/checked exception、null 顺序、Unicode/UTF8、Task 身份与字段、只读列表、Record canonical/value 语义 |
| [TransferKotlinInteropTest](../../../app/src/test/java/com/deepseekharness/app/transfer/TransferKotlinInteropTest.kt) | 3 | Kotlin 字段/包内构造/嵌套 Status、静态 API/null、显式操作一次性消费 |
| 原有本域 3 类 | 21 | PendingFileAction 5、TransferArtifact 5、TransferProtocol 11，身份和源码均保留 |
| 未改相邻 4 类 | 19 | WebFileActionsBinding、TransferListModel、WebTransferPolicy、FileIntegrity |

边界如实区分：Engine/Publisher/Service 始终是真实生产对象。HostFiles shadow 仅隔离 AndroidBackupFileSystem 的平台 I/O，默认 atomic/small/child 算法仍实际执行；DocumentStreams 与 Provider 目标受控；HarnessController 仅提供假 identity/cookie 和初始化；执行器只控制调度，drain 真实运行。HTTP 测试只有 Files.write 平台边界用 Java 静态/Kotlin Companion 双 shadow，真实主机文件记录实际偏移；FilesBehavior 单独直接执行真实 TransferFiles.write，仅替换 Os/PFD 事实并用真实临时文件承载字节。两类证据不冒充 Android 内核 NOFOLLOW/fsync 实测。

本机 Robolectric 使用 SDK34 离线缓存、Conscrypt OFF、`java.io` opens。SDK37 已删除 Robolectric Shadows 重载签名引用的 FingerprintManager；首次新增测试编译因此失败，之后仅在 `/tmp` runner 的测试 classpath 加 SDK34 android-all-instrumented，仓库测试配置/Gradle/robolectric.properties 未改。OpenJDK HTTP 测试 JVM 设 `sun.net.http.allowRestrictedHeaders=true` 允许 Origin，模拟 Android 的请求边界；不写进产品。

主要本地证据（均仓库外）：`/tmp/transfer-java-final-2e0bfb12/`、`/tmp/transfer-kotlin-final-2e0bfb12/` 的 compile/test 日志、参数文件、receipt、原 class；`/tmp/transfer-api-final-2e0bfb12.json` 和 `.javap.json`；`/tmp/transfer-compile-2e0bfb12.py` 与 `/tmp/transfer-abi-check-2e0bfb12.py`。代码审查子代理完整对照 8 Java/8 Kotlin，未确认 P0/P1/P2 回归；它未运行测试，不替代主会话合入审查。

## 格式、边界和受管输入

共享 Java 格式清单经主会话窗口批准，只做 **8 删 7 增**。删除项是本域 8 个旧生产 Java；新增项是上表新增 6 个 Java 测试及 [TransferBehaviorTestSupport](../../../app/src/test/java/com/deepseekharness/app/transfer/TransferBehaviorTestSupport.java)。其它条目的顺序和政策字段保持原字节；Kotlin 全树覆盖 8 个生产 + 1 个新测试。原有 3 个测试不改名、不迁移、不删减。

局部格式结果：9 Kotlin / 7 Java 格式化通过；Java 格式 token 完全保留，Kotlin 格式幂等。最终全树检查：Kotlin **38 文件/10 批/0 need**；Java **1812 文件/58 批/0 need**，清单 missing=0/extra=0；两者均离线使用锁定字节，Java `--jobs 1` 串行。

边界/源码门禁：core 25/0、architecture PASS、源码卫生 PASS、格式清单回归 4/0、runtime descriptor source 4/0；两份本域文档 24 链接/0 错/0 警告。运行时输入契约 18 项中 17 过、1 ERROR，原因是新工作树缺固定资产 `dsh-runtime.bin`，不是本域源码判定差异。Gradle 原基线与最终 `dsha-gradle :app:compileStandardDebugKotlin` 都在 prepareBackupAssets 因同一固定资产缺失而 exit1，均未到 Kotlin 编译/测试任务。最终 Gradle 日志为 `/tmp/transfer-gradle-kotlin-final-2e0bfb12.log`；不把未执行项写成通过，也不使用普通 Gradle、任务排除或改证明绕过。

当前 manifest 的 launcherTrees/launcherSources 均未纳入 transfer 域，8 个源扩展名替换不改变当前受管 launcher 集合；本域没有受管 JSON/配方/固定输入/依赖锁/proof/最终 descriptor 变更。给主会话的源码输入清单是精确 Java 8 删/Kotlin 8 增；是否扩大受管范围及最终 descriptor 重算仍由主会话串行负责。

输入前缀为 `app/src/main/java/com/deepseekharness/app/transfer/`，每行删除原 `.java`、新增同名 `.kt`；下列为真实源码字节 SHA-256，不能误当成 runtimeId：

| 类型 | 原 Java SHA-256 | 新 Kotlin SHA-256 |
|---|---|---|
| `DownloadEngine` | `a6ff0062dfff636e68b5c7512f4bab31adb9a0cdde9492f4928cba47fd57dead` | `c17c86659b94c41e367be309f2592771afc634738f173aa4eb58d6e1d12e7057` |
| `DownloadService` | `04150f93dcd5e7f5980d16d5ef03c9de59de722115a42f2b46c226a5b89f5071` | `50f0864a32df36c80c1fd75d271b0b8cfdaf68f5dd4994a99a0c31cd46d58bc7` |
| `DownloadTask` | `77cc2b4593eb746da86225d79beba9bb9063d09619aa57716b972f01c614cc99` | `245a665e83640931c093e43e932d938ebce96d71b173473f4ec6a770def569cd` |
| `PendingFileAction` | `e55ef4e78549b68104252e29ea40b9e39c33f6dcf14f8557eaf557918dfdd659` | `b36854e30c248f5ae410445000c4ebc707d3dc350a6cfa56eadd29d9be985684` |
| `TransferArtifact` | `cee9975f5cc8473bc487e5a92f671ffca875a68bc027aea01d7a62ed284a93bd` | `6664f25e36d91af59469830d941b5bcfce2c81e2012390b829894b5cca9cc6a0` |
| `TransferFiles` | `27256bb05106ea8f8f7d63f6c67435fcaf86149dfc5d2ee49efc54bcca660e27` | `841670e77574693652c45b9daf0df3c3a763c4afa7345011a7772f0b1e803c51` |
| `TransferProtocol` | `bb2b59e991a04620930cc6b7552f995f94e17b48313cfd5eb3296825a7ef4a5b` | `6b75ea2dfe65d295bcbbe1d98c33d6748af9fd9348cd5a3e40d952e18629a285` |
| `TransferPublisher` | `0575d09576cbe3dbf399140369408101251c426b02a68b6b994706988bf27c13` | `3022e487d2c313a790dd4a8fbec392190a95fe3eff8d2a8a39defabbbf1d0f2d` |

## 工作树事故与恢复

09:18 UTC 左右，原嵌套树的 `.git` 与多数文件消失，只剩 2 个 Kotlin 和 1 个测试。原因按 UNKNOWN 记录，没有证据归因于具体会话。主会话保全三文件，`git worktree repair` 实际 exit1；按其裁定从同一 b3602555 新建当前直接树，旧目录及注册未删、未 prune、未重放未知删除命令。

恢复来源 `/tmp/transfer-partial-2e0bfb12/` 的 SHA-256：

| 文件 | SHA-256 |
|---|---|
| DownloadEngine Kotlin | `85250b52796a6fa8e91a810f0c4c040974ce328a9b2ef1c39da4ab6b50112d03` |
| DownloadService Kotlin | `b4e3a9521d76ba8bc745b67b07e2268a69fa8eefe445dfda5423cd2c84b3a2b8` |
| TransferPublisherBehaviorTest | `b4d2c6f6cc701940f454677456145f81afd5d2044db4b538ccb6010aeb50efa4` |

其余内容由当前会话/子代理的原工具记录重建；原 8 Java 精确源码及 40 基线/ABI 在仓库外保全。r2 已重新编译原 Java 与迁移 Kotlin 并完成同输入 88 项对照，不拿旧树结果代替恢复后的验证。

## 未验范围、合入与回退

未运行本批全量 app 单测、默认 Gradle/Robolectric 配置、Lint、APK/Dex/签名/安装、Android 系统真实 Service timeout/进程回收/通知/WakeLock、厂商 Provider、真实网络阻塞取消/网络超时、gallery 解码/share 完整路径、5GB 实际吞吐及真实 Android 描述符/持久同步故障矩阵。local Blob 移交/零字节与 broker release 错误重试等分支仅做源码对照或既有纯策略验证，不扩写为新真机证据。

不自行 push、合并、发布、安装或操作手机。主会话合入前需基于当时 main 重核/必要时 rebase，独立逐行审查并请用户批准上述明确兼容例外；受管输入和最终 descriptor 由主会话处理。

回退为 revert 本批提交，恢复 8 Java 并删除同名 Kotlin、恢复本域格式清单条目；不改用户数据/偏好键/协议/包名/数据格式。若主会话之后配套更新受管输入，回退时需串行恢复对应输入并重算 descriptor。
