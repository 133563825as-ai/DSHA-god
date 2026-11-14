# 本机新建 Gradle 诊断守卫：本轮边界

## 实际实现

这是Lead明确批准新建的**诊断入口**，不是已有dsha-gradle恢复，不因名称取得Gradle执行豁免。本轮没有Gradle/JVM子进程执行代码，不改系统、SDK、AGENTS、依赖锁、签名或设备。

- `python3 tools/dsha-gradle --status`：只读显示固定锁存在性；不创建锁、不清stale。
- `python3 tools/dsha-gradle --preflight`：取得固定全局锁，检查环境注入、隐式init、资源、既有精确工具身份，写结构化拒绝回执。静态条件即便全满足，仍返回`DIAGNOSTIC_JVM_PROBES_NOT_ADMITTED`。没有“通过即运行”分支。
- **所有其他参数/项目任务一律`PROJECT_EXECUTION_NOT_ADMITTED`/exit2**，包括unit、compile、resources、lint及assemble/package/publish/install/sign/clean/stop/daemon/background。不接受任意脚本/init/JVM/系统/项目参数。不调用cached Gradle、gradlew或build.sh。

## 锁与归属

固定`/tmp/dsha-gradle.guard`以mkdir原子创建，0700；不按工作树分锁。已有目录、symlink、缺owner或stale均拒绝，不自动清理也不杀持有者。创建后记录目录dev/ino及本次owner：随机nonce、pid、/proc/self/stat出生字段、bootid；owner以O_EXCL/O_NOFOLLOW写入并fsync。发布不完整保留现场。

释放前验证当前目录dev/ino与非链接类型、owner打开FD的普通文件身份/完整字节、当前进程pid/birth/boot，以及删除前owner inode和目录身份；只unlink本轮owner再rmdir。异常/陌生文件保留，不rmtree/不盲删计算目录。此处是合作进程的串行护栏，不是防同UID恶意竞态的内核沙箱。

## 静态拒绝条件

- 固定共享`/root/.gradle`、`/root/android-sdk`、JDK17目录；不复制cache，不下载。
- 读取当前Gradle wrapper SHA与工具契约。必须唯一既有Gradle9.3.1目录、无symlink、发行zip存在且符合当前wrapper锁SHA；缺zip不拿zip.ok/launcher版本替代发行字节证据。记录launcher/JDK/platform SHA仅诊断，不能据此声称发行解包树已全验。
- 拒绝JAVA_TOOL_OPTIONS、_JAVA_OPTIONS、JDK_JAVA_OPTIONS、GRADLE_OPTS、JAVA_OPTS、ORG_GRADLE_PROJECT_*等环境注入；只报告变量名，不记录值。此诊断阶段也不开放JAVA_HOME/SDK/cache覆盖。
- 拒绝共享或用户Gradle home的init.gradle/init.gradle.kts及非空/链接init.d，拒绝发行init.d除readme.txt外成员；不会执行隐式脚本。因为不运行Gradle，受信repo的构建脚本也没有本轮执行机会。
- 2048MiB为保守静态预留：launcher384 + single-use daemon768 + Test512 + native/编译器384；不是任意fork安全保证。核MemAvailable，且固定cgroup v2根memory.max/current可用时取更低余量；当前实现未解析当前进程实际cgroup层级，可能漏更深限制，故只是候选余量，不是完整资源准入。资源齐备不等于项目任务准入。JDK记录本次SHA没有外部预先pin，也只属候选诊断信息。

## 尚未兑现、禁止声称

本轮**未实现/未执行**真实fcntl/flock/FileChannel双进程锁探针、逐JVM oom900写后读、固定Gradle/Test heap覆盖、受管init模板、taskGraph派生任务禁操作检查。因此不运行任何Gradle项目任务；不是一个可构建的完整wrapper。后续须独立授权和逐行审查后才能实现：A持锁→B返回null→A释放→B成功，EPERM/timeout拒；每个launcher/single-use daemon/Test进程实证oom900；固定资源覆盖和任务图依赖/finalizer禁操作拒绝。不能凭task白名单或改工具名绕过这些前提。

## 当前工具链事实

指定`/root/dsh-bin`、`/usr/local/bin`、项目tools未找到现成dsha-gradle。真实SDK是`/root/android-sdk`，不是`/root/android-sdk37`；SDK37 metadata与43MB platform jar存在。Gradle9.3.1解包目录存在，但仅zip.ok/zip.lck、未发现发行zip。SDK36 aidl是已有qemu wrapper，aapt2为x86；本任务不修改/执行这些工具。`/root/.gradle/android/FakeDependency.jar`不是framework。非instrumented android-all与Robolectric sdk34 instrumented i7不可替代。

## 证据

纯Python测试只用synthetic临时目录与mock静态条件，不运行JVM/Gradle，不把mock称真实probe通过。日志、输入摘要及拒绝回执放`app/build/rebirth-rc1-closure/gradle-guard/run-*/`。真实本机preflight仅在独审准入后由Lead执行；其合理结果可以是明确拒绝，不能为求绿改锁/删断言/伪造SDK。已有64/22宿主定向通过不因此扩张为完整Android或Gradle通过。
