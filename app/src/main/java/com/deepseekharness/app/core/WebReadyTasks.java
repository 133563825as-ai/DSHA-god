package com.deepseekharness.app.core;

import com.deepseekharness.app.util.Rc1MigrationResult;
import java.util.Map;

/** Bounded post-authentication work. The caller remains the generation and state authority. */
final class WebReadyTasks {
  interface Ports {
    boolean current();

    void asynchronous(String name, Runnable work);

    void delay(long millis) throws InterruptedException;

    String finalizeMigration() throws Exception;

    String startupId();

    void migrationLog(String value);

    void migrationNeedsAttention();

    boolean exchangeCookie();

    boolean lanBound();

    void lanReady();
  }

  static void run(boolean safeMode, boolean lanMode, Ports ports) {
    if (!ports.current()) return;
    if (!safeMode) ports.asynchronous("dsha-rc1-verification", () -> finalizeMigration(ports));
    if (!lanMode) return;
    for (int attempt = 0; attempt < 3 && ports.current(); attempt++) {
      try {
        if (ports.exchangeCookie()) break;
      } catch (Throwable ignored) {
      }
      try {
        ports.delay(1200);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    for (int attempt = 0; attempt < 12 && ports.current() && !ports.lanBound(); attempt++) {
      try {
        ports.delay(200);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    if (ports.current() && ports.lanBound()) ports.lanReady();
  }

  private static void finalizeMigration(Ports ports) {
    try {
      ports.delay(3000);
      if (!ports.current()) return;
      String result = ports.finalizeMigration();
      if (!ports.current()) return;
      Map<String, Object> receipt = Rc1MigrationResult.parse(result);
      if (!Rc1MigrationResult.boundTo(receipt, ports.startupId())
          || !Rc1MigrationResult.allowsFinalize(receipt)) {
        ports.migrationLog(result);
        if (ports.current()) ports.migrationNeedsAttention();
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } catch (Exception error) {
      if (ports.current()) {
        ports.migrationLog("RC1_MIGRATION_VERIFICATION_UNAVAILABLE");
        ports.migrationNeedsAttention();
      }
    }
  }
}
