package com.deepseekharness.app.ui;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;

/**
 * 实验性页面保活（B-min / K1）：应用作用域、单 owner 的 WebView 页面保管器。
 *
 * <p>Activity 被系统回收时只把 WebView 从视图树摘下并交给本对象，不 {@code destroy()}；新 Activity 以同一
 * generation/instance 接管同一 WebView，不重新 loadUrl。显式结束、服务换代、renderer 退出、内存回收与
 * 任务被移除都会 retire 并销毁页面。进程死亡/强杀/APK 更新时本对象随进程消失，仍由文档恢复兜底。
 *
 * <p>本类不是安全边界：页面在后台仍可能执行脚本。停止 Web、维护与备份必须走 retire（K2 接入点）。
 */
public final class BrowserPageKeeper implements ComponentCallbacks2 {
  public static final String REASON_CONFIG = "config";
  public static final String REASON_SYSTEM = "system";
  public static final String REASON_FINISH = "finish";
  public static final String REASON_RENDERER = "renderer";
  public static final String REASON_IDENTITY = "identity";
  public static final String REASON_SESSION_CHANGED = "session-changed";
  public static final String REASON_RELOAD = "reload";
  public static final String REASON_INIT_FAILED = "init-failed";
  public static final String REASON_FALLBACK = "fallback";
  public static final String REASON_TRIM = "trim";
  public static final String REASON_TASK_REMOVED = "task-removed";
  public static final String REASON_EMPTY = "empty";

  interface Clock {
    long now();
  }

  private static BrowserPageKeeper instance;

  /**
   * 不依赖 DshaApp 的字段：DshaApp/HarnessService 属于运行时描述符钉住的 launcher 源，
   * 页面保活不应为两个钩子改变受管运行时身份。进程只保留最近一个 Application 的 keeper。
   */
  public static BrowserPageKeeper forApplication(Context source) {
    Context context = source;
    try {
      Context application = source.getApplicationContext();
      if (application != null) context = application;
    } catch (RuntimeException unavailable) {
      // 保持传入的 context；诊断仍可用。
    }
    synchronized (BrowserPageKeeper.class) {
      if (instance == null || instance.context != context) {
        instance = new BrowserPageKeeper(context, SystemClock::elapsedRealtime, true);
      }
      return instance;
    }
  }

  private final Context context;
  private final Clock clock;
  private WebPreviewActivity.Retained page;
  private java.lang.ref.WeakReference<WebPreviewActivity> owner =
      new java.lang.ref.WeakReference<>(null);
  private long detachedAt;
  private String detachedReason = "";
  private String retiredReason = "";
  private int attaches;
  private int detaches;
  private int retires;

  BrowserPageKeeper(Context context, Clock clock, boolean registerCallbacks) {
    this.context = context;
    this.clock = clock;
    if (registerCallbacks) {
      try {
        context.registerComponentCallbacks(this);
      } catch (RuntimeException unavailable) {
        // 页面保活是尽力适配：拿不到应用回调时只是失去 trim 兜底。
      }
    }
  }

  /** Activity 借阅应用级唯一页面；没有页面时新建一个空壳，页面创建仍由 Activity 完成。 */
  public synchronized WebPreviewActivity.Retained acquire(WebPreviewActivity activity) {
    boolean adopted = page != null;
    boolean wasDetached = adopted && detachedAt > 0;
    String resumedFrom = detachedReason;
    if (page == null) page = new WebPreviewActivity.Retained();
    if (page.view != null && page.view.getParent() instanceof android.view.ViewGroup parent)
      parent.removeView(page.view);
    page.keeper = this;
    page.owner = new java.lang.ref.WeakReference<>(activity);
    owner = new java.lang.ref.WeakReference<>(activity);
    detachedAt = 0;
    detachedReason = "";
    if (wasDetached) {
      attaches++;
      WebPageDiagnostics.keepAlive(context, "ATTACH", resumedFrom, generationOf(page), page.ready);
    }
    return page;
  }

