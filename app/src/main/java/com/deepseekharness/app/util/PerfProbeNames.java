package com.deepseekharness.app.util;

import java.util.Locale;

/** 测试包性能采样文件名：dsha-perf-&lt;UTC 时间&gt;-mobile-on|off|unknown.json。纯逻辑，不 import Android API。 */
public final class PerfProbeNames {
  private PerfProbeNames() {}

  /** 网页侧报告的插件状态：true / false；null 表示网页数据缺失。 */
  public static String mobileState(Boolean loaded) {
    if (loaded == null) return "unknown";
    return loaded ? "on" : "off";
  }

  /**
   * @param epochMillis 采样结束时间
   * @param loaded 是否加载了 dsh-web-mobile；null 表示未知
   */
  public static String fileName(long epochMillis, Boolean loaded) {
    java.util.Calendar utc =
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), Locale.ROOT);
    utc.setTimeInMillis(epochMillis);
    String time =
        String.format(
            Locale.ROOT,
            "%04d%02d%02d-%02d%02d%02d",
            utc.get(java.util.Calendar.YEAR),
            utc.get(java.util.Calendar.MONTH) + 1,
            utc.get(java.util.Calendar.DAY_OF_MONTH),
            utc.get(java.util.Calendar.HOUR_OF_DAY),
            utc.get(java.util.Calendar.MINUTE),
            utc.get(java.util.Calendar.SECOND));
    return "dsha-perf-" + time + "-mobile-" + mobileState(loaded) + ".json";
  }

  /** 网页 stop() 结果里的 mobile.loaded 字段：只认 JSON 布尔字面量，其余视为未知。 */
  public static Boolean parseLoaded(Object value) {
    return value instanceof Boolean ? (Boolean) value : null;
  }
}
