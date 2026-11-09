package com.deepseekharness.app.util;

import java.util.Arrays;

/**
 * 测试包性能采样的逐帧统计：只接收原生 FrameMetrics 已拆好的纳秒数值，不 import Android API。
 *
 * <p>采样窗口固定上限，超出的帧只计数不保存，避免长时间录制占用内存。add 在监听线程调用，summarize 在主线程调用，二者由本类内部加锁互斥。
 */
public final class FrameStats {
  /** FrameMetrics 各阶段，顺序即 JSON 输出顺序。 */
  public static final String[] STAGES = {
    "unknownDelay",
    "input",
    "animation",
    "layoutMeasure",
    "draw",
    "sync",
    "commandIssue",
    "swapBuffers",
    "gpu"
  };

  private static final long MS = 1_000_000L;
  private final int capacity;
  private final long[] vsync;
  private final long[] total;
  private final long[] budget;
  private final long[][] stages;
  private int count;
  private long overflow;
  private long dropped;
  private long firstDrawFrames;

  public FrameStats(int capacity) {
    if (capacity <= 0) throw new IllegalArgumentException("capacity");
    this.capacity = capacity;
    vsync = new long[capacity];
    total = new long[capacity];
    budget = new long[capacity];
    stages = new long[STAGES.length][capacity];
  }

  /**
   * 记录一帧。
   *
   * @param intendedVsyncNanos 该帧预定 vsync（System.nanoTime 时钟）
   * @param totalNanos 帧总耗时
   * @param budgetNanos 本帧预算（DEADLINE 或按刷新率推算）；未知传 0
   * @param stageNanos 与 {@link #STAGES} 对应的阶段耗时，不可用的阶段传负数
   * @param firstDraw 是否首帧（窗口首次绘制不计入卡顿判断，但仍计数）
   */
  public synchronized void add(
      long intendedVsyncNanos,
      long totalNanos,
      long budgetNanos,
      long[] stageNanos,
      boolean firstDraw) {
    if (firstDraw) {
      firstDrawFrames++;
      return;
    }
    if (count >= capacity) {
      overflow++;
      return;
    }
    vsync[count] = intendedVsyncNanos;
    total[count] = Math.max(0, totalNanos);
    budget[count] = Math.max(0, budgetNanos);
    for (int i = 0; i < STAGES.length; i++)
      stages[i][count] = stageNanos != null && i < stageNanos.length ? stageNanos[i] : -1;
    count++;
  }

  /** 系统因监听线程来不及而丢弃的回调数量（FrameMetrics dropCountSinceLastInvocation 之和）。 */
  public synchronized void dropped(int value) {
    if (value > 0) dropped += value;
  }

  public static final class Summary {
    public long frames;
    public long overflowFrames;
    public long droppedReports;
    public long firstDrawFrames;
    public double windowSeconds;

    /** 帧数 ÷ 整个采样窗口时长（静止不出帧时会偏低，这是真实的“平均出帧率”）。 */
    public double fpsOverWindow;

    /** 只计有帧的秒：帧数 ÷ 活跃秒数，更接近“动起来时”的帧率。 */
    public double fpsActiveSeconds;

    public double p50Ms;
    public double p90Ms;
    public double p95Ms;
    public double p99Ms;
    public double maxMs;
    public double meanMs;

    /** 中位帧预算（毫秒）及其折算的刷新率。 */
    public double budgetMs;

    public double budgetHz;
    public long overBudgetFrames;
    public double overBudgetRatio;

    /** 超过 2 倍预算的帧（明显卡顿）。 */
    public long over2xBudgetFrames;

    public int[] framesPerSecond = new int[0];

    /** 每 250 毫秒桶中的帧数，按 intended vsync 相对采样开始归桶。 */
    public int[] framesPer250ms = new int[0];

    /** 每阶段 {mean, p95, max}（毫秒）；该阶段全部不可用时为 null。 */
    public double[][] stageMs = new double[STAGES.length][];

    /** 逐帧总耗时（0.01 毫秒精度），供离线复核。 */
    public double[] totalMsPerFrame = new double[0];

    /**
     * 相邻两帧预定 vsync 的间隔折算成"等效帧率"后的分布：{@code [≥100, 75–100, 50–75, 25–50, 10–25, <10]}（Hz）
     * 对应帧数。用来看画面出帧节奏：流式输出时是不是一会儿 120、一会儿 60/30。
     */
    public int[] gapHzBuckets = new int[GAP_BUCKET_LABELS.length];

    /** 超过 50 毫秒没有出帧的间隙次数（画面"停住"再动起来）。 */
    public int stallGaps;

    /** 逐个间隔（毫秒，0.1 精度），与 totalMsPerFrame 对齐（第一帧为 0）。 */
    public double[] gapMsPerFrame = new double[0];
  }

  /** {@link Summary#gapHzBuckets} 各档名称。 */
  public static final String[] GAP_BUCKET_LABELS = {
    ">=100", "75-100", "50-75", "25-50", "10-25", "<10"
  };

