package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class StartupHistoryReadOwnerTest {
  @Test
  public void physicalExitSerializesOwnersAndRetirement() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var exited = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    var deliveries = new AtomicInteger();
    var owner =
        new StartupHistoryReadOwner(
            () -> true,
            task -> {
              Thread thread =
                  new Thread(
                      () -> {
                        try {
                          task.run();
                        } finally {
                          exited.countDown();
                        }
                      });
              thread.setDaemon(true);
              thread.start();
            },
            Runnable::run);
    var successor = new StartupHistoryReadOwner(() -> true, Runnable::run, Runnable::run);
    assertTrue(
        owner.request(
            () -> {
              entered.countDown();
              try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException error) {
                interrupted.set(true);
                throw error;
              }
              return List.of();
            },
            (entries, failed) -> deliveries.incrementAndGet()));
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      owner.retire();
      assertFalse(owner.request(List::of, (entries, failed) -> fail("duplicate")));
      assertFalse(successor.request(List::of, (entries, failed) -> fail("cross-owner read")));
    } finally {
      release.countDown();
      assertTrue(exited.await(5, TimeUnit.SECONDS));
    }
    assertFalse(interrupted.get());
    assertEquals(0, deliveries.get());
    assertTrue(successor.request(List::of, (entries, failed) -> deliveries.incrementAndGet()));
    assertEquals(1, deliveries.get());
  }

  @Test
  public void queuedDeliveryAndFailureAreGenerationLocal() {
    var queue = new ArrayDeque<Runnable>();
    var active = new AtomicBoolean(true);
    var owner = new StartupHistoryReadOwner(active::get, Runnable::run, queue::add);
    var deliveries = new AtomicInteger();
    assertTrue(owner.request(List::of, (entries, failed) -> deliveries.incrementAndGet()));
    owner.retire();
    queue.remove().run();
    assertEquals(0, deliveries.get());
    assertTrue(
        owner.request(
            () -> {
              throw new java.io.IOException("synthetic");
            },
            (entries, failed) -> {
              assertTrue(failed);
              assertTrue(entries.isEmpty());
              deliveries.incrementAndGet();
            }));
    queue.remove().run();
    assertEquals(1, deliveries.get());
    assertTrue(owner.request(List::of, (entries, failed) -> fail("retired module")));
    active.set(false);
    queue.remove().run();
    assertFalse(owner.request(List::of, (entries, failed) -> fail("inactive")));
    active.set(true);
    assertTrue(owner.request(List::of, (entries, failed) -> deliveries.incrementAndGet()));
    queue.remove().run();
    assertEquals(2, deliveries.get());
    var workers = new ArrayDeque<Runnable>();
    var delayed = new StartupHistoryReadOwner(active::get, workers::add, Runnable::run);
    assertTrue(
        delayed.request(
            () -> {
              fail("retired before IO starts");
              return List.of();
            },
            (entries, failed) -> fail("retired delivery")));
    delayed.retire();
    workers.remove().run();
    var rejected =
        new StartupHistoryReadOwner(
            () -> true,
            task -> {
              throw new java.util.concurrent.RejectedExecutionException();
            },
            Runnable::run);
    assertTrue(
        rejected.request(
            List::of,
            (entries, failed) -> {
              assertTrue(failed);
              deliveries.incrementAndGet();
            }));
    assertEquals(3, deliveries.get());
    assertTrue(owner.request(List::of, (entries, failed) -> deliveries.incrementAndGet()));
    queue.remove().run();
    assertEquals(4, deliveries.get());
  }
}
