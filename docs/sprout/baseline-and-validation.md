# 精确基线与验证台账

> 快照日期：2026-11-12｜文档包v0.1  
> 这是已查询、已核验的历史快照，不是持续自动刷新的状态面板。后续变更追加记录，不把新分支当前HEAD误当旧运行被测版本。

## 分支角色

| 分支 | 本次文档整理时的提交 | 意义 |
|---|---|---|
| `integration/sprout`（默认） | `4027e4c8a84fa25b09d9b3d7e6b8f833b898dd1e` | 共同源码入口，完整门禁仍有失败 |
| `feat/rebirth-identity` | `3b4fa3d2800fb679d4d1db23e1d723ed7972fd05` | 第十三轮124/129、5失败；下一批仅overlay生命周期生产修复获单独批准，未提交 |
| `docs/sprout-preparation` | 基于4027e4c8，本文档批次 | 只整理公开准备材料，不捎带后续测试修复 |
| `main`（保留） | `23a5b367b60726358c438d7b35eec1350d5ee925` | 原项目介绍历史，不覆盖、不强推 |

正式主仓库未因上述工作而更新。共同分支不是正式发行分支；本线尚未产生嫩芽APK、固定测试签名或设备验收。

## 来源与关键输入

- 参考build359：`92acaa0408cb7b031ee3036c6116c6463ae6b62e`。
- 固定输入归档：`fixed-inputs-20261105e`，SHA256 `be97d9f0a45f408d3a7de83ae55a00a85a5154343589571c6d159a0fe58f08f5`，按既有[输入锁与流程](../fixed-build-inputs.md)恢复，不提交大二进制。
- 内置DSH `0.2.2-rc.1`。raw／managed测试输入需绑定本次平台、lock、来源与完整字节证明；arm64本机与x64 CI不是同一整根摘要。
- 实际APK与源码提交、输入、证书和SHA分别登记；unsigned不是可安装候选。

## 候选CI历史（同提交完整链，不拼各轮结果）

