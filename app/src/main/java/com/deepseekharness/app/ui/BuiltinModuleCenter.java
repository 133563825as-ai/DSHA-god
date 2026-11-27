package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.contract.*;
import com.deepseekharness.app.util.UiText;

/** 只消费宿主既有登记；管理入口不随贡献隐藏或停止而消失。 */
public final class BuiltinModuleCenter extends Fragment {
  private static final String MODULE = "module";
  private final LanguageSelectionOwner languageOwner = new LanguageSelectionOwner();

  @Override
  public void onStop() {
    languageOwner.invalidate();
    super.onStop();
  }

  @Override
  public void onDestroyView() {
    languageOwner.invalidate();
    super.onDestroyView();
  }

  static BuiltinModuleCenter detail(ModuleId id) {
    BuiltinModuleCenter page = new BuiltinModuleCenter();
    Bundle args = new Bundle();
    args.putString(MODULE, id.value());
    page.setArguments(args);
    return page;
  }

  // 这是原生页面的展示范围，不是另一份模块注册表。
  static java.util.List<ModuleId> productIds() {
    return java.util.List.of(
        BuiltinModules.THEME_MODULE_ID,
        BuiltinModules.STARTUP_HISTORY_MODULE_ID,
        BuiltinModules.LANGUAGE_MODULE_ID,
        BuiltinModules.ABOUT_MODULE_ID);
  }

  static ContributionView contribution(ModuleRegistry registry, ModuleId id) {
    for (var item : registry.contributions()) if (id.equals(item.owner())) return item;
    return null;
  }

