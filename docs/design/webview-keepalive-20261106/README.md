# WebView 页面保活 B-min：K0 证据 + K1 keeper（实验性）

- 日期：2026-11-06（UTC）
- 基线：`origin/main` = `e22369b8`（build333 发布准备；本分支另在 `3d3932f8` 上做过 rebase 兼容检查，代码不依赖该提交）
- 任务：按 `/tmp/webview-keepalive.md` 实施 **B-min 的 K0+K1**；只做代码 + 单测 + 本设计文档，不 push、不合并、不发布、不安装包
- 相关：K2（生命周期安全）、K3（插件契约）、K4（认证/首帧/内存）、K5（真机验收）均**不在本次范围**
- 定位：**实验性、尽力适配**。只承诺“同一进程内 Activity 被系统回收时页面不变”；进程死亡/强杀/APK 更新仍然重载，靠文档恢复（方案 D）兜底。不承诺所有手机、平板、自由窗口或 OEM WebView 表现一致。

## 0. 一句话结论

`Retained` 的所有权从 Activity/ViewModel 提升到进程级 `BrowserPageKeeper`：

- `onDestroy(isChangingConfigurations)` → 只摘挂，页面留在 keeper；
- `onDestroy(isFinishing)` → retire/destroy；
- 系统回收（两者皆 false）→ 摘挂保留，新 Activity 以同一 generation/instance 接管同一 WebView，**不 `loadUrl`**；
- generation/instance 不匹配、renderer 退出、detached 时的 trim/任务移除 → 销毁，不复用死页面。

K0 只加脱敏诊断，供真机区分“Activity 被回收（P0）/ 进程死亡（P1）/ Web 换代（P2）/ renderer 退出（P3）”。

## 1. 为什么需要 K1（设计报告摘要）

原 `WebPreviewActivity.onDestroy` 只有两个分支：

```java
if (isChangingConfigurations() && webView != null) {
  // 配置变更：detach，WebView 交给 ViewModel
} else destroyWebView();
```

系统在进程存活时回收 stopped Activity（`isFinishing()==false`、`isChangingConfigurations()==false`）会走进 `else`，把唯一页面销毁；用户回到 Web 时 ViewModel 已清空，只能重新 `loadUrl`。`singleTask` / `reorderToFront` / `launchMode` 都解决不了“唯一实例已被系统回收”。

B-min 的做法是把 `Retained` 从 Activity 作用域提升到应用作用域，只把“何时销毁”从 Activity 的隐式行为改成显式策略。

## 2. 实现范围

### 2.1 新增

| 文件 | 职责 |
|---|---|
| `ui/BrowserPageKeeper.java` | 应用作用域、单 owner 的页面保管器；`acquire/detach/retireFrom/retire/trimMemory/onTaskRemoved`；实现 `ComponentCallbacks2`（低内存） |
| `ui/WebPageDiagnostics.java` | K0 脱敏诊断入口：`WEB_ENGINE`、`WEB_PAGE_DESTROY`、`WEB_KEEPALIVE_ATTACH/DETACH/RETIRE` |
| `util/WebPageDiagnosticText.java` | 诊断行纯格式化；原因码白名单（小写字母/数字/连字符，≤24），URL/token/控制字符只会得到 `unknown` |

### 2.2 修改

| 文件 | 改动 |
|---|---|
| `ui/WebPreviewActivity.java` | `Retained` 改为从 keeper 借阅；`onDestroy` 三分支（config/system/finish）；`detachPageView()`；`destroyWebView(reason)`；renderer 退出与各销毁入口记录 K0 原因 |
| `tools/java-format-files.json` | 登记 5 个新 Java 文件（formatter 显式清单） |
| `WebBackgroundPageLifecycleTest` | 改用 keeper 页面；新增系统回收/配置变更/显式结束/onNewIntent/换代测试 |
| `BrowserPageKeeperTest`（新） | keeper 级单页所有权、trim、task-removed、renderer gap 测试 |
| `WebPageDiagnosticTextTest`（新） | 诊断行白名单与脱敏测试 |

