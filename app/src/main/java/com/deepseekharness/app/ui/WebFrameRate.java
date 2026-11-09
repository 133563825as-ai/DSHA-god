package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.RequiresApi;

import com.deepseekharness.app.util.FrameRatePolicy;

import java.util.ArrayList;

/**
 * 请求设备允许的高刷新率：Web 工作台由 WebPreviewActivity 自己管（画中画时交还系统），
 * 其余页面由 {@link #lifecycle()} 在每次回到前台时申请。
 */
public final class WebFrameRate {
  private WebFrameRate() {}

  static void apply(Activity activity, View content) {
    if (activity == null || content == null) return;
    Display display = activity.getWindowManager().getDefaultDisplay();
    Display.Mode current = display == null ? null : display.getMode();
    if (current == null) return;
    ArrayList<Display.Mode> matching = new ArrayList<>();
    for (Display.Mode mode : display.getSupportedModes()) {
      // 刷新率请求不能顺带改变面板分辨率。
      if (mode.getPhysicalWidth() == current.getPhysicalWidth()
          && mode.getPhysicalHeight() == current.getPhysicalHeight()) {
        matching.add(mode);
      }
    }
    float[] rates = new float[matching.size()];
    int[] ids = new int[matching.size()];
    for (int i = 0; i < rates.length; i++) {
      rates[i] = matching.get(i).getRefreshRate();
      ids[i] = matching.get(i).getModeId();
    }
    float peak = 0f;
    try {
      peak = Settings.System.getFloat(activity.getContentResolver(), "peak_refresh_rate");
    } catch (Settings.SettingNotFoundException | SecurityException ignored) {
    }
    PowerManager power = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
    float selected = FrameRatePolicy.select(power != null && power.isPowerSaveMode(), peak, rates);
    setWindowRate(activity, selected, FrameRatePolicy.modeFor(selected, ids, rates));
    if (Build.VERSION.SDK_INT >= 35) Api35.setFrameRateCategory(content, selected > 0f);
  }

  static void clear(Activity activity, View content) {
    if (activity == null) return;
    setWindowRate(activity, 0f, 0);
    if (content != null && Build.VERSION.SDK_INT >= 35) Api35.setFrameRateCategory(content, false);
  }

  private static void setWindowRate(Activity activity, float rate, int modeId) {
    WindowManager.LayoutParams params = activity.getWindow().getAttributes();
    if (Math.abs(params.preferredRefreshRate - rate) < 0.01f
        && params.preferredDisplayModeId == modeId) return;
    params.preferredRefreshRate = rate;
    params.preferredDisplayModeId = modeId;
    activity.getWindow().setAttributes(params);
  }

  /** 除 Web 工作台外的所有页面：回到前台时按同一策略申请（省电模式、峰值设置每次重新读取）。 */
  static android.app.Application.ActivityLifecycleCallbacks lifecycle() {
    return new android.app.Application.ActivityLifecycleCallbacks() {
      @Override
      public void onActivityResumed(Activity activity) {
        if (activity instanceof WebPreviewActivity) return;
        try {
          apply(activity, activity.getWindow().getDecorView());
        } catch (RuntimeException ignored) {
          // 刷新率只是偏好；系统拒绝时保持默认，不影响页面。
        }
      }

      @Override
      public void onActivityCreated(Activity activity, android.os.Bundle saved) {}

      @Override
      public void onActivityStarted(Activity activity) {}

      @Override
      public void onActivityPaused(Activity activity) {}

      @Override
      public void onActivityStopped(Activity activity) {}

      @Override
      public void onActivitySaveInstanceState(Activity activity, android.os.Bundle out) {}

      @Override
      public void onActivityDestroyed(Activity activity) {}
    };
  }

  /** 供 Application 注册（本类包内可见）。 */
  public static void register(android.app.Application app) {
    app.registerActivityLifecycleCallbacks(lifecycle());
  }

  @RequiresApi(35)
  private static final class Api35 {
    static void setFrameRateCategory(View view, boolean high) {
      view.setRequestedFrameRate(
          high
              ? View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
              : View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE);
    }
  }
}
