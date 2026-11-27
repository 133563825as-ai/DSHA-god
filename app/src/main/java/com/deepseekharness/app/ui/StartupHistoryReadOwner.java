package com.deepseekharness.app.ui;

import com.deepseekharness.app.util.StartupHistoryStore;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;

/** 只退休交付；共享门闩直到不可取消的真实读取退出才释放，不排队、不打断线程。 */
final class StartupHistoryReadOwner {
  interface Reader {
    List<StartupHistoryStore.Entry> read() throws Exception;
  }

  private static final Object IO_LOCK = new Object();
  private static Object reading;
  private final BooleanSupplier active;
  private final Executor worker;
  private final Executor delivery;
  private long revision;

  StartupHistoryReadOwner(BooleanSupplier active, Executor delivery) {
    this(active, task -> new Thread(task, "diagnostic-history").start(), delivery);
  }

  StartupHistoryReadOwner(BooleanSupplier active, Executor worker, Executor delivery) {
    this.active = active;
    this.worker = worker;
    this.delivery = delivery;
  }

  boolean request(Reader reader, BiConsumer<List<StartupHistoryStore.Entry>, Boolean> receive) {
    final long ticket;
    final Object io = new Object();
    synchronized (this) {
      if (!active.getAsBoolean()) return false;
      synchronized (IO_LOCK) {
        if (reading != null) return false;
        reading = io;
      }
      ticket = ++revision;
    }
    try {
      worker.execute(
          () -> {
            List<StartupHistoryStore.Entry> entries = List.of();
            boolean failed = false;
            try {
              synchronized (StartupHistoryReadOwner.this) {
                if (ticket != revision || !active.getAsBoolean()) return;
                // 此处承诺一次读取；退休只丢结果，不等待或打断随后不可取消的 IO。
              }
              entries = reader.read();
            } catch (Exception error) {
              failed = true;
            } finally {
              synchronized (IO_LOCK) {
                if (reading == io) reading = null;
              }
            }
            final var result = entries;
            final boolean failure = failed;
            delivery.execute(
                () -> {
                  synchronized (StartupHistoryReadOwner.this) {
                    if (ticket == revision && active.getAsBoolean())
                      receive.accept(result, failure);
                  }
                });
          });
    } catch (RuntimeException error) {
      synchronized (IO_LOCK) {
        if (reading == io) reading = null;
      }
      delivery.execute(
          () -> {
            synchronized (StartupHistoryReadOwner.this) {
              if (ticket == revision && active.getAsBoolean()) receive.accept(List.of(), true);
            }
          });
    }
    return true;
  }

  synchronized void retire() {
    revision++;
  }
}
