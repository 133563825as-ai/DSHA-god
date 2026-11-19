package com.deepseekharness.app.ui;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 许可正文读取的单活 owner；解析仍归 AboutLicenses，离页只退休回调，不提前归还读取槽位。 */
final class LicenseReadOwner {
  private static final Object SLOT = new Object();
  private static boolean busy;
  private static final Executor IO = Executors.newSingleThreadExecutor();

  interface Source {
    AboutLicenses.ReadResult read(BooleanSupplier cancelled);
  }

  private static final class Request {
    volatile boolean cancelled;
    Consumer<AboutLicenses.ReadResult> callback;

    Request(Consumer<AboutLicenses.ReadResult> callback) {
      this.callback = callback;
    }
  }

  private Request current;

  boolean read(Source source, Executor ui, Consumer<AboutLicenses.ReadResult> callback) {
    return read(source, IO, ui, callback);
  }

  boolean read(
      Source source, Executor io, Executor ui, Consumer<AboutLicenses.ReadResult> callback) {
    synchronized (SLOT) {
      if (busy) return false;
      busy = true;
    }
    Request request = new Request(callback);
    synchronized (this) {
      cancel();
      current = request;
    }
    try {
      io.execute(() -> load(source, ui, request));
    } catch (RuntimeException rejected) {
      synchronized (this) {
        if (current == request) cancel();
      }
      synchronized (SLOT) {
        busy = false;
      }
      return false;
    }
    return true;
  }

  synchronized void cancel() {
    if (current == null) return;
    current.cancelled = true;
    current.callback = null;
    current = null;
  }

  private void load(Source source, Executor ui, Request request) {
    AboutLicenses.ReadResult result;
    try {
      if (request.cancelled) return;
      result = source.read(() -> request.cancelled);
    } catch (RuntimeException unavailable) {
      result = new AboutLicenses.ReadResult(null, AboutLicenses.Failure.UNREADABLE);
    } finally {
      // Source 返回前负责关流；取消不能让另一个页面提前占用仍在读取的槽位。
      synchronized (SLOT) {
        busy = false;
      }
    }
    if (request.cancelled) return;
    final AboutLicenses.ReadResult completed = result;
    try {
      ui.execute(
          () -> {
            synchronized (this) {
              if (current != request || request.cancelled) return;
              Consumer<AboutLicenses.ReadResult> callback = request.callback;
              current = null;
              request.callback = null;
              if (callback != null) callback.accept(completed);
            }
          });
    } catch (RuntimeException rejected) {
      synchronized (this) {
        if (current == request) cancel();
      }
    }
  }
}
