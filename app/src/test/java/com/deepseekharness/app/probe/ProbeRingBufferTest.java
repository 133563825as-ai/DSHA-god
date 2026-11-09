package com.deepseekharness.app.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class ProbeRingBufferTest {
  private static ProbeSample sample(long tMs) {
    return ProbeSample.builder(tMs, 1_700_000_000_000L + tMs).build();
  }

  @Test
  public void defaultCapacityIsSixtySamples() {
    assertEquals(60, new ProbeRingBuffer().capacity());
  }

  @Test
  public void overflowDropsOldestAndKeepsNewestInOrder() {
    ProbeRingBuffer buffer = new ProbeRingBuffer(3);
    for (int i = 0; i < 5; i++) buffer.add(sample(i));
    assertEquals(3, buffer.size());
    List<ProbeSample> snapshot = buffer.snapshot();
    assertEquals(3, snapshot.size());
    assertEquals(2, snapshot.get(0).tMs);
    assertEquals(3, snapshot.get(1).tMs);
    assertEquals(4, snapshot.get(2).tMs);
  }

  @Test
  public void snapshotIsIndependentOfLaterWrites() {
    ProbeRingBuffer buffer = new ProbeRingBuffer(3);
    buffer.add(sample(1));
    List<ProbeSample> snapshot = buffer.snapshot();
    buffer.add(sample(2));
    assertEquals(1, snapshot.size());
    assertEquals(2, buffer.size());
  }

  @Test
  public void clearDropsHistory() {
    ProbeRingBuffer buffer = new ProbeRingBuffer(3);
    buffer.add(sample(1));
    buffer.add(sample(2));
    buffer.clear();
    assertEquals(0, buffer.size());
    assertTrue(buffer.snapshot().isEmpty());
    buffer.add(sample(3));
    assertEquals(1, buffer.size());
    assertEquals(3, buffer.snapshot().get(0).tMs);
  }

  @Test
  public void rejectsNonPositiveCapacity() {
    try {
      new ProbeRingBuffer(0);
      throw new AssertionError("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      // 容量必须为正，否则环形缓冲没有槽位。
    }
  }

  @Test
  public void concurrentWritersStayBoundedAndKeepInvariants() throws Exception {
    ProbeRingBuffer buffer = new ProbeRingBuffer(60);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    CountDownLatch start = new CountDownLatch(1);
    for (int t = 0; t < 4; t++) {
      final long offset = t * 1000L;
      pool.submit(
          () -> {
            try {
              start.await();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              return;
            }
            for (int i = 0; i < 500; i++) buffer.add(sample(offset + i));
          });
    }
    start.countDown();
    pool.shutdown();
    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    assertEquals(60, buffer.size());
    assertEquals(60, buffer.snapshot().size());
  }
}
