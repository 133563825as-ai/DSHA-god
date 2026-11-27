# RC1 最小修复：本轮声明与跨层约定

## 入口与授权

- 固定历史入口：/root/新生/DSHA新生计划-新对话交接-20261114.md（只引用，不覆盖）。
- 当前会话：session-2edc53a4-3bcc-4f5c-90bd-a734913bc988。
- 用户此前明确指示：“不需要保持暂停，该往前赶一赶，而且智能体团队呢，可以找队友和你一起”。据此前精确范围，恢复本轮RC1生产修改及隔离定向测试；不自动提交、合并、推送、触发CI、APK构建、签名发布、安装或操作手机。
- 后续用户“继续下一步”并明确“可以，全权交给你”，Lead承担技术决策和实施推进。本阶段独立扩到web-search整模块canonical窄修、宿主浏览器配置和诊断型Gradle准入；不夹带权限UI/未知文件清理，不借自主推进推定提交发布设备授权。
- 工作树：/root/dsha-v03/sprout/worktrees/rebirth-rc1-closure；fix/rebirth-rc1-closure；基线8b42e20bea9a0049761fad7d0cd9948e48838e4a。已只读验证clean后新建，不从暂停草稿复制。
- 原候选两份Python草稿SHA与旧声明保护不变。文档树、reference-b359的6335暂存删除、/root/dsha-v03/docs脏改及/root/汇总冻结历史不处理。

## 唯一范围

复用build364 RC1关联窄片和故障测试，逐文件适配；保持DSH0.2.2-rc.1、候选发行隔离及已修overlay。只修历史/当前结果、缓存及startup绑定、未知不重放、可信无secret投影、账本分节集合和清单一致性。新桥、authority框架、自动补偿/禁用、秘密转移、恢复旧unknown、跨进程exactly-once、DSH升级、x64、模块平台、Kotlin/Rust及其他三个runtime问题不包含。

“历史只读”指CJS history-only配置update、设置账本写和业务rename均零；既有Python current启动票据降权history-only及有证据的reuse优化维护单独计数，不宣称整启动零文件写。不得借维护元数据重建丢失权威、生成新代或授予迁移许可。

## 最小跨层约定（实现和测试必须一致）

沿参考wire resultVersion=3，不采用此前259例整个大profile；durable业务记录保留version=2兼容，不批量改写旧记录。

必检字段：resultVersion、phase、status、startupId、generation（DSH_MISSING例外）、verificationScope、contentCopyStatus、sourceObjectIdentity、settingsImportStatus、executionState；warnings有界。sourceObjectIdentity仅unknown，不证明绝对原件身份。

正向消费组合：

| phase/status | scope/copy | settings | execution | 用途 |
|---|---|---|---|---|
| prepare/prepared | current/verified | pending或not-required（历史设置仅在真实同代证据存在时verified-historical） | COMPLETED | prepare启动门禁，不是CJS无条件写许可 |
| prepare或finalize/historical-complete | historical/verified | verified-historical或not-required | NOT_SENT | 无迁移写入普通启动/有限历史展示 |
| finalize/committed | current/verified | verified-current、verified-historical或not-required | COMPLETED | finalize常规成功；不证明外部effects |
| prepare/skipped + reason DSH_MISSING | none/unknown | unknown | NOT_SENT | 无generation专用分支 |

不完整、未知枚举、错phase、错startup/generation及互斥组合一律不授成功。失败/unknown允许诊断，但不启动或静默掩盖。Startup只消费prepare，WebReady只消费finalize；Guest记录prepare代次并约束finalize，不能由结果自述generation自证绑定。受管Java finalize必须传内部参数--expected-generation，Python读current后在任何业务/receipt/reuse写前核对，不等立即unknown；输出后同代检查不能替代执行前保护。该参数仅现有入口防错代，不增公开迁移能力。历史cache命中prepare仍必须实际执行Python有界历史prepare，把durable current降为本startup/history-only；成功回执后重新探测验证同代/同输入/同startup/同mode，才能保留本地finalize票据，禁止沿用派发前旧write-allowed元数据。缓存失效unknown不补派。重复finalize只承诺进程内有界顺序抑制，不称并发/跨进程幂等。verify-existing不接生产新入口；如为参考内部只读函数可保留并测试，不新增公开命令/UI。

## E：期望集合证据

有settings来源时：ledger.expectedSections须具备version=2、generation/sourceSha256与真实来源绑定（若含inputDigest还须一致），或已严格绑定version/generation/settingsSha256/inputDigest/inputs的独立checkpoint.settingsSections为期望集合；两者都有必须一致。缺失、错类型、重复、矛盾均unknown。绝不从ledger.sections现有键生成可信集合。空集合仅在独立源解析或同输入绑定的可信集合明确为空时合法；若独立真实源集合非空却声明[]仍unknown，不把缺证当空。

CJS能从已校验快照解析得到独立源集合；历史模式直接比ledger实际节状态与源全集，若声明expectedSections也必须一致，不写回补字段。Python/Java/Catalogue按可得权威保守判定；旧缺集合只unknown，不能强迫解析全部sessions或新增框架。无settings独立not-required，坏/缺sha不能伪装无settings。清单legacy状态只作历史事实，不成为健康/恢复/删除许可。

## Schema和派发

锁定SettingsForms rc.1 describe输出{uid,refs}，schema UID非持久身份；不得hash原始UID作跨describe稳定身份。纯数据解释受信图，不new Schema执行callback。首期只支持能证明完整覆盖的无secret静态类型，未知引用/类型/字段、secret、尚未支持动态或分支、别名冲突均写前拒绝。普通maxTokens保留；不按名字脱敏；数组索引不移位。整份来源的节名/别名冲突、schema图完整性和secret/不支持类型均须在任何首次update前预检；首期任一unsafe节则整轮不派发，不能先写合法A再发现不安全B。比较完整候选与实际读回，不用contains子集冒充完整业务完成。首次update调用后的任何异常都unknown/noReplay；值相等及错误文本不证明未派发。不得因新增校验扩展原有业务目标。

## 团队写域

- python-migration：rc1-migration.py、test-rc1-migration.py。
- java-migration：RC1 Cache/Reuse/Result/GuestPluginScripts、StartupPipeline/WebReadyTasks/WebLifecycleController最小接线、RetainedCatalogue及对应Java测试。
- settings-migration：rc1-settings-migration.cjs、test-rc1-settings-migration.mjs。
- Lead：本轮记录、受管描述符/patch接线、fixture集成与串行门禁协调；不与队友同时写其文件。
- 非实现者在实现冻结后逐行审全部diff，不以数量或CI替代审核。

## 验证底线

真实锁定SettingsForms不等于真实ConfigEditor。分别记录mock/editor真实链、容器/Android证据；故障注入只在隔离synthetic临时目录。原暂停两草稿不执行，真实用户profile和凭据不读写。定向测试不能替代完整Ubuntu门禁或APK/设备通过。四个既有runtime失败独立对账，不能改expected/skip求绿。
