package com.deepseekharness.app.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class ProbeEventLogTest {
  private static ProbeEvent event(long tMs) {
    return new ProbeEvent(
        tMs, 1_700_000_000_000L + tMs, ProbeEvent.TYPE_MAIN_BLOCK, "delayMs=" + tMs);
  }

  @Test
  public void overflowDropsOldestEvent() {
    ProbeEventLog log = new ProbeEventLog(4);
    for (int i = 0; i < 6; i++) log.add(event(i));
    assertEquals(4, log.size());
    List<ProbeEvent> snapshot = log.snapshot();
    assertEquals(2, snapshot.get(0).tMs);
    assertEquals(5, snapshot.get(3).tMs);
  }

  @Test
  public void windowIsInclusiveAndKeepsOrder() {
    ProbeEventLog log = new ProbeEventLog();
    for (int i = 0; i < 10; i++) log.add(event(i));
    List<ProbeEvent> window = log.window(3, 6);
    assertEquals(4, window.size());
    assertEquals(3, window.get(0).tMs);
    assertEquals(6, window.get(3).tMs);
  }

  @Test
  public void windowHandlesReversedAndEmptyRanges() {
    ProbeEventLog log = new ProbeEventLog();
    log.add(event(5));
    assertTrue(log.window(6, 4).isEmpty());
    assertTrue(log.window(0, 4).isEmpty());
    assertEquals(1, log.window(5, 5).size());
  }

  @Test
  public void clearResetsTheWindow() {
    ProbeEventLog log = new ProbeEventLog(4);
    log.add(event(1));
    log.add(event(2));
    log.clear();
    assertEquals(0, log.size());
    assertTrue(log.window(0, 10).isEmpty());
  }
}
