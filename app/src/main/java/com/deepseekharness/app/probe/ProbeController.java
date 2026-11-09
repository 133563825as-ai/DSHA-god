package com.deepseekharness.app.probe;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import com.deepseekharness.app.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 壳级常驻探针的单例入口：1 Hz 采样进 60 个样本的环形缓冲，导出 schema {@code dsha-perf-probe/3}。
 *
 * <p>只在测试/调试构建启用（ADR-0002“默认常驻，仅测试/调试构建”）：{@link BuildConfig#DEBUG} 为真，或 {@link
 * BuildConfig#TEST_BUILD} 为测试版号（{@code tN}）；正式包与稳定版（{@code sN}）为 no-op，不注册回调、不起线程。
 *
 * <p>不采集用户内容、不上传：只读同一 UID 的 {@code /proc} 元数据与 JVM/ART 计数，导出到本地文件。
 */
public final class ProbeController {
  private static final ProbeController INSTANCE = new ProbeController();

  private final ProbeRingBuffer samples = new ProbeRingBuffer(ProbeExport.SAMPLE_CAPACITY);
  private final ProbeEventLog events = new ProbeEventLog();
  private final AtomicLong samplerSelfCpuTicks = new AtomicLong();

  private final ShellSampler.Sink sink =
      new ShellSampler.Sink() {
        @Override
        public void onSample(ProbeSample sample) {
          samples.add(sample);
          if (sample.samplerCpuTicks > 0) samplerSelfCpuTicks.addAndGet(sample.samplerCpuTicks);
        }

        @Override
        public void onEvent(ProbeEvent event) {
          events.add(event);
        }
      };

  private volatile ShellSampler sampler;
  private volatile boolean running;
  private long startedAtWallMs;
  private long startedAtElapsedMs;
  private long clkTck = 100;
  private int appUid = -1;
  private int appPid = -1;

  private ProbeController() {}

  public static ProbeController get() {
    return INSTANCE;
  }

  /**
   * 是否允许启动：调试构建，或测试包（TEST_BUILD 非空且不是稳定版 sN）。稳定版是日常使用包，按 ADR-0002 不启用。
   */
  public static boolean enabled() {
    if (BuildConfig.DEBUG) return true;
    String testBuild = BuildConfig.TEST_BUILD;
    if (testBuild == null || testBuild.isEmpty()) return false;
    return !testBuild.startsWith("s");
  }

  /** 幂等；未启用时为 no-op。应在主线程调用（注册生命周期回调）。 */
  public synchronized void start(Context context) {
    if (running || !enabled() || context == null) return;
    // :shizuku 等辅助进程也会创建 Application；探针只跟主进程，避免多份 1 Hz 采样。
    if (!isMainProcess(context)) return;
    Context app = context.getApplicationContext();
    Context startedContext = app == null ? context : app;
    samples.clear();
    events.clear();
    samplerSelfCpuTicks.set(0);
    startedAtWallMs = System.currentTimeMillis();
    startedAtElapsedMs = SystemClock.elapsedRealtime();
    clkTck = ShellSampler.clockTicks();
    appUid = Process.myUid();
    appPid = Process.myPid();
    events.add(
        new ProbeEvent(
            0,
            startedAtWallMs,
            ProbeEvent.TYPE_PROBE_START,
            "pid="
                + appPid
                + " uid="
                + appUid
                + " testBuild="
                + BuildConfig.TEST_BUILD
                + " debug="
                + BuildConfig.DEBUG));
    ShellSampler started = new ShellSampler(startedContext, sink);
    sampler = started;
    running = true;
    started.start();
  }

  /** 幂等；保留已有缓冲供事后导出。 */
  public synchronized void stop() {
    if (!running) return;
    running = false;
    ShellSampler stopped = sampler;
    sampler = null;
    if (stopped != null) stopped.stop();
    events.add(
        new ProbeEvent(
            elapsedMs(),
            System.currentTimeMillis(),
            ProbeEvent.TYPE_PROBE_STOP,
            "samples=" + samples.size()));
  }

  private static boolean isMainProcess(Context context) {
    try {
      String process = Application.getProcessName();
      return process == null || process.equals(context.getPackageName());
    } catch (RuntimeException unavailable) {
      return true;
    }
  }

  public boolean isRunning() {
    return running;
  }

  /** 任意线程可调：取环形缓冲快照后组装 JSON，不阻塞采样。 */
  public String exportJson() {
    ProbeExport.Meta meta = new ProbeExport.Meta();
    meta.generatedAtEpochMs = System.currentTimeMillis();
    meta.startedAtEpochMs = startedAtWallMs;
    meta.uptimeMs = startedAtElapsedMs <= 0 ? 0 : elapsedMs();
    meta.sampleIntervalMs = ProbeExport.SAMPLE_INTERVAL_MS;
    meta.sampleCapacity = samples.capacity();
    meta.pssIntervalMs = ProbeExport.PSS_INTERVAL_MS;
    meta.clkTck = clkTck;
    meta.cpuCount = Math.max(1, Runtime.getRuntime().availableProcessors());
    meta.appPid = appPid;
    meta.appUid = appUid;
    meta.samplerSelfCpuMs = samplerSelfCpuTicks.get() * 1000.0 / Math.max(1, clkTck);
    meta.versionName = BuildConfig.VERSION_NAME;
    meta.versionCode = BuildConfig.VERSION_CODE;
    meta.testBuild = BuildConfig.TEST_BUILD;
    meta.debug = BuildConfig.DEBUG;
    meta.manufacturer = Build.MANUFACTURER;
    meta.model = Build.MODEL;
    meta.device = Build.DEVICE;
    meta.sdk = Build.VERSION.SDK_INT;
    meta.release = Build.VERSION.RELEASE;
    meta.note = ProbeExport.NOTE;
    return new ProbeExport(meta, samples.snapshot(), events.snapshot()).toJson();
  }

  /** 写 UTF-8 JSON 并 fsync；父目录不存在时创建。成功返回 true，失败静默返回 false（探针不能影响主流程）。 */
  public boolean dumpTo(File file) {
    if (file == null) return false;
    String json = exportJson();
    try {
      File parent = file.getParentFile();
      if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
      try (FileOutputStream out = new FileOutputStream(file)) {
        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      }
      return true;
    } catch (IOException | RuntimeException failure) {
      return false;
    }
  }

  private long elapsedMs() {
    return startedAtElapsedMs <= 0
        ? 0
        : Math.max(0, SystemClock.elapsedRealtime() - startedAtElapsedMs);
  }
}
