package com.deepseekharness.app.ui;

import android.os.Process;
import android.system.Os;
import android.system.OsConstants;

import com.deepseekharness.app.util.ThreadCpuStats;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads same-UID process and thread CPU counters from procfs for the test-package probe only. */
final class ThreadCpuSampler {
  private static final long INTERVAL_MS = 500L;
  private static final long STOP_WAIT_MS = 300L;
  private final int appUid;
  private final long startNanos;
  private final long startWall;
  private final ThreadCpuStats stats;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Thread worker;

  ThreadCpuSampler(int appUid) {
    this.appUid = appUid;
    startNanos = System.nanoTime();
    startWall = System.currentTimeMillis();
    long clkTck = clockTicks();
    int cpuCount = Math.max(1, Runtime.getRuntime().availableProcessors());
    stats = new ThreadCpuStats(clkTck, INTERVAL_MS, cpuCount);
    worker = new Thread(this::loop, "dsha-perf-cpu");
    worker.setDaemon(true);
    worker.start();
  }

  /** 在主线程调用：最多等 {@link #STOP_WAIT_MS}；采样线程仍未退出时返回 null（统计对象不能并发读）。 */
  ThreadCpuStats.Summary stopAndSummarize() {
    running.set(false);
    worker.interrupt();
    try {
      worker.join(STOP_WAIT_MS);
    } catch (InterruptedException stopInterrupted) {
      Thread.currentThread().interrupt();
    }
    if (worker.isAlive()) return null;
    long durationMs = Math.max(1, (System.nanoTime() - startNanos) / 1_000_000L);
    return stats.summarize(durationMs);
  }

  private void loop() {
    long selfPrevious = -1;
    while (running.get()) {
      long selfTicksBefore = readCurrentThreadTicks();
      List<ThreadCpuStats.ThreadReading> readings = collect();
      if (!running.get()) break; // 被打断的半轮扫描不计入
      long t = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
      long wall = startWall + t;
      stats.addSample(t, wall, readings);
      long selfTicksAfter = readCurrentThreadTicks();
      if (selfTicksBefore >= 0 && selfTicksAfter >= selfTicksBefore) {
        if (selfPrevious >= 0) stats.addSamplerSelfCpuTicks(selfTicksAfter - selfPrevious);
        selfPrevious = selfTicksAfter;
      }
      try {
        Thread.sleep(INTERVAL_MS);
      } catch (InterruptedException stop) {
        if (!running.get()) break;
      }
    }
  }

  private List<ThreadCpuStats.ThreadReading> collect() {
    List<ThreadCpuStats.ThreadReading> result = new ArrayList<>();
    File proc = new File("/proc");
    File[] processes;
    try {
      processes = proc.listFiles(File::isDirectory);
      if (processes == null) throw new IOException("proc list");
    } catch (Exception unreadable) {
      stats.unreadableProcess();
      return result;
    }
    for (File processDir : processes) {
      // 停止时尽快退出，避免主线程等待一整轮 /proc 扫描。
      if (!running.get()) break;
      String pidText = processDir.getName();
      if (!isDigits(pidText)) continue;
      int pid;
      try {
        pid = Integer.parseInt(pidText);
      } catch (NumberFormatException ignored) {
        continue;
      }
      String status;
      try {
        status = readText(new File(processDir, "status"));
      } catch (Exception unreadable) {
        stats.unreadableProcess();
        continue;
      }
      if (!hasUid(status, appUid)) continue;

      String processName;
      try {
        processName = readText(new File(processDir, "cmdline")).split("\u0000", 2)[0].trim();
        if (processName.isEmpty()) processName = Integer.toString(pid);
      } catch (Exception unreadable) {
        stats.unreadableProcess();
        processName = Integer.toString(pid);
      }
      File taskDir = new File(processDir, "task");
      File[] tasks;
      try {
        tasks = taskDir.listFiles(File::isDirectory);
        if (tasks == null) throw new IOException("task list");
      } catch (Exception unreadable) {
        stats.unreadableProcess();
        continue;
      }
      for (File taskDirEntry : tasks) {
        String tidText = taskDirEntry.getName();
        if (!isDigits(tidText)) continue;
        int tid;
        try {
          tid = Integer.parseInt(tidText);
          ThreadCpuStats.ParsedStat parsed =
              ThreadCpuStats.parseStatLine(readText(new File(taskDirEntry, "stat")));
          result.add(
              new ThreadCpuStats.ThreadReading(
                  new ThreadCpuStats.ThreadKey(pid, processName, tid, parsed.name),
                  parsed.totalTicks()));
        } catch (Exception unreadable) {
          stats.unreadableThread();
        }
      }
    }
    return result;
  }

  private long readCurrentThreadTicks() {
    try {
      int tid = Process.myTid();
      ThreadCpuStats.ParsedStat parsed =
          ThreadCpuStats.parseStatLine(readText(new File("/proc/self/task/" + tid + "/stat")));
      return parsed.totalTicks();
    } catch (Exception unreadable) {
      stats.unreadableThread();
      return -1;
    }
  }

  private static boolean hasUid(String status, int uid) {
    for (String line : status.split("\\n")) {
      if (!line.startsWith("Uid:")) continue;
      String[] values = line.substring(4).trim().split("\\s+");
      if (values.length == 0) return false;
      try {
        return Integer.parseInt(values[0]) == uid;
      } catch (NumberFormatException invalid) {
        return false;
      }
    }
    return false;
  }

  private static String readText(File file) throws IOException {
    StringBuilder text = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
      char[] buffer = new char[4096];
      int count;
      while ((count = reader.read(buffer)) >= 0) text.append(buffer, 0, count);
    }
    return text.toString();
  }

  private static boolean isDigits(String value) {
    if (value.isEmpty()) return false;
    for (int i = 0; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return false;
    return true;
  }

  private static long clockTicks() {
    try {
      long value = Os.sysconf(OsConstants._SC_CLK_TCK);
      return value > 0 ? value : 100;
    } catch (Exception unavailable) {
      return 100;
    }
  }
}
