package com.deepseekharness.app.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class FrameStatsTest {
  private static final long MS = 1_000_000L;
  private static final long SEC = 1_000_000_000L;
  private static final long BUDGET_120 = 8_333_333L;

  private static long[] stages(long draw, long gpu) {
    long[] values = new long[FrameStats.STAGES.length];
    java.util.Arrays.fill(values, 0);
    values[4] = draw;
    values[8] = gpu;
    return values;
  }

  @Test
  public void emptyWindowReportsZeroFramesAndSecondSlots() {
    FrameStats stats = new FrameStats(16);
    FrameStats.Summary s = stats.summarize(0, 2 * SEC + 1, BUDGET_120);
    assertEquals(0, s.frames);
    assertEquals(0.0, s.fpsOverWindow, 0);
    assertArrayEquals(new int[] {0, 0, 0}, s.framesPerSecond);
    assertNull(s.stageMs[0]);
  }

  @Test
  public void steady120HzWithOneJankFrame() {
    FrameStats stats = new FrameStats(1024);
    long start = 5 * SEC;
    // 1 秒 120 帧，每帧 4ms；第 60 帧 20ms（超过 2 倍预算）。
    for (int i = 0; i < 120; i++) {
      long total = i == 60 ? 20 * MS : 4 * MS;
      stats.add(start + i * BUDGET_120, total, BUDGET_120, stages(2 * MS, MS), false);
    }
    FrameStats.Summary s = stats.summarize(start, start + SEC, 0);
    assertEquals(120, s.frames);
    assertEquals(120.0, s.fpsOverWindow, 0.01);
    assertEquals(120.0, s.fpsActiveSeconds, 0.01);
    assertEquals(1, s.overBudgetFrames);
    assertEquals(1, s.over2xBudgetFrames);
    assertEquals(0.0083, s.overBudgetRatio, 0.0001);
    assertEquals(4.0, s.p50Ms, 0);
    assertEquals(4.0, s.p99Ms, 0);
    assertEquals(20.0, s.maxMs, 0);
    assertEquals(8.333, s.budgetMs, 0.001);
    assertEquals(120.0, s.budgetHz, 0.1);
    assertArrayEquals(new int[] {120}, s.framesPerSecond);
    assertArrayEquals(new int[] {31, 30, 30, 29}, s.framesPer250ms);
    assertEquals(2.0, s.stageMs[4][0], 0);
    assertEquals(1.0, s.stageMs[8][2], 0);
    assertEquals(120, s.totalMsPerFrame.length);
    assertEquals(20.0, s.totalMsPerFrame[60], 0);
  }

  @Test
  public void idleSecondLowersWindowFpsButNotActiveFps() {
    FrameStats stats = new FrameStats(1024);
    for (int i = 0; i < 60; i++) stats.add(i * (SEC / 60), 5 * MS, 0, null, false);
    FrameStats.Summary s = stats.summarize(0, 2 * SEC, BUDGET_120);
    assertArrayEquals(new int[] {60, 0}, s.framesPerSecond);
    assertEquals(30.0, s.fpsOverWindow, 0.01);
    assertEquals(60.0, s.fpsActiveSeconds, 0.01);
    // 无逐帧 DEADLINE 时用回退预算判断；null 阶段视为不可用。
    assertEquals(120.0, s.budgetHz, 0.1);
    assertEquals(0, s.overBudgetFrames);
    assertNull(s.stageMs[4]);
  }

  @Test
  public void firstDrawAndOverflowAreCountedNotStored() {
    FrameStats stats = new FrameStats(2);
    stats.add(0, 100 * MS, BUDGET_120, null, true);
    stats.add(1, MS, BUDGET_120, null, false);
    stats.add(2, MS, BUDGET_120, null, false);
    stats.add(3, MS, BUDGET_120, null, false);
    stats.dropped(3);
    stats.dropped(-1);
    FrameStats.Summary s = stats.summarize(0, SEC, 0);
    assertEquals(2, s.frames);
    assertEquals(1, s.firstDrawFrames);
    assertEquals(1, s.overflowFrames);
    assertEquals(3, s.droppedReports);
    assertEquals(1.0, s.maxMs, 0);
  }

  @Test
  public void framesBeforeWindowOrAfterEndClampIntoEdgeSeconds() {
    FrameStats stats = new FrameStats(8);
    stats.add(-SEC, MS, BUDGET_120, null, false);
    stats.add(5 * SEC, MS, BUDGET_120, null, false);
    FrameStats.Summary s = stats.summarize(0, 2 * SEC, 0);
    assertArrayEquals(new int[] {1, 1}, s.framesPerSecond);
  }

  @Test
  public void percentileUsesNearestRank() {
    long[] values = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    assertEquals(5, FrameStats.percentile(values, 50));
    assertEquals(9, FrameStats.percentile(values, 90));
    assertEquals(10, FrameStats.percentile(values, 99));
    assertEquals(1, FrameStats.percentile(values, 0));
    assertEquals(0, FrameStats.percentile(new long[0], 50));
  }

  @Test
  public void budgetForRefreshRateRejectsInvalidRates() {
    assertEquals(8_333_333L, FrameStats.budgetForRefreshRate(120f));
    assertEquals(16_666_667L, FrameStats.budgetForRefreshRate(60f));
    assertEquals(0, FrameStats.budgetForRefreshRate(0f));
    assertEquals(0, FrameStats.budgetForRefreshRate(Float.NaN));
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsEmptyCapacity() {
    new FrameStats(0);
  }

  @Test
  public void gapHistogramShowsCadenceSwitches() {
    FrameStats stats = new FrameStats(16);
    long t = 0;
    long[] gaps = {8_333_333L, 8_333_333L, 16_666_667L, 33_333_333L, 120_000_000L};
    stats.add(t, 1_000_000L, 8_333_333L, new long[9], false);
    for (long gap : gaps) {
      t += gap;
      stats.add(t, 1_000_000L, 8_333_333L, new long[9], false);
    }
    FrameStats.Summary s = stats.summarize(0, t + 1, 8_333_333L);
    // 120,120 → ≥100；60 → 50–75；30 → 25–50；约 8 Hz → <10。
    org.junit.Assert.assertArrayEquals(new int[] {2, 0, 1, 1, 0, 1}, s.gapHzBuckets);
    org.junit.Assert.assertEquals(1, s.stallGaps);
    org.junit.Assert.assertEquals(0.0, s.gapMsPerFrame[0], 0);
    org.junit.Assert.assertEquals(120.0, s.gapMsPerFrame[5], 0.05);
  }
}
