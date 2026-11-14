# 新生计划阶段进展（2026-11-14）

> 本页是追加时点快照，不覆盖旧台账。仅同步资料，不代表生产代码已提交、合并、构建或发布。

## 版本与证据对象

- 本文档分支原HEAD：`55623ba5eabdf6a778221b6777cdf651609629eb`。
- 实施基线：`8b42e20bea9a0049761fad7d0cd9948e48838e4a`；修复位于独立任务分支`fix/rebirth-rc1-closure`的未提交工作区，不能把基线SHA当作修复后的源码身份。
- 内置DSH保持`0.2.2-rc.1`。未整版同步参考主线，未引入官方rc.3、模块平台、x64、Kotlin/Rust。
- 历史[验证台账](../baseline-and-validation.md)的125/129属于旧候选CI；本次本地定向结果不改写那个运行，也不能与不同轮次拼成完整全绿。
- 本线仍无Rebirth APK、签名或设备验收。原两份暂停Python草稿保留，未作为实施源码或构建输入。

## 本阶段实际完成

1. **RC1最小闭环**：区分历史完成与当前副本核验；对象身份不足固定unknown；严格消费phase/startup/generation/execution组合；未知派发不重放。
2. **设置安全投影**：按锁定schema的uid/refs纯数据图解释受支持无secret子集，不按字段名猜秘密；不支持/秘密/别名冲突写前拒绝，不新增秘密转移框架。
3. **账本集合一致性**：Python、Java缓存、CJS及保留清单保守核对独立期望集合，不从现有ledger键自证完整。
4. **历史缓存降权**：缓存命中prepare也实际执行Python，将durable current降为本startup的history-only后重新核对；否则旧write-allowed可能导致历史账本重写。该问题由独审发现并修复。
5. **web-search整模块语义**：共享局部锚点并不代表完整当前模块。recipe显式启用完整输出SHA，Java沿已有canonical路径最多恢复一次，错误canonical拒绝；Python builder无恢复能力时直接fail-closed。保留原完整模块断言，没有改expected求绿。
6. **宿主测试配置**：浏览器engine/executable参数分离，两个目标fixture接入统一host helper；未声称所有浏览器入口已统一。新增Gradle工具仅诊断，所有项目任务硬拒绝，不是可运行构建工具。

## 验证结果分层

| 范围 | 实际结果 | 不能推导 |
|---|---|---|
| RC1 Python | 47例通过 | Android/完整迁移准入 |
| CJS设置链 | 63例通过，含真实SettingsForms、ConfigEditor、磁盘重开、生产hook临时路径适配 | 任意secret/动态schema迁移获准 |
| Java窄宿主五类 | 64例，0失败/ignored | 完整Android/Gradle |
| Startup/WebReady消费者 | 17+5=22例通过；完整WebRun仅逐字source-slice | 完整Controller/Robolectric |
| web-search定向 | Python4、两Java类9例；旧模块canonical恢复恰1次并输出完整当前SHA | APK/设备执行 |
| 最终统一runtime选集 | RC1 Python、CJS、web-search 3/3通过，raw首尾完整字节复核通过 | 完整runtime130 |
| 最终完整fast | **102项：95通过、7失败，整体失败** | 首包全绿 |

64与22来自分次执行，不合称一次86项完整门禁。上一轮跨层所在run整体因JUnit180秒超时保留FAILED；之后单独JUnit在900秒外层预算内64通过，没有修改测试内部deadline或断言。

### fast剩余七项

- 三项工具链setup缺口：vscreen-help、vscreen-protocol、bundled-dsh-version；未执行子测试，不写成业务断言失败。
- CAS materialize与scan-review两项失败。合成诊断观察到只读打开期间ctime变化触发守卫；未削弱稳定性检查，底层机制未定。
- CI handoff测试setUp复制Git对象出现EINVAL，未到主体；未证明与CAS同根。
- plugin-downloads测试unknown-counter缺失，保留为新观察；不能未经证据归为环境或RC1回归。

### 其他未闭合准入

- mobile/recovery浏览器GPU子进程退出码5、测试退出码1，在业务断言前中止；recovery后端已READY。本轮不能判旧滚动/页面问题已修。
- 唯一disable-gpu smoke仍失败，未继续叠加兼容flag或关闭Web安全求绿。
- Gradle诊断真实因cgroup内存文件EACCES拒绝，没有启动JVM/Gradle。完整Gradle、Robolectric、Lint及完整runtime准入仍缺证。
- 本机仅有Android内核上的Ubuntu用户态，未发现已配置可用独立Linux执行器。用户态QEMU不算独立宿主，没有连接远端或创建资源。

## 来源与下一步

最终受管身份及源码包见[交接身份](handoff-identities.md)。下一步采用[独立Linux验证计划](linux-validation.md)，不在当前容器反复盲试相同失败，不跳项求绿。

参考版本按固定时点区分：build363仅历史核验；build364 `1545e10467c8ca9e9862e5facad3c4a070227e79`已有真实RC1修复，不能再用旧363说“未实现”。采用的是关联窄片与测试方法，不是整批cherry-pick或继承参考发布/设备通过。

## 两项只读研究（未实施）

- **存储管理**：正式资产、用户备份、已验证副本、未知保留记录及临时产物需要分开。发现开发测试副本与完成历史增长、容量页分类覆盖不全等风险；未清理文件，已释放空间为0。未访问手机实际存储。
- **文件权限提示**：原生入口目前取消文件回调后仅弹Toast；推荐“去授权/取消”直达本包系统页，返回实查，再由用户显式继续选择文件。不能让全局自动允许代按。移除SAF附件对all-files前置要求是另一个权限边界决定，不夹带实施。未修改UI或操作手机。

本次仅提交本目录资料；不合并主线，不提交生产修复、私有日志、归档、依赖树或二进制。资料同步不代表获得签名、发布、安装或设备操作授权。