**没有改**：`app-integration/client.js`（K3）、`DshaApp.java`、`HarnessService.java`、`runtime-descriptor.json`。
后三者属于运行时描述符钉住的 launcher 源；改它们会重算 `runtimeId`，让已装运行时变成“非当前候选”，与“只做实验性页面保活”的收益不成比例。低内存钩子因此由 keeper 自己 `registerComponentCallbacks`，任务移除先由 Activity `onDestroy(isFinishing)` 覆盖（见 §6 已知限制）。

## 3. K0：脱敏证据

只记录可枚举事实，不写页面地址、cookie、token 或页面标题。

| 日志 stage | 内容 | 用途 |
|---|---|---|
| `WEB_ENGINE` | `pid=<pid> generation=<gen> webview=<包名 版本>` | 区分“新进程”（pid 变化）与“新文档”（generation/新引擎行） |
| `WEB_PAGE_DESTROY` | `reason=<finish|identity|session-changed|reload|init-failed|fallback|renderer> changingConfig=<bool> finishing=<bool> generation=<gen>` | 证明销毁发生在哪条路径；`changingConfig=false finishing=false` 不会出现在这里（说明是系统回收） |
| `WEB_KEEPALIVE_ATTACH` | `action=ATTACH reason=<上次 detach 原因> generation=<gen> ready=<bool>` | 新 Activity 接管了 detached 页面 |
| `WEB_KEEPALIVE_DETACH` | `action=DETACH reason=<config|system> generation=<gen> ready=<bool>` | 页面被保留 |
| `WEB_KEEPALIVE_RETIRE` | `action=RETIRE reason=<finish|renderer|trim|task-removed|empty> generation=<gen> ready=<bool>` | keeper 主动销毁 |

一次真机往返即可判断：

- 有 `WEB_ENGINE` + 新 pid → P1 进程死亡；
- 有 `WEB_ENGINE` 但 pid 相同、无 `WEB_PAGE_DESTROY(finishing=false,changingConfig=false)` → 进程内被回收（P0，K1 目标）；
- `WEB_PAGE_DESTROY reason=session-changed` → Web 服务换代/停止（P2，预期重载）；
- `WEB_PAGE_DESTROY reason=renderer` → renderer 退出（P3）。

原因码经 `WebPageDiagnosticText.reasonCode` 白名单处理；`WebPageDiagnostics` 不接收 URL。

## 4. K1：状态机与规则

```
[无页面]
   │ Activity 首次创建 → keeper.acquire() → loadSession → createWebView
   ▼
[attached] ──onStop/Home/Back──> [attached, Activity stopped]
   │                                    │
   │ onDestroy(changingConfig)          │ onDestroy(system reclaim)
   ▼                                    ▼
[detached in keeper] <──────────────────┘
   │ 新 Activity onCreate（identity 匹配）→ attach，不 loadUrl
   ▼
[attached, 同一 WebView]

[detached] ──finish/identity/renderer/trim/task-removed→ [retire/destroy]
[attached] ──finish/identity/session-changed/renderer────> [retire/destroy]
进程死亡 ──────────────────────────────────────────────> [无页面，走 D 恢复]
```

### 4.1 `onDestroy` 策略

| 条件 | 行为 |
|---|---|
| `isChangingConfigurations()` | `detachPageView()` + `keeper.detach(config)`；新 Activity acquire 同一页面 |
| `isFinishing()` | 记录 `WEB_PAGE_DESTROY reason=finish` → 摘视图 → `keeper.retireFrom(finish)` → `Retained.dispose()` 销毁 WebView/上传/下载/麦克风 |
| 两者皆 false（系统回收） | 摘视图 + `keeper.detach(system)`；页面保留，等新 Activity 接管 |
| keeper 已不拥有本 Activity | 只移除本地视图引用，不摘别人的页面（防止旧实例误杀新 owner） |

`detachPageView()` 只做视图/客户端摘除：`container.removeView`、`WebViewClient` 换成只处理 renderer 退出的 `RetainedRendererClient`、`WebChromeClient`/`DownloadListener` 置空、`MutableContextWrapper` 切到 application context。页面自己的 `pageSession`/`themeGate`/`pageEvents`/`compatibilityScript` 属于 WebView 级，保留。

