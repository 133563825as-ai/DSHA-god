# 并行专项研究（只读，非本轮实施范围）

## 1. 文件访问权限不足直达授权

用户提供截图并要求专项研究，不授权操作手机；本项无代码修改、测试或设备验证。主代理已核读以下入口：

- `StorageAccess.java:19–25`：Android11+检查所有文件访问特殊权限，不是普通runtime permission；当前产品还将Web文档选择器置于此门禁后。
- `ui/WebPreviewActivity.java:1432–1451`：未授权时立即向文件选择callback返回null并只弹原生Toast；截图文字与此分支一致。截图不能证明当前候选已安装或手机实际权限状态。
- `ui/PermissionActions.java:96–109`：已有按本包跳转ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION的入口，失败回系统总名单。
- `ui/DshaDialogBuilder.java:18–29,78–91`：默认业务对话框不会自动代按；safetyConfirmation=true可能受全局允许策略自动执行positive，因此权限导航不能使用该模式。

单一推荐：保留当前授权边界，将Toast-only变为原生“去授权/取消”，准确说明申请的是广泛的所有文件访问；仅用户点CTA才跳系统本包页。返回后实查权限，不信RESULT_OK；取消/未开不循环弹跳，callback仅终结一次。授权成功再由用户点“继续选择文件”进入原有picker，不自动选择/上传/发送。

复用现有PendingUpload/Retained及页面身份ticket，绑定WebView、startup generation、page instance、origin实际端口和请求；导航/换代/退出取消旧请求，旋转只保留同进程合法等待，进程死亡不恢复执行意图。必须补旧回调、重复结果、设置中旋转/返回同步回调、撤销权限、外部origin、OEM系统页回退、全局自动允许不得代按等负控。具体iframe/SPA输入归属仍缺证，不能宣称已解。

最小候选修改组：WebPreviewActivity、PermissionActions必要小重载、BrowserUploadRequestState必要阶段及其测试、i18n文案。移除SAF单次选附件对all-files的前置要求是另一个安全/产品边界决定，不在CTA改动里暗中实施；不顺带改容器挂载/重启或SAF目录桥。

## 2. 存储管理盘点

子代理仅元数据/源码只读检查，未读用户配置或归档正文、未调用设备、未清理。容量是容器目录分配块，非手机总空间；父子目录不能相加，团队active写入使其非原子快照。

审计约2026-11-14 07:32–07:44 UTC：

| 类别 | 观察占用 | 解释 |
|---|---:|---|
| /root/dsha-v03 | 约33.43GiB | 指定项目范围，不含/root/.gradle等 |
| main/.agents/worktrees | 约20.26GiB | 最大项是嵌套工作树，不是main/.git膨胀；每树声明/唯一证据未核，不可据名字清理 |
| inputs | 约3.45GiB | 固定输入/历史下载，不是用户备份；当前完整包旁8片合计约0.668GiB仅待核重复候选 |
| sprout/worktrees | 约3.72GiB | 其中原候选约2.86GiB，新任务树约0.66GiB；均保护/active |
| receipts | 约1.62GiB | 多为历史测试/审计证据，不等同垃圾 |
| /root/新生 | 约180MiB | 交接/准备/审计材料 |

规则核实：AutomaticBackupPruner只清完整且再次验证的automatic副本，保最新3；manual/unknown不自动清。HostOperationArchive将完成操作和portable副本移动到completed，并非删除。StorageMaintenance.inspect当前分类未覆盖所有trial/配置快照/迁移/保留根，统计为逻辑文件字节，不是物理独占或保证已释放。

主要另立风险：prepare-test-runtime每次创建managed随机完整副本且只更新current指针；若干fixture长期保留输出，无统一轮换。App完成历史长期累积、容量页覆盖不全、失败清理吞错可观测性不足。修法应先做可见分类、active引用和已验证替代证据，再讨论显式回收预算；不能按mtime/tmp名字盲删或放松未知原件保护。

已释放空间=0。潜在分片约0.668GiB、旧固定包约1.298GiB等只是候选，需要完整包SHA/分片流SHA/无活跃引用/保留政策及用户单独授权；不把各项简单相加当保证释放量。禁止自动清理原暂停草稿/其证据、旧脏改/暂存删除、冻结历史、用户备份/凭据、未闭trial/未知进程、当前固定资产及本轮active产物。
