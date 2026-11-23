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

/** Independent demo route; deliberately does not reference the legacy storage activity. */
public final class StorageDemoFragment extends Fragment {
  private static final StorageDemoController PROCESS = new StorageDemoController(
      work -> new Handler(Looper.getMainLooper()).postDelayed(work, 500));
  private StorageDemoController.Page page;

  @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    return build(requireContext(), PROCESS, p -> page = p);
  }
  @Override public void onStop() {
    if (page != null) page.cancel();
    super.onStop();
  }
  @Override public void onDestroyView() {
    if (page != null) page.close();
    page = null;
    super.onDestroyView();
  }

  static View build(android.content.Context context, StorageDemoController owner,
      java.util.function.Consumer<StorageDemoController.Page> bind) {
    CardPage ui = new CardPage(context, "存储总览 · 合成演示",
        "仅内存合成样例，不读取设备目录、不申请权限、不执行清理。四账独立，禁止相加。");
    TextView status = ui.text("", 14, R.color.shell_text);
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    ui.card().addView(status);
    TextView[] ledgers = new TextView[4];
    String[] names = {"私有逻辑字节", "私有分配估算", "SAF 逻辑字节", "卷可用空间快照"};
    for (int i = 0; i < names.length; i++) {
      LinearLayout card = ui.card();
      card.addView(ui.text(names[i], 14, R.color.shell_text));
      ledgers[i] = ui.text("未请求", 13, R.color.shell_text_secondary);
      ledgers[i].setPadding(0, ui.dp(8), 0, 0);
      card.addView(ledgers[i]);
    }
    StorageDemoController.Page page = owner.open(state -> {
      status.setText(state.status);
      StorageModel.Report report = state.report;
      if (report == null) { for (TextView value : ledgers) value.setText("未读取"); }
      else {
        ledgers[0].setText(amount(report.privateByBucket().get("demo").logical()));
        ledgers[1].setText(amount(report.privateByBucket().get("demo").allocatedEstimate()));
        ledgers[2].setText(amount(report.safByProvider().get("synthetic")));
        ledgers[3].setText(amount(report.volumeAvailable().get("synthetic-volume")));
      }
    });
    bind.accept(page);
    // All controls remain in the safe scroll region, not a second fixed bottom bar.
    LinearLayout actions = ui.card();
    action(ui, actions, "请求部分合成样例", () -> page.request(StorageDemoController.Sample.PARTIAL));
    action(ui, actions, "请求空合成样例", () -> page.request(StorageDemoController.Sample.EMPTY));
    action(ui, actions, "请求未知合成样例", () -> page.request(StorageDemoController.Sample.UNKNOWN));
    action(ui, actions, "取消合成请求", page::cancel);
    ui.footer.setVisibility(View.GONE);
    return ui.root;
  }
  private static void action(CardPage ui, LinearLayout parent, String label, Runnable click) {
    Button button = ui.button(parent, label, false, click);
    button.setMinHeight(ui.dp(48));
    button.setMinimumHeight(ui.dp(48));
  }
  static String amount(StorageModel.Amount value) {
    return (value.observed().isPresent() ? value.observed().getAsLong() + " B" : "未知（不是 0）")
        + (value.partial() ? " · 部分/不完整" : " · 完整合成样例");
  }
}
