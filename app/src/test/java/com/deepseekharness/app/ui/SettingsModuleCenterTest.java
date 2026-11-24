package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.contract.*;
import com.deepseekharness.app.util.UiText;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;

/** 真实 Settings 导航与既有注册表；不启动 Web/PTY，不读取启动记录。 */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 34, application = SettingsStorageNavigationTest.App.class,
    qualifiers = "zh-rCN-w360dp-h800dp-mdpi",
    shadows = SettingsBuiltinModuleCompositionTest.ControllerConfig.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SettingsModuleCenterTest {
  private ActivityController<SettingsStorageNavigationTest.Host> host;
  private ModuleRegistry registry;
  private String language;

  @Before public void setup() {
    language = UiText.language();
    UiText.setLanguage("zh");
    host = Robolectric.buildActivity(SettingsStorageNavigationTest.Host.class).setup();
    registry = BuiltinModules.ensureProductModules(host.get());
    registry.exitSafeMode();
    BuiltinModules.setThemeEnabled(registry, true);
    registry.setContributionHidden(BuiltinModules.THEME_CONTRIBUTION.globalKey(), false);
    android.provider.Settings.Global.putFloat(host.get().getContentResolver(),
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
  }

  @After public void teardown() {
    registry.exitSafeMode();
    BuiltinModules.setThemeEnabled(registry, true);
    registry.setContributionHidden(BuiltinModules.THEME_CONTRIBUTION.globalKey(), false);
    host.pause().stop().destroy();
    UiText.setLanguage(language);
  }

  private View mount(androidx.fragment.app.Fragment fragment) {
    host.get().getSupportFragmentManager().beginTransaction()
        .replace(R.id.fragment_container, fragment).commitNow();
    return fragment.requireView();
  }

  private View current() {
    host.get().getSupportFragmentManager().executePendingTransactions();
    return host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container).requireView();
  }

  @Test public void settingsEntryListsExactlyFourAndNavigatesToRealDetails() {
    View settings = mount(new SettingsFragment());
    assertTrue(settings.findViewWithTag("builtin-module-center-entry").performClick());
    View center = current();
    assertEquals(4, BuiltinModuleCenter.productIds().size());
    for (var id : BuiltinModuleCenter.productIds())
      assertNotNull(center.findViewWithTag("module-row:" + id.value()));
    assertNull(center.findViewWithTag("module-row:" + BuiltinModules.STORAGE_DEMO_MODULE_ID.value()));
    center.findViewWithTag("module-row:" + BuiltinModules.THEME_MODULE_ID.value()).performClick();
    View detail = current();
    assertNotNull(detail.findViewWithTag("module-center-theme-controls"));
    assertNotNull(text(detail, BuiltinModules.THEME_MODULE_ID.value()));
    assertSame(registry, BuiltinModules.ensureRegistered(host.get()));
    BuiltinModules.setThemeEnabled(registry, false);
    var fragment = host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    fragment.onResume();
    var controls = (ViewGroup) fragment.requireView().findViewWithTag("module-center-theme-controls");
    assertFalse(((com.google.android.material.materialswitch.MaterialSwitch) controls.getChildAt(1)).isChecked());
    assertNotNull(text(fragment.requireView(), "已停止 / 未装配"));
  }

  @Test public void hiddenThemeCanBeManagedAndStopRetiresGeneration() {
    var old = BuiltinModules.themeLease(registry);
    registry.setContributionHidden(BuiltinModules.THEME_CONTRIBUTION.globalKey(), true);
    View detail = mount(BuiltinModuleCenter.detail(BuiltinModules.THEME_MODULE_ID));
    assertTrue(BuiltinModuleCenter.status(registry, BuiltinModules.THEME_MODULE_ID).contains("入口隐藏（未停止）"));
    assertTrue(old.active());
    var controls = (ViewGroup) detail.findViewWithTag("module-center-theme-controls");
    controls.getChildAt(1).performClick();
    assertFalse(old.active());
    controls = current().findViewWithTag("module-center-theme-controls");
    controls.getChildAt(1).performClick();
    assertNotNull(BuiltinModules.themeLease(registry));
    assertNotEquals(old.generation(), BuiltinModules.themeLease(registry).generation());
    assertFalse(old.active());
    assertTrue(registry.layout().isHidden(BuiltinModules.THEME_CONTRIBUTION.globalKey()));
    controls = current().findViewWithTag("module-center-theme-controls");
    controls.getChildAt(0).performClick();
    assertNotNull(SettingsFragment.visibleTheme(registry));
    clickEntry(current(), "打开主题与外观");
    Intent intent = Shadow.<ShadowActivity>extract(host.get()).getNextStartedActivity();
    assertNotNull(intent);
    assertEquals(ThemeActivity.class.getName(), intent.getComponent().getClassName());
  }

  @Test @Config(qualifiers = "zh-rCN-w900dp-h800dp-mdpi")
  public void onlyThemeHasControlsAndOriginalRoutesRemain() {
    for (var id : java.util.List.of(BuiltinModules.STARTUP_HISTORY_MODULE_ID,
        BuiltinModules.LANGUAGE_MODULE_ID, BuiltinModules.ABOUT_MODULE_ID)) {
      View detail = mount(BuiltinModuleCenter.detail(id));
      assertNull(detail.findViewWithTag("module-center-theme-controls"));
    }
    clickEntry(mount(BuiltinModuleCenter.detail(BuiltinModules.ABOUT_MODULE_ID)), "关于与许可");
    current();
    assertTrue(host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container) instanceof AboutFragment);
    clickEntry(mount(BuiltinModuleCenter.detail(BuiltinModules.STARTUP_HISTORY_MODULE_ID)), "前往现有诊断");
    Intent intent = Shadow.<ShadowActivity>extract(host.get()).getNextStartedActivity();
    assertEquals(DiagnosticActivity.class.getName(), intent.getComponent().getClassName());
    clickEntry(mount(BuiltinModuleCenter.detail(BuiltinModules.LANGUAGE_MODULE_ID)), "界面语言 / Language");
    current();
    assertTrue(host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container) instanceof BuiltinModuleCenter);
    android.app.Dialog picker = org.robolectric.shadows.ShadowDialog.getLatestDialog();
    assertNotNull("original language picker is actually shown", picker);
    assertTrue(picker.isShowing());
    picker.dismiss();
    SettingsFragment settings = new SettingsFragment();
    View shell = mount(settings);
    assertTrue(settings.isTwoPane());
    shell.findViewWithTag("builtin-module-center-entry").performClick();
    host.get().getSupportFragmentManager().executePendingTransactions();
    settings.getChildFragmentManager().executePendingTransactions();
    assertTrue(settings.shownDetail() instanceof BuiltinModuleCenter);
    settings.shownDetail().requireView().findViewWithTag("module-row:" + BuiltinModules.LANGUAGE_MODULE_ID.value()).performClick();
    settings.getChildFragmentManager().executePendingTransactions();
    var languageDetail = settings.shownDetail();
    assertTrue(languageDetail instanceof BuiltinModuleCenter);
    clickEntry(languageDetail.requireView(), "界面语言 / Language");
    picker = org.robolectric.shadows.ShadowDialog.getLatestDialog();
    assertTrue(picker.isShowing());
    picker.dismiss();
    assertSame(languageDetail, settings.shownDetail());
    assertNull(languageDetail.requireView().findViewById(R.id.settings_detail));
    assertSame(settings, host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container));
  }

  @Test public void safeModeCannotBeBypassedAndAssemblyErrorsAreExplicit() {
    registry.enterSafeMode();
    View detail = mount(BuiltinModuleCenter.detail(BuiltinModules.THEME_MODULE_ID));
    assertTrue(BuiltinModuleCenter.status(registry, BuiltinModules.THEME_MODULE_ID).contains("安全模式"));
    var controls = (ViewGroup) detail.findViewWithTag("module-center-theme-controls");
    assertFalse(controls.getChildAt(0).isEnabled());
    assertFalse(controls.getChildAt(1).isEnabled());
    assertEquals(ContractCodes.SAFE_MODE_RESTRICTED, BuiltinModules.setThemeEnabled(registry, true).code());
    assertNull(BuiltinModules.themeLease(registry));
    assertTrue(registry.safeModeGate().forcedSafeMode());
    ModuleRegistry failed = new ModuleRegistry(new LayoutStore() {
      public LayoutSnapshot load() { return LayoutSnapshot.empty(); }
      public void save(LayoutSnapshot value) { }
    }, new SafeModeGate(), DiagnosticsSink.discarding());
    assertTrue(BuiltinModuleCenter.status(failed, BuiltinModules.THEME_MODULE_ID).contains("装配错误"));
    assertNotNull(mount(new SettingsFragment()).findViewById(R.id.settings_about));
  }

  @Test public void simulatedColdStartKeepsThemeAndLayoutChoices() throws Exception {
    registry.setContributionHidden(BuiltinModules.THEME_CONTRIBUTION.globalKey(), true);
    var lease = BuiltinModules.themeLease(registry);
    BuiltinModules.setThemeEnabled(registry, false);
    // 简单模拟进程缓存丢失；不声称设备真实冷启。偏好仍使用原 SharedPreferences。
    var cached = BuiltinModules.class.getDeclaredField("cachedRegistry");
    cached.setAccessible(true);
    cached.set(null, null);
    registry = BuiltinModules.ensureProductModules(host.get());
    assertFalse(lease.active());
    assertNull(BuiltinModules.themeLease(registry));
    assertTrue(registry.layout().isHidden(BuiltinModules.THEME_CONTRIBUTION.globalKey()));
    View detail = mount(BuiltinModuleCenter.detail(BuiltinModules.THEME_MODULE_ID));
    ((ViewGroup) detail.findViewWithTag("module-center-theme-controls")).getChildAt(1).performClick();
    assertNotNull(BuiltinModules.themeLease(registry));
    assertTrue(registry.layout().isHidden(BuiltinModules.THEME_CONTRIBUTION.globalKey()));
  }

  private static void clickEntry(View root, String value) {
    View found = clickableEntry(root, value);
    assertNotNull(value, found);
    assertTrue(found.performClick());
  }

  private static View clickableEntry(View root, String value) {
    if (root.isClickable() && text(root, value) != null) return root;
    if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
      View found = clickableEntry(((ViewGroup) root).getChildAt(i), value);
      if (found != null) return found;
    }
    return null;
  }

  private static View text(View root, String value) {
    if (root instanceof TextView && value.contentEquals(((TextView) root).getText())) return root;
    if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
      View found = text(((ViewGroup) root).getChildAt(i), value);
      if (found != null) return found;
    }
    return null;
  }
}