### 4.2 接管条件

- `keeper.acquire()` 返回应用级唯一 `Retained`；新 Activity 仍走原有 `onCreate` 判定：
  - `retained.view != null` 且 `pageSession` 与当前 `getReadyWebPageIdentity()` 一致 → 挂回容器，`continueInitialNavigation()`；
  - 身份不一致 → `destroyWebView(identity)` 销毁旧 WebView，再用当前凭据 `loadSession`；
  - 没有页面 → 正常 `loadSession`。
- 首次导航由 `PreviewNavigation.claim()` 保证只发生一次：已完成的页面在 Activity 重建后 `claim()` 返回 false，不会二次 `loadUrl`。
- `keeper.acquire` 若发现页面仍挂在旧容器上（极端竞态），先 `removeView` 再交给新 Activity，避免 “already has a parent”。

### 4.3 销毁规则

- `detach` 只清 owner；空壳页面（`view == null`）没有保留价值，直接 retire。
- `retireFrom(activity, reason)` 只在 keeper 仍属于该 Activity 时生效；旧 Activity 不能销毁新 Activity 已接管的页面。
- renderer 在 detached 空窗退出：`Retained.retireRendererView` 清理后调用 `keeper.retireDetached(renderer)`；前台 renderer 退出仍由现有错误面板/重试路径接管，keeper 保留空壳以便重试。
- `trimMemory`：只有 `detached` 页面在 `RUNNING_LOW/RUNNING_CRITICAL/COMPLETE` 时销毁；`TRIM_MEMORY_UI_HIDDEN` 不等于内存不足，不销毁可见页面。
- `onTaskRemoved()`：retire 当前页面（K1 提供 API 与测试；生产钩子见 §6）。

## 5. 测试与结果

单测（Robolectric，`sdk=34`，不运行真实网页/guest）：

- `BrowserPageKeeperTest`（10）：单页复用、config detach、finish retire、owner 隔离、空壳、trim（可见不销毁/UI_HIDDEN 不销毁/detached 销毁）、task-removed、renderer gap。
- `WebBackgroundPageLifecycleTest`（新增 5 + 既有 13）：`ActivityController` 模拟系统回收后新 Activity 接管同一 `Page` 且 `navigations` 仍为 1；配置变更重建同样不 load；`finish()` 销毁并释放 keeper；`onNewIntent` 同代不增加导航；Web 换代销毁旧页面且不复用。
- `WebPageDiagnosticTextTest`（5）：URL/token/控制字符不能进入诊断行；原因码白名单。
- 回归：`WebBackgroundPageLifecycleTest`、`BrowserTasksTest`、`PictureInPictureOptInTest`、`WebPageBootOverlayTest`、`WebCrashPanelTest` 全部通过。

命令：`dsha-gradle :app:testStandardDebugUnitTest --tests '*BrowserPageKeeperTest' ...`（定向，不跑全量）。

## 6. 已知限制与风险（必须如实声明）

