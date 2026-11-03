package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class ThreadCpuStatsTest {
  private static ThreadCpuStats.ThreadReading reading(
      int pid, String process, int tid, String name, long ticks) {
    return new ThreadCpuStats.ThreadReading(
        new ThreadCpuStats.ThreadKey(pid, process, tid, name), ticks);
  }

  @Test
  public void parsesStatCommWithSpacesAndClosingParenthesis() {
    ThreadCpuStats.ParsedStat stat =
        ThreadCpuStats.parseStatLine("123 (worker ) pool) S 1 2 3 4 5 6 7 8 9 10 11 12 120 30 0 0");
    assertEquals("worker ) pool", stat.name);
    assertEquals(11, stat.userTicks);
    assertEquals(12, stat.systemTicks);
  }

  @Test
  public void computesDeltasAndHandlesDisappearingAndNewThreads() {
    ThreadCpuStats stats = new ThreadCpuStats(100, 500, 8);
    ThreadCpuStats.ThreadReading a = reading(1, "app", 11, "main", 100);
    ThreadCpuStats.ThreadReading b = reading(1, "app", 12, "worker", 50);
    stats.addSample(0, 1_700_000_000_000L, Arrays.asList(a, b));
    stats.addSample(
        500,
        1_700_000_000_500L,
        Arrays.asList(reading(1, "app", 11, "main", 150), reading(1, "app", 13, "new", 7)));
    stats.addSample(
        1000, 1_700_000_001_000L, Collections.singletonList(reading(1, "app", 11, "main", 200)));
    ThreadCpuStats.Summary summary = stats.summarize(1000);
    assertEquals(1000.0, summary.top.get(0).cpuMs, 0.01);
    assertEquals(100.0, summary.top.get(0).cpuPctOfOneCore, 0.01);
    assertEquals(100.0, summary.top.get(0).peakPctOfOneCore, 0.01);
    assertEquals(1, summary.top.size());
    assertEquals(3, summary.processes.get(0).threads);
    assertEquals(3, summary.samples);
    assertEquals(2, summary.timeline.size());
  }

  @Test
  public void combinesSameNamesAndSortsTopByCpu() {
    ThreadCpuStats stats = new ThreadCpuStats(100, 500, 4);
    stats.addSample(
        0,
        0,
        Arrays.asList(
            reading(2, "app:remote", 21, "pool", 0), reading(2, "app:remote", 22, "pool", 0)));
    stats.addSample(
        1000,
        1000,
        Arrays.asList(
            reading(2, "app:remote", 21, "pool", 40), reading(2, "app:remote", 22, "pool", 60)));
    ThreadCpuStats.Summary summary = stats.summarize(1000);
    assertEquals(2, summary.top.size());
    assertEquals(600.0, summary.top.get(0).cpuMs, 0.01);
    assertEquals(1, summary.byName.size());
    assertEquals("pool", summary.byName.get(0).name);
    assertEquals(1000.0, summary.byName.get(0).cpuMs, 0.01);
  }

  @Test
  public void capsTimelineAt128Points() {
    ThreadCpuStats stats = new ThreadCpuStats(100, 500, 2);
    for (int i = 0; i < 140; i++) {
      stats.addSample(
          i * 500L, i * 500L, Collections.singletonList(reading(1, "app", 1, "main", i * 5L)));
    }
    ThreadCpuStats.Summary summary = stats.summarize(70_000);
    assertEquals(128, summary.timeline.size());
    assertEquals(6000, summary.timeline.get(0).t);
    assertTrue(summary.timeline.get(127).t > summary.timeline.get(0).t);
  }
}
