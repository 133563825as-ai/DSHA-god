package com.deepseekharness.app.backup;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorkerQuiescenceTest {
  @Test
  public void waitsForWorkerFinallyAndRejectsNewWorkersWhileClosed() throws Exception {
    WorkerQuiescence gate = new WorkerQuiescence();
    WorkerQuiescence.Lease running = gate.tryBegin();
    assertNotNull(running);
    gate.closeGate();
    assertNull(gate.tryBegin());

    CountDownLatch entered = new CountDownLatch(1), finished = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread waiter =
        new Thread(
            () -> {
              entered.countDown();
              try {
                gate.awaitIdle(new BackupControl(null), 2_000);
              } catch (Throwable error) {
                failure.set(error);
              } finally {
                finished.countDown();
              }
            });
    waiter.start();
    assertTrue(entered.await(1, TimeUnit.SECONDS));
    assertFalse(
        "await must not trust an early busy=false state",
        finished.await(100, TimeUnit.MILLISECONDS));
    running.close();
    assertTrue(finished.await(1, TimeUnit.SECONDS));
    assertNull(failure.get());

    gate.reopen();
    WorkerQuiescence.Lease next = gate.tryBegin();
    assertNotNull(next);
    next.close();
  }

  @Test
  public void readNeedsIdleOwnerAndWorkerExitNotJustTerminalState() {
    WorkerQuiescence gate = new WorkerQuiescence();
    assertNull(gate.tryRead(false));
    WorkerQuiescence.Lease normal = gate.tryBegin();
    assertNull(gate.tryRead(true));
    normal.close();
    WorkerQuiescence.Read first = gate.tryRead(true);
    assertNotNull(first);
    assertNull(gate.tryRead(true)); // 换选使旧结果失效，但不凭取消信号复用 worker 名额。
    assertTrue(first.control.isCancelled());
    assertFalse(first.current());
    assertEquals(1, gate.active());
    first.worker.close();
    WorkerQuiescence.Read second = gate.tryRead(true);
    first.cancel(); // 旧页面的迟到 close 不能取消新页面的请求。
    assertTrue(second.current());
    assertFalse(second.control.isCancelled());
    second.cancel();
    assertNull(gate.tryRead(true));
    second.worker.close();
  }

  @Test
  public void normalLaunchInvalidatesBeforeMutationWithoutWaitingForSoftRead() {
    WorkerQuiescence gate = new WorkerQuiescence();
    WorkerQuiescence.Read reading = gate.tryRead(true);
    gate.invalidateRead(); // 普通入口：即使准备阶段失败不登记线程，也已作废快照。
    assertFalse(reading.current());
    WorkerQuiescence.Lease normal = gate.tryBegin();
    assertNotNull(normal);
    assertEquals(2, gate.active());
    reading.cancel();
    reading.worker.close();
    assertEquals(1, gate.active());
    assertNull(gate.tryRead(true));
    normal.close();
  }

  @Test
  public void finishedReadCannotDeliverAfterReselectCloseNormalJobOrReset() {
    WorkerQuiescence gate = new WorkerQuiescence();
    WorkerQuiescence.Read old = gate.tryRead(true);
    old.worker.close(); // 模拟结果已入主线程队列。
    assertTrue(old.current());
    WorkerQuiescence.Read next = gate.tryRead(true);
    assertFalse(old.current());
    next.worker.close();
    next.cancel();
    assertFalse(next.current());
    WorkerQuiescence.Read beforeJob = gate.tryRead(true);
    beforeJob.worker.close();
    gate.tryBegin().close();
    assertFalse(beforeJob.current());
    WorkerQuiescence.Read beforeReset = gate.tryRead(true);
    beforeReset.worker.close();
    gate.closeGate();
    gate.reopen();
    assertFalse(beforeReset.current());
  }

  @Test
  public void resetWaitsForCancelledReadFinallyAndReopenDoesNotResurrectIt() throws Exception {
    WorkerQuiescence gate = new WorkerQuiescence();
    WorkerQuiescence.Read read = gate.tryRead(true);
    gate.closeGate();
    assertTrue(read.control.isCancelled());
    assertNull(gate.tryRead(true));
    try {
      gate.awaitIdle(new BackupControl(null), 0);
      fail("cancel is not exit");
    } catch (IOException expected) {
      assertEquals("WORKER_QUIESCE_TIMEOUT", expected.getMessage());
    }
    gate.reopen();
    assertNull(gate.tryRead(true));
    read.worker.close();
    gate.awaitIdle(new BackupControl(null), 0);
    assertFalse(read.current());
  }

  @Test
  public void timeoutKeepsGateClosedUntilCallerExplicitlyReopens() throws Exception {
    WorkerQuiescence gate = new WorkerQuiescence();
    WorkerQuiescence.Lease running = gate.tryBegin();
    gate.closeGate();
    try {
      gate.awaitIdle(new BackupControl(null), 1);
      fail("expected timeout");
    } catch (IOException expected) {
      assertEquals("WORKER_QUIESCE_TIMEOUT", expected.getMessage());
    }
    assertTrue(gate.isClosed());
    assertNull(gate.tryBegin());
    running.close();
    gate.reopen();
    WorkerQuiescence.Lease next = gate.tryBegin();
    assertNotNull(next);
    next.close();
  }
}
