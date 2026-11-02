package com.deepseekharness.app.util;

/**
 * 测试版留痕文字：测试版号 + 修改与发行者署名。
 *
 * <p>只有测试包的 BuildConfig 带署名；正式包署名为空，这里返回空串，界面什么都不显示。纯逻辑，不 import Android API。
 */
public final class TestBuildMark {
  private TestBuildMark() {}

  /**
   * @param signature 署名（正式包为空）
   * @param build 测试版号，如 "t1"；可为空
   * @param chinese 是否用中文
   */
  public static String text(String signature, String build, boolean chinese) {
    if (signature == null || signature.isEmpty()) return "";
    String label = build == null || build.isEmpty() ? "" : " " + build;
    return chinese
        ? "测试版" + label + " · 修改与发行：" + signature
        : "Test build" + label + " · Modified and distributed by " + signature;
  }
}
