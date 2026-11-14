# A7 S4 软件检查点：F07 有界只读清单

日期：2026-11-14。状态：**软件实施已形成检查点，待独立全差异审查；整轮未完成**。
前置已满足：Lead 对 S1 `c92f8495`、S2 `39ddef7a`、S3 `b6dcdc4d` 均 APPROVE；[管理器表](a7-s4-manager-readonly-table-20261114.md)及固定 FD probe 已附条件批准。主线推进未在本工作树自动 rebase，实施基线仍为903cbbfc。

本片新增纯策略/解析 `RootModulePolicy`、`RootModuleInventory`，固定 fd 适配器 `RootModuleFiles`、版本探测子入口 `RootModuleProbeMain` 与协调器 `RootModuleReadCore`，以及原生只读页 `RootModulesFragment`。设备页仅导航到该页，渲染不触发probe；用户显式读取时才经 RootShell 固定 `root-module-list` operation 进入当前安装 APK 的 UID0 helper。

边界保持：

- 管理器/参数只来自已批枚举；KSU32702/固定name/uapi6、APatch11282、Magisk精确三项指纹。未知版本或歧义拒绝，不搜索PATH、不转Shizuku/ADB。
- probe先核真实UID0和当前安装身份/source==实际classpath；候选逐级fd O_PATH/NOFOLLOW、fstat dev/inode/mode/uid等，最终exec `/proc/self/fd/<fd>`原对象，不stat后重新按候选path执行。输出≤4KiB、总版本/读取预算10秒；关闭未确认保持UNKNOWN。
- reader只有固定 `/data/adb/modules`；流式枚举至第257项探限，prop≤16KiB/总≤256KiB/最多256模块，常规单链接文件及只读marker，拒绝链接/FIFO/特殊文件/换链/权限失败；不递归、不读脚本、不调用module list/debug或namespace切换。
- 解析只保留id/name/version/versionCode与标记/mtime，严格UTF8/长度/重复键/ID匹配。失败或partial不折成完整空清单，完整且零项才显示没有模块。
- 页面没有安装/启停/更新/卸载等写按钮、助手端点或自动轮询。暂停清除清单、围栏旧页面与中断本次worker；恢复不重放旧读取。授权变化丢弃旧结果，模块正文只在原生页，不进入公开诊断。

实际软件验证：

| 证据 | 当前结果 |
|---|---|
| 策略/解析/fd算法/真实协调器 | 4类 **66项，0失败/错误/skip**；真实当前生产类+固定host JSON/JUnit，OS端口仅内存事实，无设备调用 |
| 原生页状态夹具 | 实际RootModulesFragment **6项通过**；验证complete/partial/未知与原始字段、暂停清理，不代替真实布局/权限弹窗 |
| 全生产类型 | 当前main+standard及真实生成入口 **1026 Java源**，实际Gradle依赖/SDK/R，javac exit0 |
| 双屏回归 | actual physical flow **26项**、Manager/认证Core/anchor链 **15项**继续通过 |
| 格式/边界 | 十一条新增登记已获批，仅增加6生产+5测试；全树Java固定格式检查exit0，最终1797文件/0need；边界25项通过 |
| 文案 | S5文案工作区已追加14条新ID并复用2条旧文案，十语齐全；子任务报告catalog11项、newFindings0、source hygiene PASS，详见S5接续记录，待父会话最终重核与独立提交 |

联调中确实发现并修正了协调器Result契约错误：完整清单的reason必须为null，partial的LIST_PARTIAL不能充当具体失败原因；JSON输出需要null安全。17项实际协调器夹具以完整空/普通项/partial正确期望验证，未把错误期望放宽为UNKNOWN。

本机完整Gradle仍受x86 AAPT2 bad machine阻断，未进行资源链接/完整AndroidUnit/lint/APK/ELF/JNI及真机。未运行真实Android Os/fdexec/su管理器、未安装或操作手机；固定版本源码证据不是二进制或设备认证。UI中英/日夜/短屏/1.3×及同线一次维护页、I1–I10待用户另行授权。

检查点日志：`app/build/a7-s4-current-tests/{compile,junit}.log`（66）；`app/build/a7-screen-fixtures/RootModulesFragmentTest.log`（6）及physical/anchor日志（26/15）；`/tmp/a7-s4-source-fixtures-20261114.log`（1026源）；`/tmp/a7-s4-java-format-20261114.log`、`/tmp/a7-s4-boundaries-20261114.log`。这些机器文件不提交。

后续必须完成：S4独立审查与意见闭环；S5十语/状态/集成回归、前几片非阻断P2出口批处理与整轮回执。main最终rebase到当时main、纳管所有新生产类及ShizukuShell、统一重算descriptor/runtimeId并跑CI，最后用户批准合入。分支未改launcherSources/descriptor，未push/merge/tag。
