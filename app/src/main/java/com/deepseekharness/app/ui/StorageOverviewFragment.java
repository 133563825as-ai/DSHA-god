package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.contract.BuiltinModules;
import com.deepseekharness.app.util.UiText;

/** 真实工作区入口的未统计页；不持有扫描器、设备路径或合成模型。 */
public final class StorageOverviewFragment extends Fragment {
  private boolean showingAvailable;

  @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    return render();
  }

  @Override public void onResume() {
    super.onResume();
    // 旋转、返回或隐藏后恢复都重新核对；不可用时不展示旧内容或跳进旧扫描页。
    boolean active = available();
    if (active != showingAvailable && getView() instanceof ViewGroup) {
      ViewGroup root = (ViewGroup) getView();
      root.removeAllViews();
      root.addView(content(active));
    }
  }

  private boolean available() {
    try {
      return BuiltinModules.storageOverviewVisible(
          BuiltinModules.ensureStorageOverview(requireContext()));
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private View render() {
    android.widget.FrameLayout root = new android.widget.FrameLayout(requireContext());
    root.addView(content(available()));
    return root;
  }

  private View content(boolean active) {
    showingAvailable = active;
    if (!active) return unavailable();
    CardPage page = new CardPage(requireContext(), t("存储概览", "Storage overview"),
        t("未统计 · 不自动扫描", "Not measured · No automatic scan"));
    page.kv(page.card(), t("设备卷", "Device volumes"), t("容量与可用空间未知", "Capacity and free space unknown"));
    page.kv(page.card(), t("逻辑占用", "Logical usage"), t("未统计，不代表空", "Not measured; no size available"));
    page.kv(page.card(), t("备份与保留", "Backups and retention"), t("未统计，与占用分开", "Not measured; separate from usage"));
    page.kv(page.card(), t("文件权限", "File permissions"), t("未读取，不代表已授权", "Not read; no grant implied"));
    page.content.addView(page.text(t("备份、保留副本、文件与维护仍在上一页独立入口。",
        "Backups, retained copies, files and maintenance remain separate entries on the previous page."),
        14, R.color.shell_text_secondary));
    page.footer.setVisibility(View.GONE);
    return page.root;
  }

  private View unavailable() {
    CardPage page = new CardPage(requireContext(), t("存储概览", "Storage overview"),
        t("入口不可用，请返回上一页。", "Entry unavailable. Return to the previous page."));
    page.footer.setVisibility(View.GONE);
    return page.root;
  }

  private static String t(String zh, String en) { return UiText.choose(zh, en); }
}