  static String status(ModuleRegistry registry, ModuleId id) {
    if (registry.safeModeGate().forcedSafeMode()
        && !BuiltinModules.ABOUT_MODULE_ID.equals(id)
        && !BuiltinModules.LANGUAGE_MODULE_ID.equals(id))
      return t("安全模式限制；不可在此绕过", "Restricted by safe mode; no bypass here");
    if (registry.descriptor(id) == null) return t("装配错误：未登记", "Assembly error: not registered");
    var item = contribution(registry, id);
    if (!registry.isInstalled(id)) return t("已停止 / 未装配", "Stopped / not assembled");
    if (item == null) return t("装配错误：贡献不可用", "Assembly error: contribution unavailable");
    return t("运行中", "Running")
        + " · "
        + (item.hidden()
            ? t("入口隐藏（未停止）", "Entry hidden (not stopped)")
            : t("入口可见", "Entry visible"))
        + " · "
        + t("代次 ", "Generation ")
        + item.generation();
  }

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle saved) {
    android.widget.FrameLayout root = new android.widget.FrameLayout(requireContext());
    root.addView(render(), new ViewGroup.LayoutParams(-1, -1));
    return root;
  }

  @Override
  public void onResume() {
    super.onResume();
    // 返回时重读 registry；其它设置页或主题 Activity 可以改变可见性、启停与代次。
    refresh();
  }

  private View render() {
    CardPage page =
        new CardPage(
            requireContext(),
            t("内置模块", "Built-in modules"),
            t(
                "原生功能管理 · 不管理 DSH 插件或设备权限",
                "Native features · not DSH plugins or device permissions"));
    try {
      var registry = BuiltinModules.ensureRegistered(requireContext());
      var assembly = BuiltinModules.ensureProductModules(registry);
      if (assembly.rejected())
        page.content.addView(
            page.text(
                t("装配受限：", "Assembly restricted: ") + assembly.code(),
                14,
                R.color.shell_text_secondary));
      String selected = getArguments() == null ? null : getArguments().getString(MODULE);
      if (selected == null) {
        LinearLayout card = page.card();
        for (var id : productIds()) {
          var descriptor = registry.descriptor(id);
          var item = contribution(registry, id);
          String title =
              item != null
                  ? t(item.title(), item.titleEn())
                  : descriptor != null ? descriptor.name() : id.value();
          page.entry(
                  card,
                  title,
                  status(registry, id),
                  R.drawable.ic_settings,
                  () -> SettingsPanes.open(this, detail(id), "builtin-module"))
              .setTag("module-row:" + id.value());
        }
      } else {
        ModuleId id = new ModuleId(selected);
        if (!productIds().contains(id)) throw new IllegalArgumentException("MODULE_UNKNOWN");
        var descriptor = registry.descriptor(id);
        var item = contribution(registry, id);
        page.heading.setText(
            item != null
                ? t(item.title(), item.titleEn())
                : descriptor != null ? descriptor.name() : id.value());
        LinearLayout card = page.card();
        page.kv(card, t("模块", "Module"), id.value());
        page.kv(card, t("状态", "Status"), status(registry, id));
        if (descriptor != null)
          page.kv(card, t("版本", "Version"), String.valueOf(descriptor.version()));
        if (BuiltinModules.THEME_MODULE_ID.equals(id)) {
          page.content.addView(
              page.text(
                  t(
                      "隐藏只影响入口；停止会退休当前代次。恢复使用新代次，保留原有外观偏好。",
                      "Hiding affects only the entry. Stopping retires its generation. Resume creates a new generation and keeps appearance preferences."),
                  14,
                  R.color.shell_text_secondary));
          themeControls(page, registry);
          page.entry(
                  page.card(),
                  t("打开主题与外观", "Open theme and appearance"),
                  t(
                      "隐藏时请先恢复入口可见；使用原主题页",
                      "Show the entry first if hidden; uses the existing theme page"),
                  R.drawable.ic_settings,
                  () -> {
                    if (SettingsFragment.visibleTheme(registry) != null)
                      ThemeActivity.open(requireContext());
                  })
              .setEnabled(SettingsFragment.visibleTheme(registry) != null);
        } else if (BuiltinModules.STARTUP_HISTORY_MODULE_ID.equals(id)) {
          page.content.addView(
              page.text(
                  t(
                      "仅展示模块状态；启动记录仍由诊断页显式读取，此处不自动读取、不提供启停或隐藏。",
                      "Status only. Startup records are read explicitly in diagnostics; no automatic read, stop or hide controls here."),
                  14,
                  R.color.shell_text_secondary));
          page.entry(
              page.card(),
              t("前往现有诊断", "Open existing diagnostics"),
              "",
              R.drawable.ic_settings,
              () -> startActivity(new Intent(requireContext(), DiagnosticActivity.class)));
        } else if (BuiltinModules.LANGUAGE_MODULE_ID.equals(id)) {
          page.entry(
              page.card(),
              t("界面语言 / Language", "Interface language"),
              t("原语言选择器；模块不可用时由宿主兜底", "Existing picker; host fallback when unavailable"),
              R.drawable.ic_settings,
              () -> SettingsFragment.openLanguagePicker(this, languageOwner, registry));
        } else {
          page.entry(
              page.card(),
              t("关于与许可", "About and licenses"),
              t("保留原法定许可入口", "Existing legal and license page"),
              R.drawable.ic_settings,
              () -> SettingsPanes.open(this, new AboutFragment(), "module-about"));
        }
      }
    } catch (RuntimeException unavailable) {
      page.content.addView(
          page.text(
              t(
                  "装配错误；未绕过安全限制。语言和许可仍可从设置原入口访问。",
                  "Assembly error; safety restrictions remain enforced. Language and licenses remain available in Settings."),
              14,
              R.color.shell_text_secondary));
    }
    return page.root;
  }

  private void themeControls(CardPage page, ModuleRegistry registry) {
    LinearLayout controls = page.card();
    controls.setTag("module-center-theme-controls");
    boolean active = BuiltinModules.themeLease(registry) != null;
    var show = new com.google.android.material.materialswitch.MaterialSwitch(requireContext());
    show.setText(t("显示主题入口", "Show theme entry"));
    show.setMinHeight(page.dp(48));
    show.setChecked(!registry.layout().isHidden(BuiltinModules.THEME_CONTRIBUTION.globalKey()));
    show.setEnabled(active && !registry.safeModeGate().forcedSafeMode());
    show.setOnCheckedChangeListener(
        (button, checked) -> {
          var result =
              registry.setContributionHidden(
                  BuiltinModules.THEME_CONTRIBUTION.globalKey(), !checked);
          report(result);
          refresh();
        });
    controls.addView(show);
    var enabled = new com.google.android.material.materialswitch.MaterialSwitch(requireContext());
    enabled.setText(t("启用主题模块", "Enable theme module"));
    enabled.setMinHeight(page.dp(48));
    enabled.setChecked(active);
    enabled.setEnabled(!registry.safeModeGate().forcedSafeMode());
    enabled.setOnCheckedChangeListener(
        (button, checked) -> {
          report(BuiltinModules.setThemeEnabled(registry, checked));
          refresh();
        });
    controls.addView(enabled);
  }

  private void report(ContractResult result) {
    if (result.rejected())
      android.widget.Toast.makeText(
              requireContext(), result.code(), android.widget.Toast.LENGTH_LONG)
          .show();
  }

  private void refresh() {
    if (!isAdded() || !(getView() instanceof android.widget.FrameLayout)) return;
    var root = (android.widget.FrameLayout) getView();
    root.removeAllViews();
    root.addView(render(), new ViewGroup.LayoutParams(-1, -1));
  }
}