1. **进程死亡/强杀/APK 更新**：keeper 随进程消失，DOM/JS 堆不可恢复；仍走 `WebView.saveState` + 插件草稿/阅读位置恢复。不要把本方案写成“杀进程也不变”。
2. **不承诺全机型/平板/自由窗口/OEM WebView**：detach/attach、`onPause`、renderer 回收时序存在厂商差异；失败时页面照旧重载，不应崩溃。
3. **detached 期间的停止/维护/备份尚未自动 retire**：`WebPreviewActivity.onDestroy` 只在有 Activity 时执行；如果页面已经 detached（没有 Activity），用户停止 Web/维护不会立刻销毁 keep-alive 页面，只能等下一次 attach 时的 `synchronizeWebSession` → `destroyWebView(session-changed)`，或 trim/任务移除。**K2 必须接入 `HarnessController` ready/stop、维护与 origin-restore 的显式 `retire`。**
4. **任务移除没有直接 Service 钩子**：`HarnessService` 是运行时描述符钉住的 launcher 源，本分支不修改它。用户划掉任务时 Activity 会走 `onDestroy(isFinishing)` → retire，实际路径已覆盖；`BrowserPageKeeper.onTaskRemoved()` 已实现并单测，供 K2 在可接受 `runtimeId` 变更时接入。
5. **认证/首帧未重校验（K4）**：detached 页面复用旧 cookie；`onResume` 仍比较 `getWebAuthUrl()` 并重推语言/主题，但没有做主动 auth lease 探测。401/403 仍走既有一次性重取。
6. **插件契约未实现（K3）**：`client.js` 不知道 “同一文档 suspend/resume”；保活页不会伪造 `pageshow/pagehide`，但旧插件在后台的草稿 lease/阅读位置行为未验证。K3 需要 `__DSHA_NATIVE_LIFECYCLE__` + `documentToken` + 握手降级。
7. **后台 JS/网络**：detached 页面仍可能执行脚本；本分支只在 `onStop` 调 `webView.onPause()`，没有 `pauseTimers()` 或空闲超时。K2/K4 需要明确的停止/超时/内存策略。
8. **origin-restore**：QUIESCE → `finish()` → `isFinishing` 分支会 retire，正常情况下不会与迁移并发；但“detached 页面 + 广播时没有 Activity”组合未覆盖，K2 需把 keeper 纳入 `awaitClosed`。
9. **上传/下载/麦克风**：`Retained.dispose()` 沿用现有 `onCleared` 清理；detached 期间 `pendingPicker/uploadSession/blobDownload/microphone` 的行为只做了静态清理，未做真机验证。特别注意：detached 后 `retained.blobDownload` 仍持有旧 Activity 的 `WebDownloads.model`（与既有配置变更路径一致，本分支未改动），K2/K4 需要显式关闭/重绑并在真机验证上传/下载/麦克风。
10. **单宿主约束**：keeper 只持有一个页面，依赖现有 `BrowserConversationRoute` + `forwardToLiveBrowser` 保证同一时刻只有一个 Web 宿主；多窗口/自由窗口/PiP 双宿主互抢页面的场景未在 K1 处理（K2）。
11. **实验性**：没有 feature flag；如果真机表现不可接受，回退方式是 revert 本分支（页面重建路径保持不变），或后续在 K2 加 `ConfigStore` 开关。

## 7. 后续任务

- **K2 生命周期安全**：stop/维护/备份/origin-restore/task-removed/trim/多窗口；`HarnessController` ready 监听；`awaitClosed` 纳入 keeper；需要时再评估是否接受 `runtimeId` 变更以接入 `HarnessService.onTaskRemoved`。
- **K3 插件契约**：`__DSHA_NATIVE_LIFECYCLE__`、`dsha-browser-suspend/resume`、`documentToken`；`client.js` 的 lease/阅读位置/流恢复；旧插件握手失败则不保活。
- **K4 认证/首帧/内存**：detach 前 `pauseTimers`、attach 首帧遮罩、auth lease 探测、内存预算/空闲超时/feature flag。
- **K5 真机验收**：测试包（`io.github.sunsetrne.dsha.test`）覆盖应用内往返、Home、开发者选项“不保留活动”、进程死亡、配置、PiP、停止/维护、origin-restore、renderer、内存、多窗口、上传/下载/麦克风；不安装正式包。
- **可选 A1**：`alwaysRetainTaskState` 等微优化与 K1 独立，未纳入本次。

## 8. 参考

- 设计报告：`/tmp/webview-keepalive.md`（只读评估，本分支不在仓库内引用该路径）
- 关键代码：`app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java`、`ui/BrowserPageKeeper.java`、`ui/WebPageDiagnostics.java`、`util/WebPageDiagnosticText.java`
- 测试：`app/src/test/java/com/deepseekharness/app/ui/BrowserPageKeeperTest.java`、`ui/WebBackgroundPageLifecycleTest.java`、`util/WebPageDiagnosticTextTest.java`