  /**
   * @param windowStartNanos 采样开始（System.nanoTime）
   * @param windowEndNanos 采样结束
   * @param fallbackBudgetNanos 帧无 DEADLINE 时使用的预算（按当前显示模式算）
   */
  public synchronized Summary summarize(
      long windowStartNanos, long windowEndNanos, long fallbackBudgetNanos) {
    Summary s = new Summary();
    s.frames = count;
    s.overflowFrames = overflow;
    s.droppedReports = dropped;
    s.firstDrawFrames = firstDrawFrames;
    long window = Math.max(0, windowEndNanos - windowStartNanos);
    s.windowSeconds = round(window / 1e9, 3);
    int seconds = (int) Math.max(1, (window + 999_999_999L) / 1_000_000_000L);
    s.framesPerSecond = new int[seconds];
    int quarterSeconds = (int) Math.max(1, (window + 249_999_999L) / 250_000_000L);
    s.framesPer250ms = new int[quarterSeconds];
    if (count == 0) return s;

    long[] sorted = Arrays.copyOf(total, count);
    Arrays.sort(sorted);
    s.p50Ms = ms(percentile(sorted, 50));
    s.p90Ms = ms(percentile(sorted, 90));
    s.p95Ms = ms(percentile(sorted, 95));
    s.p99Ms = ms(percentile(sorted, 99));
    s.maxMs = ms(sorted[count - 1]);
    double sum = 0;
    for (int i = 0; i < count; i++) sum += total[i];
    s.meanMs = round(sum / count / MS, 3);

    long[] budgets = new long[count];
    int known = 0;
    for (int i = 0; i < count; i++) {
      long b = budget[i] > 0 ? budget[i] : fallbackBudgetNanos;
      if (b > 0) budgets[known++] = b;
    }
    if (known > 0) {
      long[] b = Arrays.copyOf(budgets, known);
      Arrays.sort(b);
      long median = percentile(b, 50);
      s.budgetMs = ms(median);
      s.budgetHz = round(1e9 / median, 1);
    }
    for (int i = 0; i < count; i++) {
      long b = budget[i] > 0 ? budget[i] : fallbackBudgetNanos;
      if (b <= 0) continue;
      if (total[i] > b) s.overBudgetFrames++;
      if (total[i] > 2 * b) s.over2xBudgetFrames++;
    }
    s.overBudgetRatio = round((double) s.overBudgetFrames / count, 4);

    int active = 0;
    for (int i = 0; i < count; i++) {
      long offset = vsync[i] - windowStartNanos;
      int slot = (int) Math.min(seconds - 1, Math.max(0, offset / 1_000_000_000L));
      s.framesPerSecond[slot]++;
      int quarterSlot = (int) Math.min(quarterSeconds - 1, Math.max(0, offset / 250_000_000L));
      s.framesPer250ms[quarterSlot]++;
    }
    for (int value : s.framesPerSecond) if (value > 0) active++;
    s.fpsOverWindow = window > 0 ? round(count * 1e9 / window, 2) : 0;
    s.fpsActiveSeconds = active > 0 ? round((double) count / active, 2) : 0;

    for (int stage = 0; stage < STAGES.length; stage++) {
      long[] values = new long[count];
      int n = 0;
      for (int i = 0; i < count; i++) if (stages[stage][i] >= 0) values[n++] = stages[stage][i];
      if (n == 0) continue;
      long[] v = Arrays.copyOf(values, n);
      Arrays.sort(v);
      double stageSum = 0;
      for (long value : v) stageSum += value;
      s.stageMs[stage] =
          new double[] {round(stageSum / n / MS, 3), ms(percentile(v, 95)), ms(v[n - 1])};
    }
    s.totalMsPerFrame = new double[count];
    for (int i = 0; i < count; i++) s.totalMsPerFrame[i] = round(total[i] / (double) MS, 2);
    long[] order = Arrays.copyOf(vsync, count);
    Arrays.sort(order);
    s.gapMsPerFrame = new double[count];
    for (int i = 1; i < count; i++) {
      long gap = order[i] - order[i - 1];
      if (gap <= 0) continue;
      s.gapMsPerFrame[i] = round(gap / (double) MS, 1);
      s.gapHzBuckets[gapBucket(1e9 / gap)]++;
      if (gap > 50 * MS) s.stallGaps++;
    }
    return s;
  }

  static int gapBucket(double hz) {
    if (hz >= 100) return 0;
    if (hz >= 75) return 1;
    if (hz >= 50) return 2;
    if (hz >= 25) return 3;
    if (hz >= 10) return 4;
    return 5;
  }

  /** 最近秩法百分位：已排序数组，p 取 0–100。 */
  static long percentile(long[] sorted, double p) {
    if (sorted.length == 0) return 0;
    int rank = (int) Math.ceil(p / 100.0 * sorted.length);
    return sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))];
  }

  /** 按刷新率推算单帧预算（纳秒）；刷新率无效时返回 0。 */
  public static long budgetForRefreshRate(float hz) {
    return hz > 1f && !Float.isNaN(hz) && !Float.isInfinite(hz) ? Math.round(1e9 / hz) : 0;
  }

  private static double ms(long nanos) {
    return round(nanos / (double) MS, 3);
  }

  private static double round(double value, int digits) {
    double scale = Math.pow(10, digits);
    return Math.round(value * scale) / scale;
  }
}
