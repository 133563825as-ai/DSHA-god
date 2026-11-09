package com.deepseekharness.app.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次壳层采样（默认 1 Hz）的不可变快照。
 *
 * <p>纯 Java 值对象：不读设备、不分配共享状态，便于纯 JVM 单测。壳自身字段来自应用进程，{@link #processes} 是同 UID 进程树（含本进程，{@link
 * ProcInfo#self} 标记）。未知值统一为 {@code -1}。
 */
public final class ProbeSample {
  /** 采样开始以来的单调毫秒（elapsedRealtime 轴，不含深睡）。 */
  public final long tMs;

  /** 采样时刻的 epoch 毫秒（墙钟）。 */
  public final long wallMs;

  /** 应用进程 utime+stime（clock ticks）；-1 表示读取失败。 */
  public final long shellCpuTicks;

  /** 应用进程 VmRSS（KB）；-1 表示读取失败。 */
  public final long shellRssKb;

  /** 应用进程线程数；-1 表示读取失败。 */
  public final int shellThreads;

  /** Java heap 已用字节（Runtime.totalMemory - freeMemory）。 */
  public final long heapUsedBytes;

  /** Java heap 上限字节（Runtime.maxMemory）。 */
  public final long heapMaxBytes;

  /** 应用进程 PSS（KB）；只在 PSS 采样拍有值，其余拍为 -1。 */
  public final long pssKb;

  /** ART GC 累计次数；-1 表示平台不提供。 */
  public final long gcCount;

  /** ART GC 累计耗时（ms）；-1 表示平台不提供。 */
  public final long gcTimeMs;

  /** 本拍采样器自身线程消耗的 clock ticks（含 /proc 扫描与格式化）；-1 表示读取失败。 */
  public final long samplerCpuTicks;

  /** 同一 UID 的进程快照，按 pid 升序；含本进程。 */
  public final List<ProcInfo> processes;

  private ProbeSample(Builder builder) {
    tMs = builder.tMs;
    wallMs = builder.wallMs;
    shellCpuTicks = builder.shellCpuTicks;
    shellRssKb = builder.shellRssKb;
    shellThreads = builder.shellThreads;
    heapUsedBytes = builder.heapUsedBytes;
    heapMaxBytes = builder.heapMaxBytes;
    pssKb = builder.pssKb;
    gcCount = builder.gcCount;
    gcTimeMs = builder.gcTimeMs;
    samplerCpuTicks = builder.samplerCpuTicks;
    processes =
        Collections.unmodifiableList(
            new ArrayList<>(builder.processes == null ? List.of() : builder.processes));
  }

  public static Builder builder(long tMs, long wallMs) {
    return new Builder(tMs, wallMs);
  }

  /** 可变的采样装配器，只在采样线程内使用一次。 */
  public static final class Builder {
    private final long tMs;
    private final long wallMs;
    private long shellCpuTicks = -1;
    private long shellRssKb = -1;
    private int shellThreads = -1;
    private long heapUsedBytes = -1;
    private long heapMaxBytes = -1;
    private long pssKb = -1;
    private long gcCount = -1;
    private long gcTimeMs = -1;
    private long samplerCpuTicks = -1;
    private List<ProcInfo> processes;

    private Builder(long tMs, long wallMs) {
      this.tMs = Math.max(0, tMs);
      this.wallMs = wallMs;
    }

    public Builder shell(long cpuTicks, long rssKb, int threads) {
      shellCpuTicks = cpuTicks;
      shellRssKb = rssKb;
      shellThreads = threads;
      return this;
    }

    public Builder heap(long usedBytes, long maxBytes) {
      heapUsedBytes = usedBytes;
      heapMaxBytes = maxBytes;
      return this;
    }

    public Builder pssKb(long value) {
      pssKb = value;
      return this;
    }

    public Builder gc(long count, long timeMs) {
      gcCount = count;
      gcTimeMs = timeMs;
      return this;
    }

    public Builder samplerCpuTicks(long ticks) {
      samplerCpuTicks = ticks;
      return this;
    }

    public Builder processes(List<ProcInfo> values) {
      processes = values;
      return this;
    }

    public ProbeSample build() {
      return new ProbeSample(this);
    }
  }

  /** /proc 里同一 UID 的一个进程。 */
  public static final class ProcInfo {
    public final int pid;
    public final int ppid;

    /** cmdline 首段；为空时退回 stat comm 或 pid 字符串。 */
    public final String name;

    public final long cpuTicks;
    public final long rssKb;
    public final int threads;

    /** 是否为宿主应用进程（壳）。 */
    public final boolean self;

    public ProcInfo(
        int pid, int ppid, String name, long cpuTicks, long rssKb, int threads, boolean self) {
      this.pid = pid;
      this.ppid = Math.max(0, ppid);
      this.name = name == null ? "" : name;
      this.cpuTicks = cpuTicks;
      this.rssKb = rssKb;
      this.threads = threads;
      this.self = self;
    }
  }
}
