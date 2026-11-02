package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TestBuildMarkTest {
  @Test
  public void releaseBuildShowsNothing() {
    assertEquals("", TestBuildMark.text("", "t1", true));
    assertEquals("", TestBuildMark.text(null, "", false));
  }

  @Test
  public void testBuildShowsNumberAndSignature() {
    assertEquals("测试版 t1 · 修改与发行：南街小一", TestBuildMark.text("南街小一", "t1", true));
    assertEquals(
        "Test build t1 · Modified and distributed by 南街小一",
        TestBuildMark.text("南街小一", "t1", false));
    assertEquals("测试版 · 修改与发行：南街小一", TestBuildMark.text("南街小一", "", true));
  }
}
