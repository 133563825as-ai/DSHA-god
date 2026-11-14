package com.deepseekharness.app.util;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;

/**
 * 普通 Root 请求交付前的进程内代次许可；不替代 Root 管理器授权。
 * 设置变更与 claim 串行，输出流 I/O 不持锁。claim 仅表示许可已消费，不证明载荷已发送。
 * 已知限制：claim 后、首次写入前的撤销仍不能取消写入；Root-01 完整验收因此受阻。
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

  private synchronized boolean claim(Permit permit, BooleanSupplier enabled) {
    if (!current(permit, enabled)) return false;
    permit.consumed = true;
    return true;
  }

  public boolean write(OutputStream input, byte[] payload, Permit permit, BooleanSupplier enabled)
      throws IOException {
    // claim 后写入失败可能只发送了一部分：许可已消费，调用方必须报告结果未知，不能自动重放。
    if (!claim(permit, enabled)) return false;
    input.write(payload);
    return true;
  }
}
