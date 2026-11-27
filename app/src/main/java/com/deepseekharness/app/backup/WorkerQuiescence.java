package com.deepseekharness.app.backup;

import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * 在删除作业目录前封住新 worker，并等待已经登记的 worker 真正跑完 finally。
 * 登记发生在线程 start 之前，避免“刚判断空闲，线程才启动”的窗口。
 */
final class WorkerQuiescence {
  final class Lease implements AutoCloseable {
    private boolean closed;

    @Override
    public void close() {
      synchronized (WorkerQuiescence.this) {
        if (closed) return;
        closed = true;
        active--;
        WorkerQuiescence.this.notifyAll();
      }
    }
  }

  /** 当前选择与物理 worker 分开：即使取消，lease 也只由工作线程的 finally 释放。 */
  final class Read {
    final BackupControl control = new BackupControl(null);
    final Lease worker = new Lease();

    void cancel() {
      synchronized (WorkerQuiescence.this) {
        if (current == this) invalidateRead();
      }
    }

    boolean current() {
      synchronized (WorkerQuiescence.this) {
        return current == this;
      }
    }
  }

  private Read current;
  private boolean closed;
  private int active;

  synchronized void invalidateRead() {
    if (current != null) {
      current.control.cancel(); // 此只读流只轮询取消，不登记 I/O 回调。
      current = null;
    }
  }

  synchronized Read tryRead(boolean ownerIdle) {
    invalidateRead(); // 重新选择即作废旧结果，即使旧读仍未退出而无法接单。
    if (!ownerIdle || closed || active != 0) return null;
    current = new Read();
    active++;
    return current;
  }

  synchronized Lease tryBegin() {
    if (closed) return null;
    invalidateRead(); // 普通作业优先，不等待已取消的软读退出。
    active++;
    return new Lease();
  }

  synchronized void closeGate() {
    closed = true;
    invalidateRead();
  }

  synchronized void reopen() {
    closed = false;
    notifyAll();
  }

  synchronized boolean isClosed() {
    return closed;
  }

  synchronized int active() {
    return active;
  }

  void awaitIdle(BackupControl control, long timeoutMillis) throws IOException {
    long timeoutNanos = Math.max(0, timeoutMillis) * 1_000_000L;
    long started = System.nanoTime();
    synchronized (this) {
      while (active > 0) {
        control.check();
        long remaining = timeoutNanos - (System.nanoTime() - started);
        if (remaining <= 0) throw new IOException("WORKER_QUIESCE_TIMEOUT");
        long waitMillis = Math.max(1, Math.min(100, remaining / 1_000_000L));
        try {
          wait(waitMillis);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException("WORKER_QUIESCE_INTERRUPTED");
        }
      }
    }
  }
}
