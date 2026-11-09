package com.deepseekharness.app.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.UiText;

/**
 * 主页右上角的运行环境切换（暂定方案，最终 UI 定稿后替换）。
 *
 * <p>点一下立即保存，不经配置页的保存栏。只有 Web 已停止时才允许切换：正在运行、启动中或停止中都拒绝并说明原因，
 * 不自动停止，也不在失败时改用另一个运行环境。容器内部的 proot / proroot 仍在运行配置页里选。
 */
final class EngineSwitchButton {
  private EngineSwitchButton() {}

  /** 插到顶栏 {@code before} 位置之前；返回按钮本身，由调用方控制哪些页面可见。 */
  static TextView install(Activity activity, android.view.ViewGroup appBar, int before) {
    TextView chip = new TextView(activity);
    chip.setGravity(Gravity.CENTER);
    chip.setTextSize(12);
    chip.setTextColor(activity.getColor(R.color.shell_text_secondary));
    chip.setBackgroundResource(R.drawable.bg_chip_clickable);
    float density = activity.getResources().getDisplayMetrics().density;
    int horizontal = Math.round(10 * density);
    chip.setPadding(horizontal, 0, horizontal, 0);
    chip.setMinHeight(Math.round(32 * density));
    chip.setMinWidth(Math.round(44 * density));
    chip.setMaxLines(1);
    android.widget.LinearLayout.LayoutParams params =
        new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(32 * density));
    params.setMarginEnd(Math.round(4 * density));
    params.gravity = Gravity.CENTER_VERTICAL;
    appBar.addView(chip, before, params);
    chip.setOnClickListener(v -> choose(activity, chip));
    render(activity, chip);
    return chip;
  }

  static void render(Activity activity, TextView chip) {
    boolean nativeEngine = !new ConfigStore(activity).dshEngine().isContainer();
    String label = nativeEngine ? UiText.choose("原生", "Native") : UiText.choose("容器", "Container");
    chip.setText(label);
    chip.setContentDescription(
        nativeEngine
            ? UiText.choose("运行环境：原生，点按切换", "Runtime: Native, tap to switch")
            : UiText.choose("运行环境：容器，点按切换", "Runtime: Container, tap to switch"));
  }

  private static void choose(Activity activity, TextView chip) {
    HarnessController controller = HarnessController.get(activity);
    if (controller != null
        && (controller.isWebRunning() || controller.isStarting() || controller.isStopping())) {
      Toast.makeText(
              activity,
              UiText.choose("请先停止 Web，再切换运行环境。", "Stop Web first, then switch the runtime."),
              Toast.LENGTH_LONG)
          .show();
      return;
    }
    ConfigStore config = new ConfigStore(activity);
    boolean nativeNow = !config.dshEngine().isContainer();
    String[] labels = {
      UiText.choose("容器（Ubuntu，默认）", "Container (Ubuntu, default)"),
      UiText.choose("Android 原生（实验）", "Android native (experimental)")
    };
    new DshaDialogBuilder(activity)
        .setTitle(UiText.choose("运行环境", "Runtime"))
        .setSingleChoiceItems(
            labels,
            nativeNow ? 1 : 0,
            (dialog, which) -> {
              dialog.dismiss();
              // 再查一次：对话框打开期间 Web 可能被启动了。
              HarnessController current = HarnessController.get(activity);
              if (current != null
                  && (current.isWebRunning() || current.isStarting() || current.isStopping())) {
                Toast.makeText(
                        activity,
                        UiText.choose(
                            "Web 已在运行，未切换；请先停止 Web。",
                            "Web is running; not switched. Stop Web first."),
                        Toast.LENGTH_LONG)
                    .show();
                return;
              }
              boolean wantNative = which == 1;
              if (wantNative == nativeNow) return;
              new ConfigStore(activity).setDshEngine(wantNative);
              render(activity, chip);
              Toast.makeText(
                      activity,
                      wantNative
                          ? UiText.choose(
                              "已切换到 Android 原生；下次启动 Web 生效。首次使用需在 DSH 里重新填 API Key。",
                              "Switched to Android native; applies the next time Web starts. Enter your API key in DSH once.")
                          : UiText.choose(
                              "已切换到容器；下次启动 Web 生效。",
                              "Switched to container; applies the next time Web starts."),
                      Toast.LENGTH_LONG)
                  .show();
            })
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .show();
  }

  /** 只在主页（未进入下级页面）显示。 */
  static void updateVisibility(TextView chip, boolean home, Activity activity) {
    if (chip == null) return;
    chip.setVisibility(home ? View.VISIBLE : View.GONE);
    if (home) render(activity, chip);
  }
}
