package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Display;
import android.view.FrameMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.util.FrameStats;
import com.deepseekharness.app.util.PerfProbeNames;
import com.deepseekharness.app.util.ThreadCpuStats;
import com.deepseekharness.app.util.UiText;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 仅测试包：Web 工作台内置性能采样。原生 FrameMetrics 逐帧统计 + 网页侧 rAF / LoAF / event timing，合并写成 JSON 存私有目录并导出到
 * Download/DSHA。
 *
 * <p>正式包（TEST_SIGNATURE 为空）与稳定版（TEST_BUILD 为 sN）{@link #install} 返回 null：不注册监听、不注入脚本、不显示入口。网页结果只经 evaluateJavascript
 * 取回，不新增 JavascriptInterface（页面里会运行第三方插件）。
 */
final class WebPerfProbe {
  /** 返回当前已就绪、属于本服务的页面；不可采样时返回 null。 */
  interface Host {
    WebView readyPage();
  }

  private static final long WINDOW_MS = 30_000L;
  private static final long WEB_RESULT_TIMEOUT_MS = 3_000L;
  private static final int FRAME_CAPACITY = 8192;
  private static final String PREFS = "dsha_test_perf_probe";
  private static final String PREF_HIGH_RATE = "requested_frame_rate_high";
  private static final String STOP_SCRIPT =
      "(function(){var p=window.__dshaPerfProbe;return p?p.stop():null;})()";

  private final Activity activity;
  private final Host host;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final TextView button;
  private HandlerThread metricsThread;
  private Window.OnFrameMetricsAvailableListener listener;
  private FrameStats stats;
  private ThreadCpuSampler cpuSampler;
  private ThreadCpuStats.Summary cpuSummary;
  private WebView sampledPage;
  private boolean recording;
  private boolean saving;
  private boolean navigated;
  private boolean metricsRegistered;
  private long startNanos;
  private long startWallMillis;
  private long deadlineMillis;
  private JSONObject displayAtStart;
  private boolean requestHighFrameRate;
  private boolean highFrameRateApplied;
  private boolean experimentFrameRateApplied;
  private float previousRequestedFrameRate;
  private float experimentPreviousRequestedFrameRate;
  private final Runnable tick = this::tick;

  static boolean enabled() {
    // 只在测试包：稳定版（版号 sN）也带署名，但日常使用不显示采样入口。
    return BuildConfig.TEST_SIGNATURE != null
        && !BuildConfig.TEST_SIGNATURE.isEmpty()
        && !BuildConfig.TEST_BUILD.startsWith("s");
  }

  /** 正式包返回 null；测试包在内容根布局上加一个可上下拖动的小按钮。 */
  static WebPerfProbe install(Activity activity, Host host) {
    if (!enabled()) return null;
    try {
      return new WebPerfProbe(activity, host);
    } catch (RuntimeException unavailable) {
      // 采样只是测试辅助，失败不影响对话页。
      return null;
    }
  }

  private WebPerfProbe(Activity activity, Host host) {
    this.activity = activity;
    this.host = host;
    button = new TextView(activity);
    int size = dp(44);
    GradientDrawable background = new GradientDrawable();
    background.setShape(GradientDrawable.OVAL);
    background.setColor(0x99000000);
    button.setBackground(background);
    button.setTextColor(0xFFFFFFFF);
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
    button.setGravity(Gravity.CENTER);
    button.setAlpha(0.55f);
    button.setMinWidth(size);
    button.setMinHeight(size);
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(size, size, Gravity.END | Gravity.CENTER_VERTICAL);
    params.setMarginEnd(dp(2));
    button.setOnClickListener(v -> toggle());
    button.setOnLongClickListener(
        v -> {
          showExperimentDialog();
          return true;
        });
    button.setOnTouchListener(new DragListener());
    showIdle();
    ViewGroup content = activity.findViewById(android.R.id.content);
    content.addView(button, params);
  }

  /** 画中画时隐藏入口；录制中进入画中画会先经 onPause 停止。 */
  void setHidden(boolean hidden) {
    button.setVisibility(hidden ? View.GONE : View.VISIBLE);
  }

  /** 录制期间页面导航会清掉网页侧采样器，结果里如实标记。 */
  void pageNavigated() {
    if (recording) navigated = true;
  }

  void stop(String reason) {
    if (!recording) return;
    recording = false;
    main.removeCallbacks(tick);
    restoreRequestedFrameRate();
    removeListener();
    long endNanos = System.nanoTime();
    long endWall = System.currentTimeMillis();
    if (cpuSampler != null) {
      cpuSummary = cpuSampler.stopAndSummarize();
      cpuSampler = null;
    }
    JSONObject displayAtEnd = displayInfo();
    long fallback = FrameStats.budgetForRefreshRate(currentRefreshRate());
    FrameStats.Summary summary = stats.summarize(startNanos, endNanos, fallback);
    saving = true;
    button.setText("…");
    WebView page = host.readyPage();
    final boolean[] done = {false};
    if (page == null || page != sampledPage) {
      finish(summary, displayAtEnd, endWall, reason, null, "page-changed-or-missing");
      return;
    }
    Runnable timeout =
        () -> {
          if (done[0]) return;
          done[0] = true;
          finish(summary, displayAtEnd, endWall, reason, null, "web-result-timeout");
        };
    main.postDelayed(timeout, WEB_RESULT_TIMEOUT_MS);
    try {
      page.evaluateJavascript(
          STOP_SCRIPT,
          value -> {
            if (done[0]) return;
            done[0] = true;
            main.removeCallbacks(timeout);
            finish(summary, displayAtEnd, endWall, reason, value, null);
          });
    } catch (RuntimeException failure) {
      done[0] = true;
      main.removeCallbacks(timeout);
      finish(summary, displayAtEnd, endWall, reason, null, "evaluate-failed");
    }
  }

  void destroy() {
    main.removeCallbacks(tick);
    if (recording) {
      recording = false;
      restoreRequestedFrameRate();
      removeListener();
    }
    if (cpuSampler != null) {
      cpuSummary = cpuSampler.stopAndSummarize();
      cpuSampler = null;
    }
    if (button.getParent() instanceof ViewGroup)
      ((ViewGroup) button.getParent()).removeView(button);
  }

  private void showExperimentDialog() {
    if (saving || recording) return;
    SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    boolean[] checked = {prefs.getBoolean(PREF_HIGH_RATE, false)};
    new DshaDialogBuilder(activity)
        .setTitle(UiText.choose("性能采样实验", "Performance sampling experiment"))
        .setMultiChoiceItems(
            new String[] {
              UiText.choose("采样时请求高帧率（实验）", "Request high frame rate while sampling (experimental)")
            },
            checked,
            (dialog, which, value) -> checked[which] = value)
        .setPositiveButton(
            UiText.choose("确定", "OK"),
            (dialog, which) -> prefs.edit().putBoolean(PREF_HIGH_RATE, checked[0]).apply())
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .show();
  }

  private void toggle() {
    if (saving) return;
    if (recording) {
      stop("user");
      return;
    }
    start();
  }

  private void start() {
    requestHighFrameRate =
        activity
            .getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
            .getBoolean(PREF_HIGH_RATE, false);
    highFrameRateApplied = false;
    experimentFrameRateApplied = false;
    previousRequestedFrameRate = 0f;
    experimentPreviousRequestedFrameRate = 0f;
    WebView page = host.readyPage();
    if (page == null) {
      Toast.makeText(
              activity,
              UiText.choose("页面尚未就绪，暂不能采样", "The page is not ready for sampling yet"),
              Toast.LENGTH_SHORT)
          .show();
      return;
    }
    String source = readProbeSource();
    if (source.isEmpty()) {
      Toast.makeText(
              activity,
              UiText.choose("采样脚本缺失，无法开始", "The sampling script is missing"),
              Toast.LENGTH_SHORT)
          .show();
      return;
    }
    float hz = currentRefreshRate();
    long budget = FrameStats.budgetForRefreshRate(hz);
    stats = new FrameStats(FRAME_CAPACITY);
    navigated = false;
    sampledPage = page;
    applyRequestedFrameRate(page);
    displayAtStart = displayInfo();
    startNanos = System.nanoTime();
    startWallMillis = System.currentTimeMillis();
    cpuSummary = null;
    cpuSampler = new ThreadCpuSampler(android.os.Process.myUid());
    watchDisplay();
    deadlineMillis = android.os.SystemClock.uptimeMillis() + WINDOW_MS;
    final long fallbackBudget = budget;
    final FrameStats target = stats;
    metricsRegistered = false;
    metricsThread = new HandlerThread("dsha-perf-frames");
    metricsThread.start();
    listener =
        (window, metrics, dropCount) -> {
          target.dropped(dropCount);
          target.add(
              metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
              metrics.getMetric(FrameMetrics.TOTAL_DURATION),
              Build.VERSION.SDK_INT >= 31
                  ? metrics.getMetric(FrameMetrics.DEADLINE)
                  : fallbackBudget,
              stages(metrics),
              metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1);
        };
    try {
      activity
          .getWindow()
          .addOnFrameMetricsAvailableListener(listener, new Handler(metricsThread.getLooper()));
      metricsRegistered = true;
    } catch (RuntimeException unavailable) {
      // 未开启硬件加速等情况下系统拒绝；网页侧数据仍可采集。
      listener = null;
    }
    double budgetMs = budget > 0 ? budget / 1e6 : 1000.0 / 60;
    page.evaluateJavascript(
        source + "\n;window.__dshaPerfProbe&&window.__dshaPerfProbe.start(" + budgetMs + ");",
        null);
    recording = true;
    tick();
    Toast.makeText(
            activity,
            UiText.choose(
                "性能采样开始：30 秒后自动停止，再点一次可提前停止",
                "Performance sampling started. It stops after 30 s, or tap again to stop."),
            Toast.LENGTH_SHORT)
        .show();
  }

  private void applyRequestedFrameRate(WebView page) {
    if (!requestHighFrameRate || Build.VERSION.SDK_INT < 35) return;
    try {
      previousRequestedFrameRate = page.getRequestedFrameRate();
      experimentPreviousRequestedFrameRate = previousRequestedFrameRate;
      page.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
      highFrameRateApplied = true;
      experimentFrameRateApplied = true;
    } catch (RuntimeException unavailable) {
      highFrameRateApplied = false;
    }
  }

  private void restoreRequestedFrameRate() {
    WebView page = sampledPage;
    if (!highFrameRateApplied || page == null || Build.VERSION.SDK_INT < 35) return;
    try {
      page.setRequestedFrameRate(previousRequestedFrameRate);
    } catch (RuntimeException ignored) {
    }
    highFrameRateApplied = false;
  }

  private static long[] stages(FrameMetrics metrics) {
    return new long[] {
      metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION),
      metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
      metrics.getMetric(FrameMetrics.ANIMATION_DURATION),
      metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
      metrics.getMetric(FrameMetrics.DRAW_DURATION),
      metrics.getMetric(FrameMetrics.SYNC_DURATION),
      metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
      metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
      Build.VERSION.SDK_INT >= 31 ? metrics.getMetric(FrameMetrics.GPU_DURATION) : -1
    };
  }

  private void tick() {
    if (!recording) return;
    long left = deadlineMillis - android.os.SystemClock.uptimeMillis();
    if (left <= 0) {
      stop("timer");
      return;
    }
    button.setText(String.format(Locale.ROOT, "● %d", (left + 999) / 1000));
    button.setAlpha(0.9f);
    button.setContentDescription(UiText.choose("停止性能采样", "Stop performance sampling"));
    // 对齐到整秒刷新倒计时。
    main.postDelayed(tick, left % 1000 == 0 ? 1000 : left % 1000);
  }

  private void showIdle() {
    button.setText(UiText.choose("性能", "Perf"));
    button.setAlpha(0.55f);
    button.setContentDescription(UiText.choose("开始性能采样", "Start performance sampling"));
  }

  /** 采样期间显示模式/刷新率的每次变化：{t 毫秒, hz, modeId}。 */
  private final JSONArray displayChanges = new JSONArray();

  private android.hardware.display.DisplayManager.DisplayListener displayListener;

  private void watchDisplay() {
    while (displayChanges.length() > 0) displayChanges.remove(0);
    android.hardware.display.DisplayManager manager =
        activity.getSystemService(android.hardware.display.DisplayManager.class);
    Display display = activity.getDisplay();
    if (manager == null || display == null) return;
    final int id = display.getDisplayId();
    recordDisplay(display);
    displayListener =
        new android.hardware.display.DisplayManager.DisplayListener() {
          @Override
          public void onDisplayChanged(int changed) {
            if (changed != id) return;
            Display now = activity.getDisplay();
            if (now != null) recordDisplay(now);
          }

          @Override
          public void onDisplayAdded(int added) {}

          @Override
          public void onDisplayRemoved(int removed) {}
        };
    manager.registerDisplayListener(displayListener, main);
  }

  private void recordDisplay(Display display) {
    try {
      if (displayChanges.length() >= 512) return;
      displayChanges.put(
          new JSONObject()
              .put("t", Math.round((System.nanoTime() - startNanos) / 1e6))
              .put("hz", round(display.getRefreshRate()))
              .put("modeId", display.getMode().getModeId()));
    } catch (org.json.JSONException | RuntimeException ignored) {
      // 诊断信息，记不下来不影响采样。
    }
  }

  private void unwatchDisplay() {
    if (displayListener == null) return;
    android.hardware.display.DisplayManager manager =
        activity.getSystemService(android.hardware.display.DisplayManager.class);
    if (manager != null) manager.unregisterDisplayListener(displayListener);
    displayListener = null;
  }

  private void removeListener() {
    unwatchDisplay();
    if (listener != null) {
      try {
        activity.getWindow().removeOnFrameMetricsAvailableListener(listener);
      } catch (RuntimeException ignored) {
      }
      listener = null;
    }
    if (metricsThread != null) {
      metricsThread.quitSafely();
      metricsThread = null;
    }
  }

  private void finish(
      FrameStats.Summary summary,
      JSONObject displayAtEnd,
      long endWall,
      String reason,
      String webValue,
      String webError) {
    sampledPage = null;
    JSONObject report = new JSONObject();
    Boolean mobile = null;
    try {
      report.put("schema", "dsha-perf-probe/2");
      report.put("startedAt", iso(startWallMillis));
      report.put("endedAt", iso(endWall));
      report.put("startedAtEpochMs", startWallMillis);
      report.put("endedAtEpochMs", endWall);
      report.put("stopReason", reason);
      report.put("navigatedDuringSample", navigated);
      report.put("build", buildInfo());
      report.put("device", deviceInfo());
      report.put(
          "display",
          new JSONObject()
              .put("start", displayAtStart)
              .put("end", displayAtEnd)
              .put("changes", displayChanges));
      report.put("native", nativeJson(summary));
      report.put("threads", threadsJson(cpuSummary));
      report.put(
          "experiment",
          new JSONObject()
              .put("requestedFrameRateHigh", requestHighFrameRate)
              .put("applied", experimentFrameRateApplied)
              .put("previous", experimentPreviousRequestedFrameRate));
      JSONObject web = parseWeb(webValue, webError);
      report.put("web", web);
      JSONObject mobileInfo = web.optJSONObject("mobile");
      mobile = mobileInfo == null ? null : PerfProbeNames.parseLoaded(mobileInfo.opt("loaded"));
      report.put("mobilePlugin", PerfProbeNames.mobileState(mobile));
    } catch (JSONException impossible) {
      // org.json 只在 NaN/Infinity 时抛出；统计已做取整，不会出现。
    }
    String name = PerfProbeNames.fileName(endWall, mobile);
    byte[] bytes = report.toString().getBytes(StandardCharsets.UTF_8);
    final android.content.Context app = activity.getApplicationContext();
    new Thread(
            () -> {
              String shown;
              try {
                File dir = new File(app.getFilesDir(), "perf-samples");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("mkdir");
                File file = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(file)) {
                  out.write(bytes);
                  out.getFD().sync();
                }
                String exportFailureText = null;
                try {
                  var exported =
                      com.deepseekharness.app.data.DownloadsExport.write(app, file, name);
                  shown =
                      String.format(
                          Locale.ROOT,
                          UiText.choose(
                              "性能采样已保存：Download/DSHA/%s",
                              "Performance sample saved: Download/DSHA/%s"),
                          exported.displayName);
                } catch (Exception exportFailure) {
                  exportFailureText = exceptionText(exportFailure);
                  try {
                    report.put(
                        "export",
                        new JSONObject()
                            .put("success", false)
                            .put("mediaStoreError", exportFailureText));
                    try (FileOutputStream out = new FileOutputStream(file)) {
                      out.write(report.toString().getBytes(StandardCharsets.UTF_8));
                      out.getFD().sync();
                    }
                  } catch (Exception ignored) {
                  }
                  try {
                    File downloads =
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS);
                    File fallbackDir = new File(downloads, "DSHA");
                    if (!fallbackDir.isDirectory() && !fallbackDir.mkdirs())
                      throw new java.io.IOException("mkdir Download/DSHA");
                    File fallback = new File(fallbackDir, name);
                    try (FileOutputStream out = new FileOutputStream(fallback)) {
                      out.write(report.toString().getBytes(StandardCharsets.UTF_8));
                      out.getFD().sync();
                    }
                    shown =
                        String.format(
                            Locale.ROOT,
                            UiText.choose(
                                "MediaStore 导出失败（%s）；已直接保存：Download/DSHA/%s",
                                "MediaStore export failed (%s); saved directly: Download/DSHA/%s"),
                            exportFailureText,
                            fallback.getName());
                  } catch (Exception fallbackFailure) {
                    String failureText = exceptionText(fallbackFailure);
                    try {
                      report.put(
                          "export",
                          new JSONObject()
                              .put("success", false)
                              .put("mediaStoreError", exportFailureText)
                              .put("fallbackError", failureText));
                      try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write(report.toString().getBytes(StandardCharsets.UTF_8));
                        out.getFD().sync();
                      }
                    } catch (Exception ignored) {
                    }
                    shown =
                        String.format(
                            Locale.ROOT,
                            UiText.choose(
                                "两种导出均失败（%s；%s），报告留在私有目录：%s",
                                "Both exports failed (%s; %s); report kept privately: %s"),
                            exportFailureText,
                            failureText,
                            file.getAbsolutePath());
                  }
                }
              } catch (Exception failure) {
                shown =
                    String.format(
                        Locale.ROOT,
                        UiText.choose("性能采样保存失败：%s", "Could not save the performance sample: %s"),
                        failure.getClass().getSimpleName());
              }
              final String message = shown;
              main.post(
                  () -> {
                    saving = false;
                    showIdle();
                    Toast.makeText(app, message, Toast.LENGTH_LONG).show();
                  });
            },
            "dsha-perf-save")
        .start();
  }

  private static String exceptionText(Exception failure) {
    String message = failure.getMessage();
    if (message == null) message = "";
    if (message.length() > 120) message = message.substring(0, 120);
    return failure.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message);
  }

  private static JSONObject parseWeb(String value, String error) throws JSONException {
    if (error != null) return new JSONObject().put("error", error);
    try {
      Object decoded = new JSONTokener(value == null ? "null" : value).nextValue();
      if (!(decoded instanceof String))
        return new JSONObject().put("error", "probe-missing (page reloaded or script blocked)");
      return new JSONObject((String) decoded);
    } catch (JSONException invalid) {
      return new JSONObject().put("error", "invalid-result");
    }
  }

  private JSONObject buildInfo() throws JSONException {
    PackageWebView webView = PackageWebView.current();
    return new JSONObject()
        .put("versionName", BuildConfig.VERSION_NAME)
        .put("versionCode", BuildConfig.VERSION_CODE)
        .put("testBuild", BuildConfig.TEST_BUILD)
        .put(
            "runtime",
            com.deepseekharness.app.runtime.NativeRuntime.lastWebWasNative(activity)
                ? "native"
                : "container")
        .put("webViewPackage", webView.packageName)
        .put("webViewVersion", webView.versionName);
  }

  private static JSONObject deviceInfo() throws JSONException {
    return new JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("device", Build.DEVICE)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("release", Build.VERSION.RELEASE);
  }

  private JSONObject displayInfo() {
    JSONObject result = new JSONObject();
    try {
      Display display = activity.getDisplay();
      if (display == null) return result;
      Display.Mode mode = display.getMode();
      result.put("refreshRate", round(display.getRefreshRate()));
      result.put("modeId", mode.getModeId());
      result.put("modeRefreshRate", round(mode.getRefreshRate()));
      result.put("physicalWidth", mode.getPhysicalWidth());
      result.put("physicalHeight", mode.getPhysicalHeight());
      JSONArray supported = new JSONArray();
      for (Display.Mode item : display.getSupportedModes())
        if (item.getPhysicalWidth() == mode.getPhysicalWidth()
            && item.getPhysicalHeight() == mode.getPhysicalHeight())
          supported.put(round(item.getRefreshRate()));
      result.put("supportedRefreshRates", supported);
      WindowManager.LayoutParams params = activity.getWindow().getAttributes();
      result.put("windowPreferredRefreshRate", round(params.preferredRefreshRate));
      result.put("windowPreferredModeId", params.preferredDisplayModeId);
    } catch (JSONException | RuntimeException ignored) {
    }
    return result;
  }

  private float currentRefreshRate() {
    try {
      Display display = activity.getDisplay();
      return display == null ? 0f : display.getRefreshRate();
    } catch (RuntimeException unavailable) {
      return 0f;
    }
  }

  private JSONObject nativeJson(FrameStats.Summary s) throws JSONException {
    JSONObject result =
        new JSONObject()
            .put("source", "Window.OnFrameMetricsAvailableListener")
            .put("listenerRegistered", metricsRegistered)
            .put("frames", s.frames)
            .put("overflowFrames", s.overflowFrames)
            .put("droppedReports", s.droppedReports)
            .put("firstDrawFrames", s.firstDrawFrames)
            .put("windowSeconds", s.windowSeconds)
            .put("fpsOverWindow", s.fpsOverWindow)
            .put("fpsActiveSeconds", s.fpsActiveSeconds)
            .put("budgetMs", s.budgetMs)
            .put("budgetHz", s.budgetHz)
            .put("overBudgetFrames", s.overBudgetFrames)
            .put("overBudgetRatio", s.overBudgetRatio)
            .put("over2xBudgetFrames", s.over2xBudgetFrames)
            .put(
                "totalMs",
                new JSONObject()
                    .put("mean", s.meanMs)
                    .put("p50", s.p50Ms)
                    .put("p90", s.p90Ms)
                    .put("p95", s.p95Ms)
                    .put("p99", s.p99Ms)
                    .put("max", s.maxMs));
    JSONArray perSecond = new JSONArray();
    for (int value : s.framesPerSecond) perSecond.put(value);
    result.put("framesPerSecond", perSecond);
    JSONArray per250ms = new JSONArray();
    for (int value : s.framesPer250ms) per250ms.put(value);
    result.put("framesPer250ms", per250ms);
    JSONObject stages = new JSONObject();
    for (int i = 0; i < FrameStats.STAGES.length; i++) {
      double[] row = s.stageMs[i];
      if (row == null) continue;
      stages.put(
          FrameStats.STAGES[i],
          new JSONObject().put("mean", row[0]).put("p95", row[1]).put("max", row[2]));
    }
    result.put("stagesMs", stages);
    JSONArray frames = new JSONArray();
    for (double value : s.totalMsPerFrame) frames.put(value);
    result.put("totalMsPerFrame", frames);
    // 出帧节奏：相邻帧间隔折算的等效帧率分布，直接对应系统"显示刷新率"浮窗里的 120/60/30 起伏。
    JSONObject cadence = new JSONObject();
    for (int i = 0; i < FrameStats.GAP_BUCKET_LABELS.length; i++)
      cadence.put(FrameStats.GAP_BUCKET_LABELS[i], s.gapHzBuckets[i]);
    result.put("gapHzBuckets", cadence);
    result.put("stallGapsOver50ms", s.stallGaps);
    JSONArray gaps = new JSONArray();
    for (double value : s.gapMsPerFrame) gaps.put(value);
    result.put("gapMsPerFrame", gaps);
    return result;
  }

  private JSONObject threadsJson(ThreadCpuStats.Summary summary) throws JSONException {
    JSONObject result = new JSONObject();
    if (summary == null) return result.put("error", "unavailable");
    result.put(
        "meta",
        new JSONObject()
            .put("intervalMs", summary.intervalMs)
            .put("clkTck", summary.clkTck)
            .put("samples", summary.samples)
            .put("unreadableProcesses", summary.unreadableProcesses)
            .put("unreadableThreads", summary.unreadableThreads)
            .put("samplerSelfCpuMs", summary.samplerSelfCpuMs)
            .put("cpuCount", summary.cpuCount)
            .put("note", summary.note));
    JSONArray top = new JSONArray();
    for (ThreadCpuStats.ThreadTotal item : summary.top) {
      top.put(
          new JSONObject()
              .put("pid", item.key.pid)
              .put("process", item.key.process)
              .put("tid", item.key.tid)
              .put("name", item.key.name)
              .put("cpuMs", item.cpuMs)
              .put("cpuPctOfOneCore", item.cpuPctOfOneCore)
              .put("peakPctOfOneCore", item.peakPctOfOneCore));
    }
    result.put("top", top);
    JSONArray byName = new JSONArray();
    for (ThreadCpuStats.NamedTotal item : summary.byName) {
      byName.put(
          new JSONObject()
              .put("process", item.process)
              .put("name", item.name)
              .put("cpuMs", item.cpuMs)
              .put("cpuPctOfOneCore", item.cpuPctOfOneCore)
              .put("peakPctOfOneCore", item.peakPctOfOneCore));
    }
    result.put("byName", byName);
    JSONArray processes = new JSONArray();
    for (ThreadCpuStats.ProcessTotal item : summary.processes) {
      processes.put(
          new JSONObject()
              .put("pid", item.pid)
              .put("process", item.process)
              .put("cpuMs", item.cpuMs)
              .put("threads", item.threads));
    }
    result.put("processes", processes);
    JSONArray timeline = new JSONArray();
    for (ThreadCpuStats.TimelinePoint point : summary.timeline) {
      JSONArray pointTop = new JSONArray();
      for (ThreadCpuStats.TimelineThread item : point.top) {
        pointTop.put(
            new JSONObject()
                .put("name", item.name)
                .put("process", item.process)
                .put("pct", item.pct));
      }
      timeline.put(
          new JSONObject()
              .put("t", point.t)
              .put("wall", point.wall)
              .put("totalPctOfOneCore", point.totalPctOfOneCore)
              .put("top", pointTop));
    }
    result.put("timeline", timeline);
    return result;
  }

  private String readProbeSource() {
    try (java.io.InputStream in =
        activity.getResources().openRawResource(com.deepseekharness.app.R.raw.dsha_perf_probe)) {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    } catch (java.io.IOException | RuntimeException missing) {
      return "";
    }
  }

  private static String iso(long millis) {
    java.text.SimpleDateFormat format =
        new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
    format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
    return format.format(new java.util.Date(millis));
  }

  private static double round(float value) {
    return Math.round(value * 100.0) / 100.0;
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  /** 当前 WebView 提供方，读取失败时为 unknown。 */
  private static final class PackageWebView {
    final String packageName;
    final String versionName;

    private PackageWebView(String packageName, String versionName) {
      this.packageName = packageName;
      this.versionName = versionName;
    }

    static PackageWebView current() {
      try {
        android.content.pm.PackageInfo info = WebView.getCurrentWebViewPackage();
        if (info != null) return new PackageWebView(info.packageName, info.versionName);
      } catch (RuntimeException ignored) {
      }
      return new PackageWebView("unknown", "unknown");
    }
  }

  /** 上下拖动避让内容；位移很小时仍按点击处理。 */
  private final class DragListener implements View.OnTouchListener {
    private float downRawY;
    private float startTranslation;
    private boolean dragging;

    @Override
    public boolean onTouch(View view, MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downRawY = event.getRawY();
          startTranslation = view.getTranslationY();
          dragging = false;
          return false;
        case MotionEvent.ACTION_MOVE:
          float delta = event.getRawY() - downRawY;
          if (!dragging && Math.abs(delta) > dp(8)) dragging = true;
          if (dragging) {
            View parent = (View) view.getParent();
            float limit = parent == null ? 0 : (parent.getHeight() - view.getHeight()) / 2f - dp(8);
            view.setTranslationY(Math.max(-limit, Math.min(limit, startTranslation + delta)));
            return true;
          }
          return false;
        case MotionEvent.ACTION_UP:
          if (dragging) {
            dragging = false;
            view.setPressed(false);
            return true;
          }
          return false;
        default:
          return false;
      }
    }
  }
}
