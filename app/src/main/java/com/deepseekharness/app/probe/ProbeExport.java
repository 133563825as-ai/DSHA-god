package com.deepseekharness.app.probe;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 壳级探针导出：schema {@code dsha-perf-probe/3} 的纯 Java 组装器。
 *
 * <p>不依赖 Android/org.json（手写序列化），因此导出结构可在纯 JVM 单测里断言。三层结构固定为 {@code shell.samples}（逐拍壳样本）、{@code
 * container}（同 UID 非宿主进程，行带 tMs 与 shell 同一时间轴）、{@code page}（本轮留空，meta.note 说明）。
 */
public final class ProbeExport {
  public static final String SCHEMA = "dsha-perf-probe/3";
  public static final int SAMPLE_INTERVAL_MS = 1000;
  public static final int SAMPLE_CAPACITY = 60;
  public static final long PSS_INTERVAL_MS = 5000L;
  public static final String NOTE =
      "页面层（WebView 注入 JS）本轮留空：仍由 res/raw/dsha_perf_probe.js 以 dsha-perf-probe/2 单独导出，"
          + "合并到同一时间轴列为后续工作。PSS 每 5 s 采样一次，pssKb=-1 表示该拍未采样。"
          + "container 只含同一 UID 的非宿主进程，行内 tMs 与 shell 样本同一时间轴；"
          + "-1 / 空串表示读取失败或平台不提供。";

  private final Meta meta;
  private final List<ProbeSample> samples;
  private final List<ProbeEvent> events;

  public ProbeExport(Meta meta, List<ProbeSample> samples, List<ProbeEvent> events) {
    this.meta = meta == null ? new Meta() : meta;
    this.samples = List.copyOf(samples == null ? List.of() : samples);
    this.events = List.copyOf(events == null ? List.of() : events);
  }

  /** 序列化为单行 JSON；同一份输入两次导出结果一致（时间戳已固化在 meta 里）。 */
  public String toJson() {
    StringBuilder out = new StringBuilder(24 * 1024);
    out.append('{');
    stringField(out, "schema", SCHEMA);
    out.append(',');
    appendMeta(out);
    out.append(',');
    appendLayers(out);
    out.append(',');
    appendEvents(out);
    out.append('}');
    return out.toString();
  }

  private void appendMeta(StringBuilder out) {
    out.append("\"meta\":{");
    stringField(out, "generatedAt", iso(meta.generatedAtEpochMs));
    out.append(',');
    numberField(out, "generatedAtEpochMs", meta.generatedAtEpochMs);
    out.append(',');
    stringField(out, "startedAt", iso(meta.startedAtEpochMs));
    out.append(',');
    numberField(out, "startedAtEpochMs", meta.startedAtEpochMs);
    out.append(',');
    numberField(out, "uptimeMs", meta.uptimeMs);
    out.append(',');
    numberField(out, "sampleIntervalMs", meta.sampleIntervalMs);
    out.append(',');
    numberField(out, "sampleCapacity", meta.sampleCapacity);
    out.append(',');
    numberField(out, "pssIntervalMs", meta.pssIntervalMs);
    out.append(',');
    numberField(out, "clkTck", meta.clkTck);
    out.append(',');
    numberField(out, "cpuCount", meta.cpuCount);
    out.append(',');
    numberField(out, "appPid", meta.appPid);
    out.append(',');
    numberField(out, "appUid", meta.appUid);
    out.append(',');
    out.append("\"build\":{");
    stringField(out, "versionName", meta.versionName);
    out.append(',');
    numberField(out, "versionCode", meta.versionCode);
    out.append(',');
    stringField(out, "testBuild", meta.testBuild);
    out.append(',');
    booleanField(out, "debug", meta.debug);
    out.append('}');
    out.append(',');
    out.append("\"device\":{");
    stringField(out, "manufacturer", meta.manufacturer);
    out.append(',');
    stringField(out, "model", meta.model);
    out.append(',');
    stringField(out, "device", meta.device);
    out.append(',');
    numberField(out, "sdk", meta.sdk);
    out.append(',');
    stringField(out, "release", meta.release);
    out.append('}');
    out.append(',');
    numberField(out, "samples", samples.size());
    out.append(',');
    numberField(out, "events", events.size());
    out.append(',');
    decimalField(out, "samplerSelfCpuMs", meta.samplerSelfCpuMs);
    out.append(',');
    stringField(out, "note", meta.note == null || meta.note.isEmpty() ? NOTE : meta.note);
    out.append('}');
  }

