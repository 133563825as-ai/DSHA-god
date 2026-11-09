package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure thread CPU delta aggregation for the test-package performance probe. */
public final class ThreadCpuStats {
  public static final int TOP_LIMIT = 25;
  public static final int TIMELINE_LIMIT = 128;

  /** Parsed /proc/[pid]/task/[tid]/stat values. */
  public static final class ParsedStat {
    public final String name;
    public final long userTicks;
    public final long systemTicks;

    private ParsedStat(String name, long userTicks, long systemTicks) {
      this.name = name;
      this.userTicks = userTicks;
      this.systemTicks = systemTicks;
    }

    public long totalTicks() {
      return userTicks + systemTicks;
    }
  }

  public static final class ThreadKey {
    public final int pid;
    public final String process;
    public final int tid;
    public final String name;

    public ThreadKey(int pid, String process, int tid, String name) {
      this.pid = pid;
      this.process = process == null ? "" : process;
      this.tid = tid;
      this.name = name == null ? "" : name;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof ThreadKey)) return false;
      ThreadKey that = (ThreadKey) other;
      return pid == that.pid && tid == that.tid;
    }

    @Override
    public int hashCode() {
      return 31 * pid + tid;
    }
  }

  public static final class ThreadReading {
    public final ThreadKey key;
    public final long cpuTicks;

    public ThreadReading(ThreadKey key, long cpuTicks) {
      this.key = key;
      this.cpuTicks = Math.max(0, cpuTicks);
    }
  }

  public static final class ThreadTotal {
    public final ThreadKey key;
    public final double cpuMs;
    public final double cpuPctOfOneCore;
    public final double peakPctOfOneCore;

    private ThreadTotal(
        ThreadKey key, double cpuMs, double cpuPctOfOneCore, double peakPctOfOneCore) {
      this.key = key;
      this.cpuMs = cpuMs;
      this.cpuPctOfOneCore = cpuPctOfOneCore;
      this.peakPctOfOneCore = peakPctOfOneCore;
    }
  }

  public static final class NamedTotal {
    public final String process;
    public final String name;
    public final double cpuMs;
    public final double cpuPctOfOneCore;
    public final double peakPctOfOneCore;

    private NamedTotal(
        String process,
        String name,
        double cpuMs,
        double cpuPctOfOneCore,
        double peakPctOfOneCore) {
      this.process = process;
      this.name = name;
      this.cpuMs = cpuMs;
      this.cpuPctOfOneCore = cpuPctOfOneCore;
      this.peakPctOfOneCore = peakPctOfOneCore;
    }
  }

  public static final class ProcessTotal {
    public final int pid;
    public final String process;
    public final double cpuMs;
    public final int threads;

    private ProcessTotal(int pid, String process, double cpuMs, int threads) {
      this.pid = pid;
      this.process = process;
      this.cpuMs = cpuMs;
      this.threads = threads;
    }
  }

  public static final class TimelineThread {
    public final String name;
    public final String process;
    public final double pct;

    private TimelineThread(String name, String process, double pct) {
      this.name = name;
      this.process = process;
      this.pct = pct;
    }
  }

  public static final class TimelinePoint {
    public final long t;
    public final long wall;
    public final double totalPctOfOneCore;
    public final List<TimelineThread> top;

    private TimelinePoint(long t, long wall, double totalPctOfOneCore, List<TimelineThread> top) {
      this.t = t;
      this.wall = wall;
      this.totalPctOfOneCore = totalPctOfOneCore;
      this.top = Collections.unmodifiableList(top);
    }
  }

  public static final class Summary {
    public final long intervalMs;
    public final long clkTck;
    public final int samples;
    public final long unreadableProcesses;
    public final long unreadableThreads;
    public final double samplerSelfCpuMs;
    public final int cpuCount;
    public final List<ThreadTotal> top;
    public final List<NamedTotal> byName;
    public final List<ProcessTotal> processes;
    public final List<TimelinePoint> timeline;
    public final String note = "WebView 沙箱渲染进程（isolated uid）不可读，网页侧耗时见 web.loaf";

    private Summary(
        long intervalMs,
        long clkTck,
        int samples,
        long unreadableProcesses,
        long unreadableThreads,
        double samplerSelfCpuMs,
        int cpuCount,
        List<ThreadTotal> top,
        List<NamedTotal> byName,
        List<ProcessTotal> processes,
        List<TimelinePoint> timeline) {
      this.intervalMs = intervalMs;
      this.clkTck = clkTck;
      this.samples = samples;
      this.unreadableProcesses = unreadableProcesses;
      this.unreadableThreads = unreadableThreads;
      this.samplerSelfCpuMs = samplerSelfCpuMs;
      this.cpuCount = cpuCount;
      this.top = Collections.unmodifiableList(top);
      this.byName = Collections.unmodifiableList(byName);
      this.processes = Collections.unmodifiableList(processes);
      this.timeline = Collections.unmodifiableList(timeline);
    }
  }

  private static final class Accumulated {
    final ThreadKey key;
    double cpuMs;
    double peakPct;

    Accumulated(ThreadKey key) {
      this.key = key;
    }
  }

  private static final class ProcessSeen {
    final int pid;
    final String process;
    final Set<Integer> tids = new HashSet<>();

    ProcessSeen(int pid, String process) {
      this.pid = pid;
      this.process = process;
    }
  }

  private static final class NamedKey {
    final String process;
    final String name;

    NamedKey(String process, String name) {
      this.process = process;
      this.name = name;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof NamedKey)) return false;
      NamedKey that = (NamedKey) other;
      return process.equals(that.process) && name.equals(that.name);
    }

    @Override
    public int hashCode() {
      return 31 * process.hashCode() + name.hashCode();
    }
  }

  private final long clkTck;
  private final long intervalMs;
  private final int cpuCount;
  private final Map<ThreadKey, Long> previous = new HashMap<>();
  private final Map<ThreadKey, Accumulated> totals = new HashMap<>();
  private final Map<String, ProcessSeen> seenProcesses = new HashMap<>();
  private final List<TimelinePoint> timeline = new ArrayList<>();
  private long unreadableProcesses;
  private long unreadableThreads;
  private double samplerSelfCpuMs;
  private int samples;
  private long firstT = -1;
  private long lastT = -1;

  public ThreadCpuStats(long clkTck, long intervalMs, int cpuCount) {
    if (clkTck <= 0) throw new IllegalArgumentException("clkTck");
    this.clkTck = clkTck;
    this.intervalMs = Math.max(1, intervalMs);
    this.cpuCount = Math.max(1, cpuCount);
  }

  /** Parses Linux stat using the final ')' because comm itself may contain spaces and ')'. */
  public static ParsedStat parseStatLine(String line) {
    if (line == null) throw new IllegalArgumentException("stat line");
    int open = line.indexOf('(');
    int close = line.lastIndexOf(')');
    if (open < 0 || close <= open || close + 1 >= line.length())
      throw new IllegalArgumentException("stat comm");
    String[] fields = line.substring(close + 1).trim().split("\\s+");
    // fields[0] is field 3 (state), hence utime/stime are fields 14/15 at offsets 11/12.
    if (fields.length <= 12) throw new IllegalArgumentException("stat fields");
    try {
      return new ParsedStat(
          line.substring(open + 1, close), Long.parseLong(fields[11]), Long.parseLong(fields[12]));
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("stat cpu ticks", invalid);
    }
  }

  /**
   * Parses field 4 (ppid) of the same Linux stat line accepted by {@link #parseStatLine}. Added for
   * the shell-level probe: it needs the process tree, and the comm field may contain spaces/')'.
   */
  public static long parseStatPpid(String line) {
    if (line == null) throw new IllegalArgumentException("stat line");
    int open = line.indexOf('(');
    int close = line.lastIndexOf(')');
    if (open < 0 || close <= open || close + 1 >= line.length())
      throw new IllegalArgumentException("stat comm");
    String[] fields = line.substring(close + 1).trim().split("\\s+");
    if (fields.length <= 1) throw new IllegalArgumentException("stat fields");
    try {
      return Long.parseLong(fields[1]);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("stat ppid", invalid);
    }
  }

  /** Adds one process/thread snapshot. A new or returning thread establishes a baseline only. */
  public synchronized void addSample(long t, long wall, Iterable<ThreadReading> current) {
    if (firstT < 0) firstT = t;
    long elapsed = lastT < 0 ? 0 : Math.max(1, t - lastT);
    Map<ThreadKey, Long> next = new HashMap<>();
    Map<ThreadKey, Double> intervalCpuMs = new HashMap<>();
    double totalIntervalMs = 0;
    if (current != null) {
      for (ThreadReading reading : current) {
        if (reading == null || reading.key == null) continue;
        long ticks = reading.cpuTicks;
        next.put(reading.key, ticks);
        String processKey = reading.key.pid + "\u0000" + reading.key.process;
        ProcessSeen seen =
            seenProcesses.computeIfAbsent(
                processKey, ignored -> new ProcessSeen(reading.key.pid, reading.key.process));
        seen.tids.add(reading.key.tid);
        Accumulated accumulated = totals.get(reading.key);
        if (accumulated == null) {
          accumulated = new Accumulated(reading.key);
          totals.put(reading.key, accumulated);
        }
        Long old = previous.get(reading.key);
        if (old == null || samples == 0) continue;
        long delta = Math.max(0, ticks - old);
        double cpuMs = delta * 1000.0 / clkTck;
        accumulated.cpuMs += cpuMs;
        double pct = cpuMs * 100.0 / elapsed;
        accumulated.peakPct = Math.max(accumulated.peakPct, pct);
        intervalCpuMs.put(reading.key, cpuMs);
        totalIntervalMs += cpuMs;
      }
    }
    previous.clear();
    previous.putAll(next);
    samples++;
    lastT = t;

    List<Map.Entry<ThreadKey, Double>> intervalOrder = new ArrayList<>(intervalCpuMs.entrySet());
    intervalOrder.sort(
        (a, b) -> {
          int cpu = Double.compare(b.getValue(), a.getValue());
          if (cpu != 0) return cpu;
          int pid = Integer.compare(a.getKey().pid, b.getKey().pid);
          return pid != 0 ? pid : Integer.compare(a.getKey().tid, b.getKey().tid);
        });
    List<TimelineThread> intervalTop = new ArrayList<>();
    for (int i = 0; i < Math.min(5, intervalOrder.size()); i++) {
      Map.Entry<ThreadKey, Double> entry = intervalOrder.get(i);
      intervalTop.add(
          new TimelineThread(
              entry.getKey().name,
              entry.getKey().process,
              round(entry.getValue() * 100.0 / elapsed, 2)));
    }
    if (samples > 1) {
      if (timeline.size() == TIMELINE_LIMIT) timeline.remove(0);
      timeline.add(
          new TimelinePoint(t, wall, round(totalIntervalMs * 100.0 / elapsed, 2), intervalTop));
    }
  }

  public synchronized void unreadableProcess() {
    unreadableProcesses++;
  }

  public synchronized void unreadableThread() {
    unreadableThreads++;
  }

  public synchronized void addSamplerSelfCpuTicks(long ticks) {
    if (ticks > 0) samplerSelfCpuMs += ticks * 1000.0 / clkTck;
  }

  public synchronized Summary summarize() {
    long duration = firstT < 0 || lastT <= firstT ? 0 : lastT - firstT;
    return summarize(duration);
  }

  public synchronized Summary summarize(long samplingDurationMs) {
    double denominatorMs = Math.max(1, samplingDurationMs);
    List<ThreadTotal> top = new ArrayList<>();
    for (Accumulated value : totals.values()) {
      if (value.cpuMs <= 0) continue;
      top.add(
          new ThreadTotal(
              value.key,
              round(value.cpuMs, 2),
              round(value.cpuMs * 100.0 / denominatorMs, 2),
              round(value.peakPct, 2)));
    }
    top.sort(THREAD_ORDER);
    if (top.size() > TOP_LIMIT) top = new ArrayList<>(top.subList(0, TOP_LIMIT));

    Map<NamedKey, double[]> names = new HashMap<>();
    Map<String, double[]> processTotals = new HashMap<>();
    for (String processKey : seenProcesses.keySet()) processTotals.put(processKey, new double[1]);
    for (Accumulated value : totals.values()) {
      if (value.cpuMs <= 0) continue;
      NamedKey name = new NamedKey(value.key.process, value.key.name);
      double[] named = names.computeIfAbsent(name, ignored -> new double[2]);
      named[0] += value.cpuMs;
      named[1] = Math.max(named[1], value.peakPct);
      String processKey = value.key.pid + "\u0000" + value.key.process;
      double[] proc = processTotals.computeIfAbsent(processKey, ignored -> new double[1]);
      proc[0] += value.cpuMs;
    }
    List<NamedTotal> byName = new ArrayList<>();
    for (Map.Entry<NamedKey, double[]> entry : names.entrySet()) {
      double cpuMs = entry.getValue()[0];
      byName.add(
          new NamedTotal(
              entry.getKey().process,
              entry.getKey().name,
              round(cpuMs, 2),
              round(cpuMs * 100.0 / denominatorMs, 2),
              round(entry.getValue()[1], 2)));
    }
    byName.sort(NAMED_ORDER);
    if (byName.size() > TOP_LIMIT) byName = new ArrayList<>(byName.subList(0, TOP_LIMIT));

    List<ProcessTotal> processes = new ArrayList<>();
    for (Map.Entry<String, double[]> entry : processTotals.entrySet()) {
      int split = entry.getKey().indexOf('\u0000');
      String pidText = entry.getKey().substring(0, split);
      String process = entry.getKey().substring(split + 1);
      int pid;
      try {
        pid = Integer.parseInt(pidText);
      } catch (NumberFormatException ignored) {
        continue;
      }
      processes.add(
          new ProcessTotal(
              pid,
              process,
              round(entry.getValue()[0], 2),
              seenProcesses.containsKey(entry.getKey())
                  ? seenProcesses.get(entry.getKey()).tids.size()
                  : 0));
    }
    processes.sort(PROCESS_ORDER);
    return new Summary(
        intervalMs,
        clkTck,
        samples,
        unreadableProcesses,
        unreadableThreads,
        round(samplerSelfCpuMs, 2),
        cpuCount,
        top,
        byName,
        processes,
        new ArrayList<>(timeline));
  }

  private static final Comparator<ThreadTotal> THREAD_ORDER =
      (a, b) -> {
        int cpu = Double.compare(b.cpuMs, a.cpuMs);
        if (cpu != 0) return cpu;
        int pid = Integer.compare(a.key.pid, b.key.pid);
        return pid != 0 ? pid : Integer.compare(a.key.tid, b.key.tid);
      };

  private static final Comparator<NamedTotal> NAMED_ORDER =
      (a, b) -> {
        int cpu = Double.compare(b.cpuMs, a.cpuMs);
        if (cpu != 0) return cpu;
        int process = a.process.compareTo(b.process);
        return process != 0 ? process : a.name.compareTo(b.name);
      };

  private static final Comparator<ProcessTotal> PROCESS_ORDER =
      (a, b) -> {
        int cpu = Double.compare(b.cpuMs, a.cpuMs);
        return cpu != 0 ? cpu : Integer.compare(a.pid, b.pid);
      };

  private static double round(double value, int digits) {
    double scale = Math.pow(10, digits);
    return Math.round(value * scale) / scale;
  }
}
