package com.deepseekharness.app.util;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;

/**
 * 普通 Root 请求交付前的进程内代次许可；不替代 Root 管理器授权。
 * 请求可在锁外预写；helper 只暂存，直到收到锁内的一字节确认及 EOF 才执行。
 * 撤销与最终确认串行，预写阻塞不妨碍撤销。
 */
public final class RootRequestGate {
  private long generation;
  private boolean settingsUnavailable;

  public static final class Permit {
    private final RootRequestGate owner;
    private final long generation;
    private boolean consumed;

    private Permit(RootRequestGate owner) {
      this.owner = owner;
      this.generation = owner.generation;
    }
  }

  @FunctionalInterface
  public interface AwaitStaged {
    void await() throws IOException, InterruptedException;
  }

  public synchronized Permit capture(BooleanSupplier enabled) {
    return !settingsUnavailable && allowed(enabled) ? new Permit(this) : null;
  }

  public synchronized void update(Runnable save) {
    settingsUnavailable = true;
    if (generation == Long.MAX_VALUE)
      throw new IllegalStateException("ROOT_AUTHORITY_GENERATION_EXHAUSTED");
    generation++;
    save.run();
    settingsUnavailable = false;
  }

  public synchronized boolean current(Permit permit, BooleanSupplier enabled) {
    return !settingsUnavailable
        && permit != null
        && permit.owner == this
        && permit.generation == generation
        && !permit.consumed
        && allowed(enabled);
  }

  private static boolean allowed(BooleanSupplier enabled) {
    try {
      return enabled.getAsBoolean();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  public boolean write(OutputStream input, byte[] payload, Permit permit, BooleanSupplier enabled)
      throws IOException, InterruptedException {
    return write(input, payload, permit, enabled, () -> {});
  }

  public boolean write(
      OutputStream input,
      byte[] payload,
      Permit permit,
      BooleanSupplier enabled,
      AwaitStaged staged)
      throws IOException, InterruptedException {
    boolean closed = false;
    try {
      synchronized (this) {
        if (!current(permit, enabled)) return false;
        permit.consumed = true;
      }
      RootRequestWire.stage(input, payload);
      staged.await();
      synchronized (this) {
        if (settingsUnavailable
            || permit.owner != this
            || permit.generation != generation
            || !allowed(enabled)) return false;
        // 确认字节或 EOF 失败可能已经派发：许可已消费，调用方报告 UNKNOWN。
        RootRequestWire.commit(input);
        input.close();
        closed = true;
        return true;
      }
    } finally {
      if (!closed) input.close();
    }
  }
}
