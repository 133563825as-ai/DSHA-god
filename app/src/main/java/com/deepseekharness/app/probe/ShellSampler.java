package com.deepseekharness.app.probe;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;

import com.deepseekharness.app.util.ThreadCpuStats;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 壳级 1 Hz 采样器：后台 daemon 线程读 {@code /proc}（仅同 UID）+ JVM heap/GC + PSS，主线程上跑 500 ms 看门狗与生命周期/IME 钩子。
 *
 * <p>开销要点：进程过滤先用 {@link Os#stat} 看目录属主 UID，只有本 UID（或属主为 root 且 status 里 Uid 匹配）才读
 * status/stat/cmdline；PSS 每 5 拍一次；看门狗复用同一个 Runnable，正常拍零分配。所有未知值写 -1，由 {@link ProbeExport} 统一导出。
 */
final class ShellSampler
    implements Application.ActivityLifecycleCallbacks, ViewTreeObserver.OnGlobalLayoutListener {
  /** 采样/事件出口；由 {@link ProbeController} 写入环形缓冲。 */
  interface Sink {
    void onSample(ProbeSample sample);

    void onEvent(ProbeEvent event);
  }

  static final long SAMPLE_INTERVAL_MS = 1000L;
  static final long PSS_INTERVAL_MS = 5000L;
  static final long WATCHDOG_INTERVAL_MS = 500L;
  static final long MAIN_BLOCK_THRESHOLD_MS = 200L;
  static final int PSS_EVERY_TICKS = (int) (PSS_INTERVAL_MS / SAMPLE_INTERVAL_MS);

  /** 单拍最多记多少个进程，防止异常环境下列表失控。 */
  static final int MAX_PROCESSES = 64;

  private static final int MAX_STATUS_CHARS = 8192;
  private static final int MAX_STAT_CHARS = 4096;
  private static final int MAX_CMDLINE_CHARS = 4096;
  private static final int MAX_NAME_CHARS = 200;

  private final Context appContext;
  private final Application application;
  private final Sink sink;
  private final int appUid;
  private final int appPid;
  private final long startElapsedMs;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicBoolean running = new AtomicBoolean();
  private final Thread worker;
  private final Runnable watchdog = this::onWatchdog;

  private Activity imeActivity;
  private View imeView;
  private int lastImeBottom = -1;
  private long watchdogExpectedUptime;

  ShellSampler(Context context, Sink sink) {
    Context app = context == null ? null : context.getApplicationContext();
    appContext = app == null ? context : app;
    application = appContext instanceof Application ? (Application) appContext : null;
    this.sink = sink;
    appUid = Process.myUid();
    appPid = Process.myPid();
    startElapsedMs = SystemClock.elapsedRealtime();
    worker = new Thread(this::loop, "dsha-probe-shell");
    worker.setDaemon(true);
  }

  /** 应在主线程调用（注册生命周期回调与看门狗）。 */
  void start() {
    if (!running.compareAndSet(false, true)) return;
    if (application != null) application.registerActivityLifecycleCallbacks(this);
    scheduleWatchdog();
    worker.start();
  }

  /** 可在任意线程调用；主线程上的摘钩子会转投到主线程。 */
  void stop() {
    if (!running.getAndSet(false)) return;
    worker.interrupt();
    main.removeCallbacks(watchdog);
    Runnable detach =
        () -> {
          detachIme();
          if (application != null) application.unregisterActivityLifecycleCallbacks(this);
        };
    if (Looper.myLooper() == Looper.getMainLooper()) detach.run();
    else main.post(detach);
  }

  static long clockTicks() {
    try {
      long value = Os.sysconf(OsConstants._SC_CLK_TCK);
      return value > 0 ? value : 100;
    } catch (Exception unavailable) {
      return 100;
    }
  }

  // ---- 采样线程 ----

  private void loop() {
    long nextAt = SystemClock.elapsedRealtime();
    long tick = 0;
    while (running.get()) {
      long now = SystemClock.elapsedRealtime();
      if (now < nextAt) {
        long wait = nextAt - now;
        try {
          Thread.sleep(wait);
        } catch (InterruptedException stop) {
          break;
        }
        continue;
      }
      long selfBefore = readSelfThreadTicks();
      long tMs = Math.max(0, SystemClock.elapsedRealtime() - startElapsedMs);
      long wall = System.currentTimeMillis();
      List<ProbeSample.ProcInfo> processes = scanProcesses();
      boolean wantsPss = tick % PSS_EVERY_TICKS == 0;
      long pssKb = wantsPss ? readPssKb() : -1;
      long gcCount = runtimeStat("art.gc.gc-count");
      long gcTimeMs = runtimeStat("art.gc.gc-time");
      long selfAfter = readSelfThreadTicks();
      long samplerTicks = selfBefore >= 0 && selfAfter >= selfBefore ? selfAfter - selfBefore : -1;

      ProbeSample.Builder builder = ProbeSample.builder(tMs, wall);
      long shellCpu = -1;
      long shellRss = -1;
      int shellThreads = -1;
      for (ProbeSample.ProcInfo process : processes) {
        if (!process.self) continue;
        shellCpu = process.cpuTicks;
        shellRss = process.rssKb;
        shellThreads = process.threads;
        break;
      }
      Runtime runtime = Runtime.getRuntime();
      long heapMax = runtime.maxMemory();
      long heapUsed = runtime.totalMemory() - runtime.freeMemory();
      builder
          .shell(shellCpu, shellRss, shellThreads)
          .heap(heapUsed, heapMax)
          .pssKb(pssKb)
          .gc(gcCount, gcTimeMs)
          .samplerCpuTicks(samplerTicks)
          .processes(processes);
      if (!running.get()) break; // 停止时已扫的一轮不落缓冲
      sink.onSample(builder.build());
      tick++;
      nextAt += SAMPLE_INTERVAL_MS;
      long after = SystemClock.elapsedRealtime();
      // 采样本身超过一拍时重新对齐，不补拍（避免停止后还在"追帧"）。
      if (nextAt < after) nextAt = after;
    }
  }

  private List<ProbeSample.ProcInfo> scanProcesses() {
    List<ProbeSample.ProcInfo> result = new ArrayList<>();
    File[] dirs = new File("/proc").listFiles(File::isDirectory);
    if (dirs == null) return result;
    for (File dir : dirs) {
      if (!running.get()) break;
      String pidText = dir.getName();
      if (!isDigits(pidText)) continue;
      int pid;
      try {
        pid = Integer.parseInt(pidText);
      } catch (NumberFormatException ignored) {
        continue;
      }
      if (!sameUid(pid)) continue;
      // 上限只限制容器进程；宿主进程必须占位，否则 shell 样本会整拍缺字段。
      if (result.size() >= MAX_PROCESSES && pid != appPid) continue;
      try {
        ProcStatus status = parseStatus(readText(new File(dir, "status"), MAX_STATUS_CHARS));
        if (status.uid >= 0 && status.uid != appUid) continue;
        String statLine = readText(new File(dir, "stat"), MAX_STAT_CHARS);
        ThreadCpuStats.ParsedStat stat = ThreadCpuStats.parseStatLine(statLine);
        long ppid = ThreadCpuStats.parseStatPpid(statLine);
        String cmdline = readText(new File(dir, "cmdline"), MAX_CMDLINE_CHARS);
        String name = displayName(cmdline, stat.name, pid);
        int threads = status.threads >= 0 ? (int) status.threads : countThreads(dir);
        result.add(
            new ProbeSample.ProcInfo(
                pid, (int) ppid, name, stat.totalTicks(), status.rssKb, threads, pid == appPid));
      } catch (IOException | RuntimeException unreadable) {
        // 进程已退出或不可读：跳过这一拍。
      }
    }
    result.sort((left, right) -> Integer.compare(left.pid, right.pid));
    return result;
  }

  /** 目录属主即进程 euid；先看属主再读文件，避免碰其他应用的 /proc 内容。 */
  private boolean sameUid(int pid) {
    try {
      StructStat stat = Os.stat("/proc/" + pid);
      if (stat.st_uid == appUid) return true;
      // dumpable=0 的进程（setuid 后 exec）目录属主是 root，但 euid 仍是本 UID。
      // 只有属主为 root 时才回读 status 复核，正常路径不碰其他 UID 的文件。
      return stat.st_uid == 0 && statusUidIsApp(pid);
    } catch (ErrnoException | RuntimeException unavailable) {
      return false;
    }
  }

  private boolean statusUidIsApp(int pid) {
    try {
      ProcStatus status =
          parseStatus(readText(new File("/proc/" + pid + "/status"), MAX_STATUS_CHARS));
      return status.uid == appUid;
    } catch (IOException | RuntimeException unavailable) {
      return false;
    }
  }

  private long readSelfThreadTicks() {
    try {
      String line =
          readText(new File("/proc/self/task/" + Process.myTid() + "/stat"), MAX_STAT_CHARS);
      return ThreadCpuStats.parseStatLine(line).totalTicks();
    } catch (IOException | RuntimeException unavailable) {
      return -1;
    }
  }

  private long readPssKb() {
    if (appContext == null) return -1;
    try {
      ActivityManager manager = appContext.getSystemService(ActivityManager.class);
      if (manager == null) return -1;
      Debug.MemoryInfo[] infos = manager.getProcessMemoryInfo(new int[] {appPid});
      if (infos == null || infos.length == 0 || infos[0] == null) return -1;
      return infos[0].getTotalPss();
    } catch (RuntimeException unavailable) {
      return -1;
    }
  }

  private static long runtimeStat(String key) {
    try {
      String value = Debug.getRuntimeStat(key);
      return value == null ? -1 : Long.parseLong(value.trim());
    } catch (RuntimeException unavailable) {
      return -1;
    }
  }

  private static int countThreads(File processDir) {
    File[] tasks = new File(processDir, "task").listFiles(File::isDirectory);
    return tasks == null ? -1 : tasks.length;
  }

  // ---- 主线程看门狗与 UI 事件 ----

  private void scheduleWatchdog() {
    watchdogExpectedUptime = SystemClock.uptimeMillis() + WATCHDOG_INTERVAL_MS;
    main.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
  }

  private void onWatchdog() {
    if (!running.get()) return;
    long now = SystemClock.uptimeMillis();
    long delay = now - watchdogExpectedUptime;
    if (delay > MAIN_BLOCK_THRESHOLD_MS) {
      event(
          ProbeEvent.TYPE_MAIN_BLOCK,
          "delayMs=" + delay + " expectedAtUptimeMs=" + watchdogExpectedUptime);
    }
    if (running.get()) scheduleWatchdog();
  }

  private void event(String type, String detail) {
    sink.onEvent(new ProbeEvent(elapsedMs(), System.currentTimeMillis(), type, detail));
  }

  private long elapsedMs() {
    return Math.max(0, SystemClock.elapsedRealtime() - startElapsedMs);
  }

  @Override
  public void onGlobalLayout() {
    View view = imeView;
    if (view == null || !running.get()) return;
    WindowInsets insets = view.getRootWindowInsets();
    int imeBottom = insets == null ? 0 : insets.getInsets(WindowInsets.Type.ime()).bottom;
    if (imeBottom == lastImeBottom) return;
    lastImeBottom = imeBottom;
    event(ProbeEvent.TYPE_IME, "imeBottomPx=" + imeBottom + " contentHeightPx=" + view.getHeight());
  }

  private void attachIme(Activity activity) {
    if (activity == null || imeActivity == activity) return;
    detachIme();
    View root = activity.findViewById(android.R.id.content);
    if (root == null) return;
    root.getViewTreeObserver().addOnGlobalLayoutListener(this);
    imeActivity = activity;
    imeView = root;
    lastImeBottom = -1;
  }

  private void detachIme() {
    View view = imeView;
    imeView = null;
    imeActivity = null;
    lastImeBottom = -1;
    if (view == null) return;
    ViewTreeObserver observer = view.getViewTreeObserver();
    if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
  }

  private void lifecycle(Activity activity, String callback) {
    if (!running.get()) return;
    String label = activity == null ? "?" : activity.getClass().getSimpleName();
    event(ProbeEvent.TYPE_LIFECYCLE, label + "." + callback);
  }

  @Override
  public void onActivityCreated(Activity activity, Bundle state) {
    lifecycle(activity, "onCreate");
  }

  @Override
  public void onActivityStarted(Activity activity) {
    lifecycle(activity, "onStart");
  }

  @Override
  public void onActivityResumed(Activity activity) {
    lifecycle(activity, "onResume");
    attachIme(activity);
  }

  @Override
  public void onActivityPaused(Activity activity) {
    lifecycle(activity, "onPause");
    if (imeActivity == activity) detachIme();
  }

  @Override
  public void onActivityStopped(Activity activity) {
    lifecycle(activity, "onStop");
  }

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle state) {
    lifecycle(activity, "onSaveInstanceState");
  }

  @Override
  public void onActivityDestroyed(Activity activity) {
    lifecycle(activity, "onDestroy");
    if (imeActivity == activity) detachIme();
  }

  // ---- /proc 文本解析（小文件、1 Hz） ----

  private static final class ProcStatus {
    long uid = -1;
    long rssKb = -1;
    long threads = -1;
  }

  private static ProcStatus parseStatus(String status) {
    ProcStatus parsed = new ProcStatus();
    for (String line : status.split("\n")) {
      if (line.startsWith("Uid:")) parsed.uid = firstLong(line, 4);
      else if (line.startsWith("VmRSS:")) parsed.rssKb = firstLong(line, 6);
      else if (line.startsWith("Threads:")) parsed.threads = firstLong(line, 8);
    }
    return parsed;
  }

  private static long firstLong(String line, int offset) {
    String[] values = line.substring(offset).trim().split("\\s+");
    if (values.length == 0) return -1;
    try {
      return Long.parseLong(values[0]);
    } catch (NumberFormatException invalid) {
      return -1;
    }
  }

  private static String displayName(String cmdline, String statName, int pid) {
    int end = cmdline.indexOf('\u0000');
    String first = (end < 0 ? cmdline : cmdline.substring(0, end)).trim();
    if (!first.isEmpty())
      return first.length() > MAX_NAME_CHARS ? first.substring(0, MAX_NAME_CHARS) : first;
    if (statName != null && !statName.isEmpty()) return statName;
    return Integer.toString(pid);
  }

  private static String readText(File file, int maxChars) throws IOException {
    StringBuilder text = new StringBuilder(256);
    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
      char[] buffer = new char[512];
      int count;
      while (text.length() < maxChars && (count = reader.read(buffer)) > 0) {
        int room = Math.min(count, maxChars - text.length());
        text.append(buffer, 0, room);
      }
    }
    return text.toString();
  }

  private static boolean isDigits(String value) {
    if (value.isEmpty()) return false;
    for (int i = 0; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return false;
    return true;
  }
}
