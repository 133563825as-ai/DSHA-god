package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.storage.model.StorageModel;
import com.deepseekharness.app.util.UiText;

/** Independent demo route; deliberately does not reference the legacy storage activity. */
public final class StorageDemoFragment extends Fragment {
  private static final StorageDemoController PROCESS =
      new StorageDemoController(work -> new Handler(Looper.getMainLooper()).postDelayed(work, 500));
  private StorageDemoController.Page page;
  private boolean unavailable;

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    unavailable = !demoVisible();
    if (unavailable) return new View(requireContext());
    return build(requireContext(), PROCESS, p -> page = p);
  }

  private boolean demoVisible() {
    try {
      return SettingsFragment.visibleStorageDemo(
              com.deepseekharness.app.contract.BuiltinModules.ensureRegistered(requireContext()))
          != null;
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    // Child details belong to SettingsFragment's existing two-pane restoration fallback.
    if (!unavailable || getParentFragment() != null) return;
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              if (!isAdded() || !isResumed() || getParentFragment() != null || !unavailable) return;
              var manager = getParentFragmentManager();
              if (manager.isStateSaved()) return;
              manager.executePendingTransactions();
              if (!isAdded() || !isResumed() || getParentFragment() != null || !unavailable) return;
              if (manager.isStateSaved()
                  || manager.findFragmentById(R.id.fragment_container) != this) return;
              int count = manager.getBackStackEntryCount();
              if (count > 0 && "settings".equals(manager.getBackStackEntryAt(count - 1).getName()))
                manager.popBackStackImmediate();
            });
  }

  @Override
  public void onStop() {
    if (page != null) page.cancel();
    super.onStop();
  }

  @Override
  public void onDestroyView() {
    if (page != null) page.close();
    page = null;
    super.onDestroyView();
  }

  static View build(
      android.content.Context context,
      StorageDemoController owner,
      java.util.function.Consumer<StorageDemoController.Page> bind) {
    CardPage ui =
        new CardPage(
            context,
            UiText.choose("存储总览 · 合成演示", "Storage overview · Synthetic demo"),
            UiText.choose(
                "仅内存合成样例，不读取设备目录、不申请权限、不执行清理。四账独立，禁止相加。",
                "In-memory synthetic samples only. No device directories, permissions or cleanup. Four separate ledgers; do not add them together."));
    TextView status = ui.text("", 14, R.color.shell_text);
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    ui.card().addView(status);
    TextView[] ledgers = new TextView[4];
    String[] names = {
      UiText.choose("私有逻辑字节", "Private logical bytes"),
      UiText.choose("私有分配估算", "Private allocation estimate"),
      UiText.choose("SAF 逻辑字节", "SAF logical bytes"),
      UiText.choose("卷可用空间快照", "Volume available-space snapshot")
    };
    for (int i = 0; i < names.length; i++) {
      LinearLayout card = ui.card();
      card.addView(ui.text(names[i], 14, R.color.shell_text));
      ledgers[i] = ui.text(UiText.choose("未请求", "Not requested"), 13, R.color.shell_text_secondary);
      ledgers[i].setPadding(0, ui.dp(8), 0, 0);
      card.addView(ledgers[i]);
    }
    StorageDemoController.Page page =
        owner.open(
            state -> {
              status.setText(status(state.code));
              StorageModel.Report report = state.report;
              if (report == null) {
                for (TextView value : ledgers) value.setText(UiText.choose("未读取", "Not read"));
              } else {
                ledgers[0].setText(amount(report.privateByBucket().get("demo").logical()));
                ledgers[1].setText(
                    amount(report.privateByBucket().get("demo").allocatedEstimate()));
                ledgers[2].setText(amount(report.safByProvider().get("synthetic")));
                ledgers[3].setText(amount(report.volumeAvailable().get("synthetic-volume")));
              }
            });
    bind.accept(page);
    // All controls remain in the safe scroll region, not a second fixed bottom bar.
    LinearLayout actions = ui.card();
    action(
        ui,
        actions,
        UiText.choose("请求部分合成样例", "Request partial synthetic sample"),
        () -> page.request(StorageDemoController.Sample.PARTIAL));
    action(
        ui,
        actions,
        UiText.choose("请求空合成样例", "Request empty synthetic sample"),
        () -> page.request(StorageDemoController.Sample.EMPTY));
    action(
        ui,
        actions,
        UiText.choose("请求未知合成样例", "Request unknown synthetic sample"),
        () -> page.request(StorageDemoController.Sample.UNKNOWN));
    action(ui, actions, UiText.choose("取消合成请求", "Cancel synthetic request"), page::cancel);
    ui.footer.setVisibility(View.GONE);
    return ui.root;
  }

  private static void action(CardPage ui, LinearLayout parent, String label, Runnable click) {
    Button button = ui.button(parent, label, false, click);
    button.setMinHeight(ui.dp(48));
    button.setMinimumHeight(ui.dp(48));
  }

  private static String status(StorageDemoController.Code code) {
    switch (code) {
      case IDLE:
        return UiText.choose("未请求 · 零业务读取", "Not requested · Zero business reads");
      case PARTIAL:
        return UiText.choose("部分结果 · 合成样例", "Partial result · Synthetic sample");
      case EMPTY:
        return UiText.choose("空结果 · 合成样例", "Empty result · Synthetic sample");
      case UNKNOWN:
        return UiText.choose("未知 · 合成样例", "Unknown · Synthetic sample");
      case NOT_STARTED:
        return UiText.choose("合成任务未启动 · 请重试", "Synthetic task not started · Retry");
      case LOADING:
        return UiText.choose("加载中 · 仅合成数据", "Loading · Synthetic data only");
      case WAITING:
        return UiText.choose(
            "等待旧合成任务可信退出 · 请稍后重试", "Waiting for previous synthetic task to exit · Retry later");
      case CANCELLED:
        return UiText.choose("已取消 · 等待合成任务退出", "Cancelled · Waiting for synthetic task to exit");
      default:
        throw new AssertionError(code);
    }
  }

  static String amount(StorageModel.Amount value) {
    return (value.observed().isPresent()
            ? value.observed().getAsLong() + " B"
            : UiText.choose("未知（不是 0）", "Unknown (not zero)"))
        + (value.partial()
            ? UiText.choose(" · 部分/不完整", " · Partial/incomplete")
            : UiText.choose(" · 完整合成样例", " · Complete synthetic sample"));
  }
}
