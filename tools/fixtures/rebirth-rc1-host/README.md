# RC1 窄宿主验证边界

入口：`python3 tools/test-rebirth-rc1-host.py --runtime <现有已证明的 raw runtime>`。
默认完整日志：`app/build/rebirth-rc1-closure/host-verification/run-*/`。不下载、安装、调用Gradle、使用Android SDK、生成APK或操作设备。运行JVM前须与Lead串行协调。

默认全跑；`--compile-only`仅写COMPILE_ONLY_PASS，`--junit-only`复编译后跑原五类及新增两类消费者JUnit、仅写JUNIT_ONLY_PASS，不等于该run验证完整链。`--consumers-only`只执行当前StartupPipelineTest、WebReadyTasksTest整类（17+5例），写CONSUMERS_ONLY_PASS；不需要raw runtime，不执行Catalogue/跨层。正常全链仅在两阶段都通过时写PASS；失败写FAILED，timeout保留partial日志。JUnit外层900秒适应当前257/1000目录真实FS测试，其本身故障deadline/断言不变；cross-layer外层180秒。

离线依赖使用`--gradle-home`或`GRADLE_USER_HOME`（再默认用户.gradle），不靠隔离子进程HOME，也不递归搜索工作树/旧产物。业务三jar按本线backup lock精确版本和SHA校验。

## 真实被测内容

- 显式清单编译当前树的 GuestPluginScripts、Rc1MigrationCache、Rc1MigrationResult、Rc1MigrationReuse、RetainedCatalogue 与其必要纯Java协作者；执行上述五个当前JUnit类，不生成修改版测试。
- HostRc1Chain从新的Java Guest实例真实调度当前树Python prepare/finalize；固定guest命令仅由Runner边界拆成受控argv，根/state均为专属mkdtemp目录，不执行原shell字符串。
- hook使用test-runtime-fixture验证现有raw runtime身份/内容，精确匹配当前生产patch的before/after。只将固定guest CJS URL映射到本树当前CJS的wrapper，并显式传隔离stateRoot。真实SettingsForms.describe/update、ConfigEditor、Loader、磁盘持久化及独立Node进程重开均执行；不mock编辑器或迁移核心。
- 首次prepare和finalize精确断wire phase/status/startup/generation/scope/copy/settings/execution；缓存重启必须真实Python降durable current为本startup/history-only，再hook update=0、Java本地finalize，ledger bytes和mtime均不变。没有声称整个启动/整个home零文件写。
- 旧Java prepare票据在另一新代prepare后finalize，必须携带expected-generation，由真实Python返回明确EXPECTED_GENERATION_MISMATCH/exit1；全部state常规文件bytes和mtime保持不变；同实例重复finalize不再次派发。

## 不被测adapter

主Python脚本在每次run目录生成并保留adapter源码及SHA，不覆盖生产文件：

- android.content.Context/ApplicationInfo、RootfsInstaller、RuntimeTools、AndroidBackupFileSystem：只解开Android构造器编译依赖；任何构造/安装/准备入口若误执行立即AssertionError。AndroidBackupFileSystem的未用方法来自当前JvmBackupFileSystem，但构造立即拒绝。
- android.util.Log和UiText：仅宿主日志/中文格式化（choose选中文）。消费者扩展编译当前完整Compat真类，不把未知launcher拒绝信号策略替换为固定false；android.system.Os只提供NOFOLLOW链接类型事实，其信号/readlink/chmod等入口一律拒执行。Build.SDK_INT=34仅选择JDK兼容分支，不是Android SDK或Robolectric。SystemClock映射JDK单调时钟。
- NativeDataLocations：仅抽取当前真实静态SESSION_NAMES/PLUGIN_NAMES/classify/id/hash，不编译其Android定位/ConfigStore图；这些不是本轮RC1迁移算法。
- EnvironmentRebuildTransaction/ManagedRuntimeTransaction/PluginInstallJournals/PluginJournalQuarantine：只提供既有目录名常量，不声称验证其事务行为。
- ColdInstallTransaction.Candidate、NativeConfigurationReset和RestoreStaging：编译期非被测边界；未命中的拥有者判据固定false，清理入口立即拒绝。实际HostOperationArchive及Catalogue所用维护/归档方法仍编译执行当前真类。
- HostRc1Chain.HostFs继承当前JvmBackupFileSystem：保留真实NOFOLLOW读，stat仅将真实unix dev/ino编码为Android FS约定的`device:inode` key；对state小JSON读取仅把本fixture的绝对home字符串投影为Java固定guest `/root/.dsh`，磁盘记录不改，Python/CJS仍读取实际临时路径。stat拒绝专属files目录外目标，不读写实际`/root/.dsh`。

## 消费者 source-slice 边界

消费者扩展保留当前完整StartupPipeline、WebReadyTasks、HarnessSessionState、StartupEnvironmentActions、WebProcessSession、生命周期/保护helper及原测试源码；原17+5测试全类执行，包括3个actualPriorAdapter，不做方法筛选，不改断言。

`consumer_adapters.py`从当前2148行WebLifecycleController按唯一固定起止边界，逐字提取**完整嵌套WebRun**（含原finalizeMigration），只加编译外壳。inputs.json记录完整Controller SHA、slice SHA、起止字符串；未重新实现被测方法。它不验证Controller其余启动/停止编排，也不等于原WebLifecycleStartProtectionTest的Robolectric整类执行。ProotBootstrap、ConfigStore、WebProcessManager、StartupDiagnostics、owner、存储/维护入口只提供编译期拒执行边界；原测试自身override的confirmStopped才是synthetic过程事实。StoredWebSession仅保留Record.proof类型，其读取入口拒执行。没有读取真实凭据或启动真实guest。

2026-11-14离线调查：真实SDK37存在于`/root/android-sdk/platforms/android-37.0/`，metadata API37且android.jar约43MB；此前仅调查/usr/lib的“只有23”不能作为全机结论。Robolectric4.15.1库缓存存在，但已调查缓存没有sdk34 android-all-instrumented；dsha-gradle不在PATH，没有运行Gradle，不以SDK23或这里的adapter冒充真实Robolectric。完整Controller/Robolectric和全宿主门禁仍分别缺证。

## 证据限制

这是JDK17+缓存JUnit/Gson/SnakeYAML/BouncyCastle的定向宿主验证，不是Android API、proot运行、完整源码/Gradle、启动管线全部消费者、Robolectric、Ubuntu完整门禁、APK或设备验收。JUnit类自身的mock/stub不被包装成真实跨层证据。缺依赖/缺既有raw fixture应失败，不伪造SDK或跳过求绿。输入清单记录实际源码/adapter/classpath jar SHA以及raw路径；各阶段日志保留命令、输出和退出码。临时目录保留以供复核，不触碰旧树、凭据或真实profile。
