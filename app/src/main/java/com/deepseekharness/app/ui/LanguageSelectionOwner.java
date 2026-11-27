package com.deepseekharness.app.ui;

/** 页面与弹层捕获的一次性提交凭证；失效后只能由下一次用户点击重新申请。 */
final class LanguageSelectionOwner {
  interface Guard {
    boolean run(Runnable write);
  }

  private long revision;

  synchronized Ticket open(Guard guard) {
    return new Ticket(++revision, guard);
  }

  synchronized void invalidate() {
    revision++;
  }

  final class Ticket {
    private final long captured;
    private final Guard guard;
    private boolean consumed;

    private Ticket(long captured, Guard guard) {
      this.captured = captured;
      this.guard = guard;
    }

    boolean commit(Runnable write) {
      synchronized (LanguageSelectionOwner.this) {
        if (consumed || captured != revision) return false;
        consumed = true;
        return guard.run(write);
      }
    }
  }
}