  private void appendLayers(StringBuilder out) {
    out.append("\"layers\":{");
    out.append("\"shell\":{\"samples\":[");
    for (int i = 0; i < samples.size(); i++) {
      if (i > 0) out.append(',');
      ProbeSample sample = samples.get(i);
      out.append('{');
      numberField(out, "tMs", sample.tMs);
      out.append(',');
      numberField(out, "wallMs", sample.wallMs);
      out.append(',');
      numberField(out, "cpuTicks", sample.shellCpuTicks);
      out.append(',');
      numberField(out, "rssKb", sample.shellRssKb);
      out.append(',');
      numberField(out, "threads", sample.shellThreads);
      out.append(',');
      numberField(out, "heapUsedBytes", sample.heapUsedBytes);
      out.append(',');
      numberField(out, "heapMaxBytes", sample.heapMaxBytes);
      out.append(',');
      numberField(out, "pssKb", sample.pssKb);
      out.append(',');
      numberField(out, "gcCount", sample.gcCount);
      out.append(',');
      numberField(out, "gcTimeMs", sample.gcTimeMs);
      out.append(',');
      numberField(out, "samplerCpuTicks", sample.samplerCpuTicks);
      out.append('}');
    }
    out.append("]},");
    appendContainer(out);
    out.append(",\"page\":{}");
    out.append('}');
  }

  /** container 是扁平行数组：每拍每个非宿主进程一行，tMs 对齐 shell 样本。 */
  private void appendContainer(StringBuilder out) {
    out.append("\"container\":[");
    boolean first = true;
    for (ProbeSample sample : samples) {
      for (ProbeSample.ProcInfo process : sample.processes) {
        if (process == null) continue;
        if (process.self || process.pid == meta.appPid) continue;
        if (!first) out.append(',');
        first = false;
        out.append('{');
        numberField(out, "tMs", sample.tMs);
        out.append(',');
        numberField(out, "pid", process.pid);
        out.append(',');
        numberField(out, "ppid", process.ppid);
        out.append(',');
        stringField(out, "name", process.name);
        out.append(',');
        numberField(out, "cpuTicks", process.cpuTicks);
        out.append(',');
        numberField(out, "rssKb", process.rssKb);
        out.append(',');
        numberField(out, "threads", process.threads);
        out.append('}');
      }
    }
    out.append(']');
  }

  private void appendEvents(StringBuilder out) {
    out.append("\"events\":[");
    for (int i = 0; i < events.size(); i++) {
      if (i > 0) out.append(',');
      ProbeEvent event = events.get(i);
      out.append('{');
      numberField(out, "tMs", event.tMs);
      out.append(',');
      numberField(out, "wallMs", event.wallMs);
      out.append(',');
      stringField(out, "type", event.type);
      out.append(',');
      stringField(out, "detail", event.detail);
      out.append('}');
    }
    out.append(']');
  }

  private static void numberField(StringBuilder out, String name, long value) {
    out.append('"').append(name).append("\":").append(value);
  }

  private static void booleanField(StringBuilder out, String name, boolean value) {
    out.append('"').append(name).append("\":").append(value);
  }

  private static void decimalField(StringBuilder out, String name, double value) {
    double rounded = Double.isFinite(value) ? Math.round(value * 100.0) / 100.0 : 0;
    out.append('"').append(name).append("\":").append(rounded);
  }

  private static void stringField(StringBuilder out, String name, String value) {
    out.append('"').append(name).append("\":");
    quote(out, value == null ? "" : value);
  }

  private static void quote(StringBuilder out, String value) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\b':
          out.append("\\b");
          break;
        case '\f':
          out.append("\\f");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          if (c < 0x20) {
            out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            out.append(c);
          }
      }
    }
    out.append('"');
  }

  private static String iso(long epochMs) {
    if (epochMs <= 0) return "";
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    return format.format(new Date(epochMs));
  }

  /** 导出元数据；由壳层组装（Build/设备信息），纯 Java 字段让单测可以直接构造。 */
  public static final class Meta {
    public long generatedAtEpochMs;
    public long startedAtEpochMs;
    public long uptimeMs;
    public int sampleIntervalMs = SAMPLE_INTERVAL_MS;
    public int sampleCapacity = SAMPLE_CAPACITY;
    public long pssIntervalMs = PSS_INTERVAL_MS;
    public long clkTck = 100;
    public int cpuCount = 1;
    public int appPid = -1;
    public int appUid = -1;
    public double samplerSelfCpuMs;
    public String versionName = "";
    public int versionCode;
    public String testBuild = "";
    public boolean debug;
    public String manufacturer = "";
    public String model = "";
    public String device = "";
    public int sdk;
    public String release = "";
    public String note = NOTE;
  }
}
