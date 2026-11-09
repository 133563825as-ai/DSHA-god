package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class NativePluginPlanTest {
  private static final List<String> BUILTINS =
      List.of("dsh-web-mobile", "dsh-computer-use-android");

  @Test
  public void freshProfileEnablesEveryBuiltinAfterOfficialBundles() {
    NativePluginPlan plan = NativePluginPlan.of(List.of(), BUILTINS, Map.of(), Set.of());
    assertEquals(
        List.of(
            "@deepseek-ai/dsh-base",
            "@deepseek-ai/dsh-web-app",
            "dsh-web-mobile",
            "dsh-computer-use-android"),
        plan.bundles);
    assertEquals(Set.of("dsh-web-mobile", "dsh-computer-use-android"), plan.link);
    assertTrue(plan.unlink.isEmpty());
    assertEquals(Set.of("dsh-web-mobile", "dsh-computer-use-android"), plan.registered);
  }

  @Test
  public void markersDisableAndChooseLinkHandling() {
    NativePluginPlan plan =
        NativePluginPlan.of(
            List.of("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", "dsh-web-mobile"),
            BUILTINS,
            Map.of(
                "dsh-web-mobile", NativePluginPlan.Marker.WEB_DISABLED,
                "dsh-computer-use-android", NativePluginPlan.Marker.NATIVE_DISABLED),
            Set.of());
    assertEquals(NativePluginPlan.OFFICIAL, plan.bundles);
    // Web 页关闭保留链接（开启时要读它）；原生页关闭摘掉链接。
    assertTrue(plan.link.contains("dsh-web-mobile"));
    assertTrue(plan.unlink.contains("dsh-computer-use-android"));
    assertFalse(plan.link.contains("dsh-computer-use-android"));
  }

  @Test
  public void userRemovalIsRespectedAndUserBundlesKept() {
    // 登记过的 computer-use 被用户移出 bundles：不再加回；用户自己的插件保留原位。
    NativePluginPlan plan =
        NativePluginPlan.of(
            List.of(
                "@deepseek-ai/dsh-base", "my-plugin", "@deepseek-ai/dsh-web-app", "dsh-web-mobile"),
            BUILTINS,
            Map.of(),
            Set.of("dsh-web-mobile", "dsh-computer-use-android"));
    assertEquals(
        List.of("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", "my-plugin", "dsh-web-mobile"),
        plan.bundles);
  }

  @Test
  public void newlyShippedBuiltinIsEnabledOnce() {
    NativePluginPlan plan =
        NativePluginPlan.of(
            List.of("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", "dsh-web-mobile"),
            BUILTINS,
            Map.of(),
            Set.of("dsh-web-mobile"));
    assertTrue(plan.bundles.contains("dsh-computer-use-android"));
  }

  @Test
  public void markerContentRules() {
    assertEquals(NativePluginPlan.Marker.NONE, NativePluginPlan.marker(false, null));
    assertEquals(NativePluginPlan.Marker.NATIVE_DISABLED, NativePluginPlan.marker(true, ""));
    assertEquals(
        NativePluginPlan.Marker.WEB_DISABLED,
        NativePluginPlan.marker(true, NativePluginPlan.WEB_DISABLED_MARKER));
    assertEquals(
        NativePluginPlan.Marker.NATIVE_DISABLED,
        NativePluginPlan.marker(true, NativePluginPlan.WEB_DISABLED_MARKER + "x"));
  }
}