| 轮次／源码 | Actions | 已核结果 | 未执行／限制 |
|---|---|---|---|
| 1／36e8fce8 | [43156086452](https://github.com/133563825as-ai/DSHA-god/actions/runs/43156086452) | workflow语法失败、0 jobs | 不能算产品测试失败或通过 |
| 2／e413b5f2 | [43156476081](https://github.com/133563825as-ai/DSHA-god/actions/runs/43156476081) | host准备引入未锁Kotlin传递依赖，被校验拒绝 | L1以后未执行 |
| 3／0f0bfd35 | [43157688747](https://github.com/133563825as-ai/DSHA-god/actions/runs/43157688747) | fast96/96、unit4496项0失败/错误/跳过、Lint0错误602警告 | runtime历史输入192/212摘要冲突前置阻断；software／包未跑 |
| 4／f71649da | [43161612697](https://github.com/133563825as-ai/DSHA-god/actions/runs/43161612697) | fast97/97、unit4496通过；runtime94/128 | 34失败，software／包未跑 |
| 5／224d32a4 | [43163290551](https://github.com/133563825as-ai/DSHA-god/actions/runs/43163290551) | fast97/97、unit4496通过；runtime95/128，ChatFlow通过 | 33失败，software／包未跑 |
| 6／0a86c652 | [43166385166](https://github.com/133563825as-ai/DSHA-god/actions/runs/43166385166) | fast98/98、unit4496通过、Lint0错误602警告；runtime96/128，真实历史212边界通过 | 32失败，software／包未跑 |
| 7／4027e4c8 | [43174111479](https://github.com/133563825as-ai/DSHA-god/actions/runs/43174111479) | fast99/99、unit4496通过、Lint0错误602警告；runtime107/128，消除11项、无新增 | 21失败，software／包未跑 |
| 8／4c8fe0ea | [43185258663](https://github.com/133563825as-ai/DSHA-god/actions/runs/43185258663) | fast99/99、unit4496通过、Lint0错误602警告；runtime113/128，消除6项、无新增 | 15失败，software／包未跑 |
| 9／7c7406f3 | [43193401370](https://github.com/133563825as-ai/DSHA-god/actions/runs/43193401370) | fast99/99、unit4496通过、Lint0错误602警告；runtime116/128，消除pnpm／vscreen HTTP／Standard宿主3项、无新增 | 12失败，software／包未跑 |
| 10／f5ce9c6e | [43200374634](https://github.com/133563825as-ai/DSHA-god/actions/runs/43200374634) | fast99/99、unit4496通过、Lint0错误602警告；runtime119/129，两个账号用例消除、新增资源测试通过、无新增失败 | 10失败，software／包未跑 |
| 11／a6133683 | [43204430014](https://github.com/133563825as-ai/DSHA-god/actions/runs/43204430014) | fast99/99、unit4496通过、Lint0错误602警告；runtime120/129，锁顺序／来源证明消除 | 9失败；新增worker证明测试失败，不称无回归；software／包未跑 |
| 12／532cc99a | [43209901981](https://github.com/133563825as-ai/DSHA-god/actions/runs/43209901981) | fast99/99、unit4496通过、Lint0错误602警告；runtime123/129，安全提示两项通过，worker本轮恢复通过 | 6失败；worker跨次时序敏感性尚未解决；software／包未跑 |
| 13／3b4fa3d2 | [43214877965](https://github.com/133563825as-ai/DSHA-god/actions/runs/43214877965) | fast99/99、unit4496通过、Lint0错误602警告；runtime124/129，欢迎测试消除、无新增失败 | 5失败：overlay、迁移原件身份、mobile滚动、应急页面、web-search跨代链；software／包未跑 |

unit结果中的7个必需外部fixture在第3／5／6／7／8／9／10／11／12／13轮报告实际通过；这不代表全部host runtime通过。602警告尚未完整做同环境来源差异归因，不声称全为历史警告。默认单测编译链、显式候选行为测试和真正候选release编译是不同证据。

### 已回收证据包标识

GitHub Actions日志／artifact有保留期限。以下摘要用于对账；本批不将内部整包或设备诊断推Git，不承诺Actions artifact永久可下载。原始报告维护者持有，必要时分享经过审查的脱敏副本。

| 运行 | artifact ID | 下载ZIP SHA256 |
|---|---|---|
| 3 | 13916534022 | `2d58f666463d918d17403fb3dddb95e02d424ea72f76e8c888a6fef92b80c4b1` |
| 5 | 13920530737 | `ba19edfad9a7bbcb33ed22c2de0a6c7eff33c1d3a070a4317542a5a9c7e03982` |
| 6 | 13921248094 | `8dff4b711a91c168c752f2c34c8c4f8a2f7e10dcafb56b36c0296abe2877d45f` |
| 7 | 13923963403 | `e9e287c8636812bc37a117b334207f3b1b207d2fd53010463ce2a2ba917664c8` |
| 8 | 13928749883 | `9a25faf7f3688eb6244401a5cbe4643b8fe371628da60d16d4a4c330af3a1fad` |
| 9 | 13933367762 | `b1cfd27dfafe3652f081c2ef260c2ba1d28d8bb525c903f4a7a7adbbf55285ae` |
| 10 | 13935733601 | `e44a52629fd55d83497dcc8f3d144bb5fae44c289d32cfbc77511eda9c29f9bc` |
| 11 | 13940975421 | `ae5d1774b2119cd09ca4e855b850158d25cd93dcb46c4a7b2dfab2e97155421d` |
| 12 | 13941973247 | `ba68523ded480b566556fb5ef0ebbdb59756a5dccb0f5eba78eb5d9f4ce6bae4` |
| 13 | 13944745350 | `a7e0f92174f7985a1c6c36b7377b8c5c5167b9fabdcb7a64257ca24c4aa799ad` |

## 关键修复提交地图

| 完整提交 | 内容 | 结果边界 |
|---|---|---|
| `36e8fce8dec3b5146539ecdd5d5a44b738fa73ac` | 候选身份、更新隔离、unsigned CI／APK checker | 不代表已构建APK |
| `0f0bfd3542a3d70a7f6c5468e4e76c99c0f81f2f` | host依赖非传递解析 | 不放松verification |
| `f71649da6b16c307ce3e0c66162cee3170ed00a1` | 历史212单源契约、ChatFlow真实接口 | 不跳原预算／链测试 |
| `224d32a468589a6caa5e13d68a381fdf6e45f0c9` | ChatFlow锁定PNG fixture加载 | host浏览器结果非手机性能 |
| `0a86c652d4ecd5c381dcd6e022a3fcd8ec663dc2` | 旧212三个真实缺席模块边界 | 43历史bytes＋3明确probe，不造旧模块 |
| `4027e4c8a84fa25b09d9b3d7e6b8f833b898dd1e` | 依赖、编译源、隔离端口／目录、jar／AAPT2准备 | 当前共同基线，未全绿 |
| `4c8fe0eacbe3af2ffae54bb0785468d4d8022931` | native executor、真实历史材料、资源map与诚实生命周期测试 | 生产不改，web-search／overlay仍红 |
| `7c7406f309cf2a22546b4684ba820a3315ade2ce` | pnpm负例PATH／fields、Standard语言桥测试、styleable结构校验 | 第九轮三项真实通过，完整runtime仍12失败 |
| `f5ce9c6ecb41192efa5461a6b11f8532fc148747` | 真实有限资源加载、共享fixture兼容／边界负控、账号测试依赖 | 第十轮119/129，账号两项与资源测试过；不涉及真实账号或生产授权 |
| `a613368347a69d277089b0dbfd941488cf774407` | 锁定HMR摘要及插件来源证明五态负控 | 第十一轮两项通过，不改变生产信任规则；完整runtime仍9失败 |
| `532cc99a52ec0049cdddd1f324ebcbd1f362c28f` | 安全提示112条规则与逐条删除／超长负控、中文取消契约 | 第十二轮两项通过，生产文本不改，非SDK取消交付实测 |
| `3b4fa3d2800fb679d4d1db23e1d723ed7972fd05` | 欢迎测试限定实际预览说明弹窗 | 第十三轮真实通过，剩5项；不关闭后续登录窗冒充保存成功 |

## 更新台账的规则

新增一轮必须登记完整SHA、Actions URL、各阶段状态、失败集合变化和未运行项；产物存在时附实际身份、证书、字节数、SHA。失败清单分测试准备／历史代次／疑似生产／未知，不根据标题归因。

后续修复成熟后可逐批向正式主线提出小提交集成，但需要核正式历史、身份与覆盖升级；不承诺整条实验分支可一键无冲突合并。文档批次与功能批次互相独立审查。