  /**
   * Activity 销毁但页面要保留（配置变更或系统回收）。只清 owner；视图的摘除与客户端重挂由 Activity 完成。
   * 空壳页面没有可保留的内容，直接 retire。
   */
  public synchronized boolean detach(WebPreviewActivity activity, String reason) {
    if (page == null) return false;
    if (owner.get() != activity) return false;
    owner.clear();
    if (page.view == null) {
      retireLocked(REASON_EMPTY);
      return false;
    }
    detachedAt = clock.now();
    detachedReason = com.deepseekharness.app.util.WebPageDiagnosticText.reasonCode(reason);
    WebPageDiagnostics.keepAlive(context, "DETACH", reason, generationOf(page), page.ready);
    detaches++;
    return true;
  }

  /** 当前 Activity 显式结束页面；owner 已换成别的 Activity 时拒绝，避免旧实例销毁新页面。 */
  public synchronized boolean retireFrom(WebPreviewActivity activity, String reason) {
    if (page == null) return false;
    if (owner.get() != activity) return false;
    retireLocked(reason);
    return true;
  }

  /** renderer 在 Activity 摘挂空窗内退出：只有 keeper 还持有该页面时才允许 retire。 */
  synchronized void retireDetached(WebPreviewActivity.Retained candidate, String reason) {
    if (page == null || page != candidate || owner.get() != null) return;
    retireLocked(reason);
  }

  /** 应用级销毁（停止 Web、维护、origin 恢复等 K2 接入点）。 */
  public synchronized boolean retire(String reason) {
    if (page == null) return false;
    retireLocked(reason);
    return true;
  }

  /** 仅摘挂状态响应系统内存压力；可见页面交给系统/错误面板，不在这里静默销毁。 */
  public synchronized boolean trimMemory(int level) {
    boolean pressure =
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
            || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
            || level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
    if (!pressure || page == null || owner.get() != null) return false;
    retireLocked(REASON_TRIM);
    return true;
  }

  /** 任务被系统移除：进程通常也会结束，但不能依赖进程死亡来释放页面。 */
  public synchronized boolean onTaskRemoved() {
    if (page == null) return false;
    retireLocked(REASON_TASK_REMOVED);
    return true;
  }

  public synchronized WebPreviewActivity.Retained page() {
    return page;
  }

  public synchronized boolean isAttached() {
    return page != null && owner.get() != null;
  }

  /** 当前页面是否仍归这个 Activity；新实例先接管后，旧实例的 onDestroy 只能放手。 */
  public synchronized boolean owns(WebPreviewActivity activity) {
    return page != null && owner.get() == activity;
  }

  public synchronized boolean hasDetachedPage() {
    return page != null && page.view != null && owner.get() == null;
  }

  public synchronized long detachedAt() {
    return detachedAt;
  }

  public synchronized String lastDetachedReason() {
    return detachedReason;
  }

  public synchronized String lastRetiredReason() {
    return retiredReason;
  }

  public synchronized int attaches() {
    return attaches;
  }

  public synchronized int detaches() {
    return detaches;
  }

  public synchronized int retires() {
    return retires;
  }

  @Override
  public void onTrimMemory(int level) {
    trimMemory(level);
  }

  @Override
  public void onLowMemory() {
    trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {}

  private void retireLocked(String reason) {
    WebPreviewActivity.Retained retiring = page;
    long generation = generationOf(retiring);
    boolean ready = retiring != null && retiring.ready;
    page = null;
    detachedAt = 0;
    detachedReason = "";
    retiredReason = com.deepseekharness.app.util.WebPageDiagnosticText.reasonCode(reason);
    retires++;
    owner.clear();
    if (retiring == null) return;
    WebPageDiagnostics.keepAlive(context, "RETIRE", retiredReason, generation, ready);
    retiring.keeper = null;
    retiring.owner.clear();
    if (retiring.view != null && retiring.view.getParent() instanceof android.view.ViewGroup parent)
      parent.removeView(retiring.view);
    retiring.dispose();
  }

  static long generationOf(WebPreviewActivity.Retained candidate) {
    if (candidate == null) return -1;
    var identity = candidate.pageSession.identity();
    return identity == null ? -1 : identity.generation();
  }
}
