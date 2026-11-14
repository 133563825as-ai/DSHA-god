# 冻结交接身份（2026-11-14）

本页仅公开必要身份摘要，不提供私有本机路径、执行凭据或完整原始日志。源码增量包未上传到此分支；资料提交不等于代码交付提交。

## 源码增量包v3

| 项目 | 身份 |
|---|---|
| 精确基线 | `8b42e20bea9a0049761fad7d0cd9948e48838e4a` |
| 逻辑任务分支 | `fix/rebirth-rc1-closure`，未提交 |
| 包名 | `rebirth-linux-validation.tar.gz`，revision3 |
| 包字节数 | `123177` |
| 包SHA256 | `e0f819a31285c3e40f1ef2b130b673aded9708245d92466debea453e6f40e431` |
| 范围 | 34项tracked修改＋17项新增的精确增量 |
| 成员 | changes.patch、new-files.tar.gz、manifest.json、LINUX-VALIDATION.md、SHA256SUMS |
| 排除 | Git对象、完整依赖、APK、固定二进制、用户数据、凭据、旧暂停草稿 |

Lead已在独立临时稀疏基线执行git apply检查/应用、排他落新增普通文件、逐文件结果SHA核对，通过。独审只读核了双层成员、路径/类型/重复/链接、基线和结果SHA/大小/mode、patch与冻结差异以及SHA256SUMS，APPROVE；独审未执行包代码，不把包回放当作独立Linux业务测试。

包是冻结快照。之后维护日志追加不回写包，也不沿用旧SHA伪称包已更新。接收者必须有可信本地精确基线，不在main/正式或暂停工作树应用；任何冲突只报告，不reset/clean覆盖。

## 受管运行时与fixture

- runtimeId：`97675c75a1577fdbacf5ef27e596788d6e393270991979d474bae6808598957a`。
- 内置DSH：`0.2.2-rc.1`，数据/权限/bridge兼容声明未借修复扩大。
- arm64 runtime归档：`131056363`字节，SHA256 `c1bd68330a10776f304a92a19d8f82c184c1abf523275a9e28dbb2c2e764e4fe`。
- 新归档恰与此前归档字节相同，但recipe与builder输入已有两项变化，重新构建及来源回执不可省略。91项规范化输入已核；managed-runtime-inputs的构建摘要按安装子集canonical定义，不是整文件raw SHA。
- raw完整内容摘要：`356eef11bc4f364e38b5767185bc4b680dfd0c20f0f46b15fa0966ef33c82bec`，37149成员。
- managed完整内容摘要：`ca3666092bfcb6cb9192fd4369c16d7c6544ab95d6edf22a6c07728efbea882c`，37163成员。
- 两fixture均为Linux arm64。x64主机不能复用native raw树；host native夹具与arm64发行归档分开建立、核验来源。

上述内容摘要是本机完整byte proof及来源绑定证据，不代表已通过独立Linux、Android/proot、APK或设备测试。

## 需要接收方补齐的条件

可信独立Linux主机、工具链和浏览器准入、固定输入传递与逐字节校验、完整L1/L2/L3软件门禁。需下载/安装/真实Gradle时，由执行方取得明确授权。不得将本页摘要代替实际接收文件校验或把公开参考包当作当前候选。

未上传任何APK、凭据、机器日志或大二进制；本页也不包含可自动恢复、删除或重放的许可。
