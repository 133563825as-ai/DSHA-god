package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FrameRatePolicyTest {
  @Test
  public void selectsConfigured120Hz() {
    assertEquals(
        120f, FrameRatePolicy.select(false, 120f, new float[] {60f, 90f, 120f, 144f}), 0.01f);
  }

  @Test
  public void neverExceedsUserPeak() {
    assertEquals(90f, FrameRatePolicy.select(false, 90f, new float[] {60f, 90f, 120f}), 0.01f);
    assertEquals(0f, FrameRatePolicy.select(false, 60f, new float[] {60f, 90f, 120f}), 0.01f);
  }

  @Test
  public void missingSettingUsesConservative120HzCap() {
    assertEquals(120f, FrameRatePolicy.select(false, 0f, new float[] {60f, 120f, 144f}), 0.01f);
  }

  @Test
  public void powerSaverLeavesPolicyToSystem() {
    assertEquals(0f, FrameRatePolicy.select(true, 120f, new float[] {60f, 120f}), 0.01f);
  }

  @Test
  public void ignoresInvalidAndOtherResolutionInputRates() {
    assertEquals(
        119.88f,
        FrameRatePolicy.select(false, Float.NaN, new float[] {Float.NaN, -1f, 60f, 119.88f}),
        0.01f);
  }

  @Test
  public void modeForPicksMatchingDisplayMode() {
    int[] ids = {1, 2, 3};
    float[] rates = {60f, 90f, 120f};
    org.junit.Assert.assertEquals(3, FrameRatePolicy.modeFor(120f, ids, rates));
    org.junit.Assert.assertEquals(2, FrameRatePolicy.modeFor(90.2f, ids, rates));
    // 不选高刷（0）或没有接近的模式时不指定。
    org.junit.Assert.assertEquals(0, FrameRatePolicy.modeFor(0f, ids, rates));
    org.junit.Assert.assertEquals(0, FrameRatePolicy.modeFor(144f, ids, rates));
    org.junit.Assert.assertEquals(0, FrameRatePolicy.modeFor(120f, ids, new float[] {60f}));
  }
}
