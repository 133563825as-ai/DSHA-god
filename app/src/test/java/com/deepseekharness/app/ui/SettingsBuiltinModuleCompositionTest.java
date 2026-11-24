package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.contract.*;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.UiText;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;

/** SDK34 复用原导航 Host/App；只隔离宿主运行时，主题仍读写原 ConfigStore。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = SettingsStorageNavigationTest.App.class,
    qualifiers = "zh-rCN-w360dp-h800dp-mdpi",
    shadows = SettingsBuiltinModuleCompositionTest.ControllerConfig.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SettingsBuiltinModuleCompositionTest {
  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static final class ControllerConfig {
    @Implementation protected void __constructor__(Context ignored) {}
    @Implementation protected static HarnessController get(Context context) {
      return org.robolectric.shadow.api.Shadow.newInstanceOf(HarnessController.class);
    }
    @Implementation protected ConfigStore config() {
      return new ConfigStore(RuntimeEnvironment.getApplication());
    }
  }
  private ActivityController<SettingsStorageNavigationTest.Host> host;
  private ModuleRegistry registry;
  private String language;
  private String previousPalette;
  @Before public void setup() {
    language = UiText.language();
    UiText.setLanguage("zh");
    host = Robolectric.buildActivity(SettingsStorageNavigationTest.Host.class).setup();
    previousPalette = ThemeController.palette(host.get());
    new ConfigStore(host.get()).setUiPalette("standard");
    registry = BuiltinModules.ensureProductModules(host.get());
    BuiltinModules.setThemeEnabled(registry, true);
    registry.setContributionHidden(BuiltinModules.THEME_CONTRIBUTION.globalKey(), false);
    android.provider.Settings.Global.putFloat(host.get().getContentResolver(),
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
    settings();
  }
  @After public void teardown() {
    BuiltinModules.setThemeEnabled(registry, true);
    registry.setContributionHidden(BuiltinModules.THEME_CONTRIBUTION.globalKey(), false);
    new ConfigStore(host.get()).setUiPalette(previousPalette);
    host.pause().stop().destroy();
    UiText.setLanguage(language);
  }
  private View settings() {
    host.get().getSupportFragmentManager().beginTransaction()
        .replace(R.id.fragment_container, new SettingsFragment()).commitNow();
    return host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container).requireView();
  }
  private Intent openTheme() {
    assertTrue(settings().findViewById(R.id.settings_theme).performClick());
    Intent intent = Shadow.<ShadowActivity>extract(host.get()).getNextStartedActivity();
    assertNotNull("visible contribution must launch real theme activity", intent);
    return intent;
  }
  private ActivityController<ThemeActivity> page(Intent intent) {
    ActivityController<ThemeActivity> page = Robolectric.buildActivity(ThemeActivity.class, intent);
    page.get().setTheme(R.style.Theme_DeepseekHarness);
    return page.setup();
  }
  @Test public void productEntryUsesVisibleContributionAndExistingPreferences() {
    assertSame(registry, BuiltinModules.ensureRegistered(host.get()));
    assertNotNull(SettingsFragment.visibleTheme(registry));
    assertNotNull(registry.descriptor(BuiltinModules.ABOUT_MODULE_ID));
    assertFalse(registry.isInstalled(BuiltinModules.STORAGE_DEMO_MODULE_ID));
    View root = settings();
    assertEquals(View.VISIBLE, root.findViewById(R.id.settings_theme).getVisibility());
    try (var unused = new PageClose(page(openTheme()))) {
      View palette = text(unused.page.get().getWindow().getDecorView(), "极光");
      assertNotNull(palette);
      ThemeActivity beforeRecreate = unused.page.get();
      ((View) palette.getParent()).performClick();
      assertEquals("aurora", new ConfigStore(host.get()).getUiPalette());
      // ShadowActivity.recreate 排主队列；必须在 PageClose 销毁 owner 前完成真实重建。
      org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
      assertNotSame("palette selection must recreate its actual activity", beforeRecreate, unused.page.get());
    }
    assertTrue(((TextView) settings().findViewById(R.id.settings_theme_sub)).getText().toString().contains("极光"));
    host.recreate();
    assertEquals("aurora", ThemeController.palette(host.get()));
  }
  @Test public void hideAndDisableAreIndependentAndRecreateKeepsChoice() {
    var lease = BuiltinModules.themeLease(registry);
    var page = page(openTheme());
    View controls = settings().findViewWithTag("builtin-theme-controls");
    ((ViewGroup) controls).getChildAt(0).performClick();
    assertFalse(((com.google.android.material.materialswitch.MaterialSwitch)
        ((ViewGroup) controls).getChildAt(0)).isChecked());
    assertNull(SettingsFragment.visibleTheme(registry));
    assertTrue(lease.active());
    assertFalse(page.get().isFinishing());
    assertEquals(View.GONE, settings().findViewById(R.id.settings_theme).getVisibility());
    assertNotNull(settings().findViewWithTag("builtin-theme-controls"));
    ((ViewGroup) settings().findViewWithTag("builtin-theme-controls")).getChildAt(1).performClick();
    assertTrue(page.get().isFinishing());
    assertFalse(lease.active());
    ((ViewGroup) settings().findViewWithTag("builtin-theme-controls")).getChildAt(1).performClick();
    assertNull(SettingsFragment.visibleTheme(registry));
    assertTrue(registry.setContributionHidden(BuiltinModules.THEME_CONTRIBUTION.globalKey(), false).accepted());
    assertNotNull(SettingsFragment.visibleTheme(registry));
    page.pause().stop().destroy();
  }
  @Test public void queuedAndLateRequestsCannotCrossGeneration() {
    Intent queued = openTheme();
    var lease = BuiltinModules.themeLease(registry);
    ThemeChangeRequest changes = new ThemeChangeRequest(lease::active);
    var late = changes.begin();
    BuiltinModules.setThemeEnabled(registry, false);
    BuiltinModules.setThemeEnabled(registry, true);
    assertFalse("negative control: removing generation gate makes this fail", late.claim(true));
    assertFalse(late.claim(true));
    var stale = page(queued);
    assertTrue("queued old intent must not acquire fresh generation", stale.get().isFinishing());
    stale.destroy();
    ThemeChangeRequest fresh = new ThemeChangeRequest(BuiltinModules.themeLease(registry)::active);
    var request = fresh.begin();
    assertTrue(request.claim(true));
    assertFalse("duplicate reply", request.claim(true));
    var livePage = page(openTheme());
    var activeLease = BuiltinModules.themeLease(registry);
    ThemeChangeRequest revoked = new ThemeChangeRequest(() -> BuiltinModules.themeActive(activeLease));
    var revokedReply = revoked.begin();
    assertTrue(registry.revoke(BuiltinModules.THEME_CONTRIBUTION.globalKey()).accepted());
    assertFalse("withdrawn but not hidden contribution", revokedReply.claim(true));
    assertFalse("revoke retires actual lease, not a catalog-only gate", activeLease.active());
    assertTrue("revoke finishes the real previous page", livePage.get().isFinishing());
    livePage.pause().stop().destroy();
    View root = host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container).requireView();
    ((SettingsFragment) host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container)).onResume();
    var enable = (com.google.android.material.materialswitch.MaterialSwitch)
        ((ViewGroup) root.findViewWithTag("builtin-theme-controls")).getChildAt(1);
    assertFalse(enable.isChecked());
    enable.performClick();
    assertTrue("one click reinstalls withdrawn module", enable.isChecked());
    assertNotNull(SettingsFragment.visibleTheme(registry));
    assertFalse("retired application remains stale after recovery", revokedReply.claim(true));
  }
  @Test public void closingPageRetiresOnlyItsRequests() {
    var lease = BuiltinModules.themeLease(registry);
    var actualPage = page(openTheme());
    actualPage.pause().stop().destroy();
    assertTrue("destroying the actual activity does not stop module", lease.active());
    ThemeChangeRequest first = new ThemeChangeRequest(lease::active);
    ThemeChangeRequest second = new ThemeChangeRequest(lease::active);
    var old = first.begin();
    var live = second.begin();
    int[] cleanups = {0};
    first.ownReveal(() -> cleanups[0]++);
    first.retire();
    assertFalse(old.claim(true));
    assertTrue(live.claim(true));
    assertEquals(1, cleanups[0]);
    assertTrue("page close must not disable business/module", lease.active());
    ThemeChangeRequest pending = new ThemeChangeRequest(lease::active, "pending-page");
    ThemeChangeRequest claimed = new ThemeChangeRequest(lease::active, "claimed-page");
    int[] retired = {0, 0};
    pending.ownPending(() -> retired[0]++);
    claimed.ownReveal(() -> retired[1]++);
    BuiltinModules.observeThemeRetirement(registry, lease, pending::retireModule);
    BuiltinModules.observeThemeRetirement(registry, lease, claimed::retireModule);
    assertTrue(registry.revoke(BuiltinModules.THEME_CONTRIBUTION.globalKey()).accepted());
    assertArrayEquals("withdrawal retires pending and claimed page masks", new int[] {1, 1}, retired);
    assertFalse(lease.active());
    assertTrue(registry.isInstalled(BuiltinModules.ABOUT_MODULE_ID));
  }
  @Test public void optionalFailureCannotPolluteLegalFallback() {
    ModuleRegistry failed = new ModuleRegistry(new LayoutStore() {
      public LayoutSnapshot load() { return LayoutSnapshot.empty(); }
      public void save(LayoutSnapshot value) { }
    }, new SafeModeGate(), DiagnosticsSink.discarding());
    assertTrue(failed.register(BuiltinModules.THEME_DESCRIPTOR, () -> { throw new IllegalStateException("factory"); }).accepted());
    assertEquals(ContractCodes.MODULE_INSTALL_FAILED, failed.install(BuiltinModules.THEME_MODULE_ID).code());
    assertEquals(ContractCodes.MODULE_DUPLICATE_ID, BuiltinModules.ensureProductModules(failed).code());
    assertNull(SettingsFragment.visibleTheme(failed));
    assertTrue(failed.visibleContributions().stream().anyMatch(value ->
        BuiltinModules.ABOUT_CONTRIBUTION_KEY.equals(value.key())));
    BuiltinModules.setThemeEnabled(registry, false);
    View root = settings();
    assertNotNull(root.findViewWithTag("builtin-theme-controls"));
    assertTrue(root.findViewById(R.id.settings_about).performClick());
    host.get().getSupportFragmentManager().executePendingTransactions();
    View about = host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container).requireView();
    assertNotNull(text(about, "第三方许可"));
    assertTrue(registry.isInstalled(BuiltinModules.ABOUT_MODULE_ID));
  }
  private static View text(View root, String value) {
    if (root instanceof TextView && value.contentEquals(((TextView) root).getText())) return root;
    if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
      View found = text(((ViewGroup) root).getChildAt(i), value);
      if (found != null) return found;
    }
    return null;
  }
  private static final class PageClose implements AutoCloseable {
    final ActivityController<ThemeActivity> page;
    PageClose(ActivityController<ThemeActivity> page) { this.page = page; }
    @Override public void close() { page.pause().stop().destroy(); }
  }
}
