package com.deepseekharness.app.ui;

/** 主线程主题选择的单次提交权；新选择或页面销毁会退休旧截图回调。 */
final class ThemeChangeRequest {
  private final java.util.function.BooleanSupplier moduleAlive;
  private final String pageIdentity;
  private Runnable pendingCleanup;

  void ownPending(Runnable cleanup) { pendingCleanup = cleanup; }

  void retireModule() {
    retire();
    Runnable cleanup = pendingCleanup;
    pendingCleanup = null;
    if (cleanup != null) cleanup.run();
  }

  ThemeChangeRequest() { this(() -> true); }

  ThemeChangeRequest(java.util.function.BooleanSupplier moduleAlive) {
    this(moduleAlive, "legacy");
  }

  ThemeChangeRequest(java.util.function.BooleanSupplier moduleAlive, String pageIdentity) {
    this.moduleAlive = moduleAlive;
    this.pageIdentity = pageIdentity;
  }

  boolean samePage(Request request) { return pageIdentity.equals(request.pageIdentity()); }

  boolean active() { return moduleAlive.getAsBoolean(); }

  private Request current;
  private Reveal reveal;

  Request begin() {
    retire();
    current = new Request();
    return current;
  }

  void retire() {
    current = null;
    if (reveal != null) reveal.close();
  }

  /** 已领取遮罩归本页所有，与尚待跨页领取的 Pending 分开退休。 */
  Reveal ownReveal(Runnable cleanup) {
    if (reveal != null) reveal.close();
    reveal = new Reveal(cleanup);
    return reveal;
  }

  final class Reveal {
    private final Runnable cleanup;
    private boolean closed, started;

    private Reveal(Runnable cleanup) {
      this.cleanup = cleanup;
    }

    boolean start(boolean ownerAlive) {
      if (closed || started) return false;
      if (reveal != this || !ownerAlive || !active()) {
        close();
        return false;
      }
      started = true;
      return true;
    }

    void close() {
      if (closed) return;
      closed = true;
      if (reveal == this) reveal = null;
      cleanup.run();
    }
  }

  final class Request {
    private Request() {}

    boolean moduleActive() { return active(); }

    String pageIdentity() { return ThemeChangeRequest.this.pageIdentity; }

    void ownPending(Runnable cleanup) { ThemeChangeRequest.this.ownPending(cleanup); }

    void retirePending() { ThemeChangeRequest.this.retireModule(); }

    /** 先消费再提交，防止重复回调或提交中的重入再次取得同一请求。 */
    boolean claim(boolean ownerAlive) {
      if (current != this) return false;
      current = null;
      return ownerAlive && active();
    }
  }
}
