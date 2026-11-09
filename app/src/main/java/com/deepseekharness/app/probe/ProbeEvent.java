package com.deepseekharness.app.probe;

/**
 * 时间轴上的一个离散事件（长帧/主线程阻塞、生命周期、IME）。纯 Java 值对象；detail 是单字段文本，避免导出结构再分叉。
 */
public final class ProbeEvent {
  public static final String TYPE_MAIN_BLOCK = "MAIN_BLOCK";
  public static final String TYPE_LIFECYCLE = "LIFECYCLE";
  public static final String TYPE_IME = "IME";
  public static final String TYPE_PROBE_START = "PROBE_START";
  public static final String TYPE_PROBE_STOP = "PROBE_STOP";

  /** 采样开始以来的单调毫秒。 */
  public final long tMs;

  /** epoch 毫秒（墙钟），便于与 logcat/系统事件对齐。 */
  public final long wallMs;

  public final String type;
  public final String detail;

  public ProbeEvent(long tMs, long wallMs, String type, String detail) {
    this.tMs = Math.max(0, tMs);
    this.wallMs = wallMs;
    this.type = type == null ? "" : type;
    this.detail = detail == null ? "" : detail;
  }
}
