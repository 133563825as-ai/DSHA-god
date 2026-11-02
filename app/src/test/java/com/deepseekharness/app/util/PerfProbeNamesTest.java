package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PerfProbeNamesTest {
  @Test
  public void fileNameCarriesUtcTimeAndMobileState() {
    // 2026-11-02T13:35:36Z
    long time = 1793626536000L;
    assertEquals("dsha-perf-20261102-133536-mobile-on.json", PerfProbeNames.fileName(time, true));
    assertEquals("dsha-perf-20261102-133536-mobile-off.json", PerfProbeNames.fileName(time, false));
    assertEquals(
        "dsha-perf-20261102-133536-mobile-unknown.json", PerfProbeNames.fileName(time, null));
  }

  @Test
  public void onlyJsonBooleansCountAsKnownState() {
    assertEquals(Boolean.TRUE, PerfProbeNames.parseLoaded(Boolean.TRUE));
    assertEquals(Boolean.FALSE, PerfProbeNames.parseLoaded(Boolean.FALSE));
    assertNull(PerfProbeNames.parseLoaded("true"));
    assertNull(PerfProbeNames.parseLoaded(1));
    assertNull(PerfProbeNames.parseLoaded(null));
  }
}
