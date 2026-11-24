package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.contract.BuiltinModules;
import com.deepseekharness.app.contract.ModuleRegistry;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.UiText;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/** Real Settings/FragmentManager navigation; only unrelated configuration capabilities are faked. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = SettingsStorageNavigationTest.App.class,
    qualifiers = "zh-rCN-w360dp-h800dp-mdpi",
    shadows = {SettingsStorageNavigationTest.ControllerConfig.class,
        SettingsStorageNavigationTest.MemoryConfig.class})
@LooperMode(LooperMode.Mode.PAUSED)
public final class SettingsStorageNavigationTest {
  public static final class App extends DshaApp {
    @Override public void onCreate() {}
    @Override public HarnessController harnessController() {
      throw new AssertionError("NAV_BOUNDARY: real application controller must not be resolved");
    }
  }
  public static final class Host extends AppCompatActivity {
    @Override protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
      FrameLayout content = new FrameLayout(this);
      content.setId(R.id.fragment_container);
      setContentView(content);
    }
  }
  /** Suppress ProotBootstrap, host ports and WebLifecycleController construction altogether. */
  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static final class ControllerConfig {
    @Implementation protected void __constructor__(Context ignored) {}
    @Implementation protected static HarnessController get(Context context) {
      return org.robolectric.shadow.api.Shadow.newInstanceOf(HarnessController.class);
    }
    @Implementation protected ConfigStore config() { return new ConfigStore(null); }
  }
  /** No SharedPreferences, KeyVault or legacy-key migrations for unrelated settings controls. */
  @Implements(value = ConfigStore.class, isInAndroidSdk = false)
  public static final class MemoryConfig {
    @Implementation protected void __constructor__(Context ignored) {}
    @Implementation protected boolean isEcoMode() { return false; }
    @Implementation protected boolean isKeepScreenOn() { return false; }
    @Implementation protected boolean isHideFromRecents() { return false; }
    @Implementation protected boolean isHideRecentsStrict() { return false; }
    @Implementation protected String getUiLanguagePreference() { return "zh"; }
    @Implementation protected String getUiLanguage() { return "zh"; }
    @Implementation protected String getUiPalette() { return "standard"; }
  }

  private ActivityController<Host> host;
  private String previousLanguage;
  private boolean previousDemoHidden, previousAboutHidden;
  @Before public void setup() {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
    host = Robolectric.buildActivity(Host.class).setup();
    android.provider.Settings.Global.putFloat(host.get().getContentResolver(),
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
    ModuleRegistry registry = registry();
    assertTrue("NAV_SETUP_DEMO_INSTALLED", BuiltinModules.ensureStorageDemo(registry).accepted());
    previousDemoHidden = registry.contributions().stream().anyMatch(view ->
        BuiltinModules.STORAGE_DEMO_CONTRIBUTION.globalKey().equals(view.key()) && view.hidden());
    previousAboutHidden = registry.contributions().stream().anyMatch(view ->
        BuiltinModules.ABOUT_CONTRIBUTION_KEY.equals(view.key()) && view.hidden());
    assertTrue("NAV_SETUP_DEMO_VISIBLE", registry.setContributionHidden(
        BuiltinModules.STORAGE_DEMO_CONTRIBUTION.globalKey(), false).accepted());
    host.get().getSupportFragmentManager().beginTransaction()
        .replace(R.id.fragment_container, new SettingsFragment()).commitNow();
    assertTrue("NAV_SETUP_SINGLE_PANE", current() instanceof SettingsFragment);
    assertFalse("NAV_SETUP_NOT_TWO_PANE", ((SettingsFragment) current()).isTwoPane());
  }
  @After public void teardown() {
    if (host != null) {
      ModuleRegistry registry = registry();
      registry.setContributionHidden(BuiltinModules.STORAGE_DEMO_CONTRIBUTION.globalKey(), previousDemoHidden);
      registry.setContributionHidden(BuiltinModules.ABOUT_CONTRIBUTION_KEY, previousAboutHidden);
      host.pause().stop().destroy();
    }
    ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(1));
    UiText.setLanguage(previousLanguage);
  }

  @Test public void settingsClickAndSystemBackKeepAboutReachable() {
    clickDemo();
    assertTrue("NAV_CLICK_STORAGE_FRAGMENT", current() instanceof StorageDemoFragment);
    assertTrue("NAV_CLICK_SYNTHETIC_ONLY", words(current().requireView()).contains("零业务读取"));
    assertEquals("NAV_CLICK_ONE_BACK_ENTRY", 1, host.get().getSupportFragmentManager().getBackStackEntryCount());
    systemBack();
    assertTrue("NAV_SYSTEM_BACK_SETTINGS", current() instanceof SettingsFragment);
    openAbout();
    assertLegalAbout();
  }

  @Test public void singlePaneRecreateRetiresOldOwner() {
    clickDemo();
    View old = current().requireView();
    clickText(old, "请求部分合成样例");
    assertTrue("NAV_OLD_LOADING", words(old).contains("加载中"));
    host.recreate();
    drain();
    assertTrue("NAV_RECREATE_STORAGE_FRAGMENT", current() instanceof StorageDemoFragment);
    assertNotSame("NAV_RECREATE_FRESH_VIEW", old, current().requireView());
    assertTrue("NAV_RECREATE_NO_AUTO_REQUEST", words(current().requireView()).contains("零业务读取"));
    String retired = words(old);
    clickText(current().requireView(), "请求空合成样例");
    assertTrue("NAV_RECREATE_OLD_LEASE_HELD", words(current().requireView()).contains("等待旧合成任务可信退出"));
    String waiting = words(current().requireView());
    ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(501));
    assertEquals("NAV_RECREATE_NO_OLD_VIEW_REPLY", retired, words(old));
    assertEquals("NAV_RECREATE_NO_NEW_VIEW_REPLY", waiting, words(current().requireView()));
    clickText(current().requireView(), "请求空合成样例");
    ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(501));
    assertTrue("NAV_RECREATE_TRUSTED_EXIT_RETRY", words(current().requireView()).contains("空结果"));
    systemBack();
    assertTrue("NAV_RECREATE_BACK_SETTINGS", current() instanceof SettingsFragment);
    openAbout();
    assertLegalAbout();
  }

  @Test public void hiddenDemoCannotReopenFromRestoredRoute() {
    clickDemo();
    clickText(current().requireView(), "请求部分合成样例");
    ModuleRegistry registry = registry();
    assertTrue("NAV_HIDE_ACCEPTED", registry.setContributionHidden(
        BuiltinModules.STORAGE_DEMO_CONTRIBUTION.globalKey(), true).accepted());
    host.recreate();
    drain();
    assertFalse("NAV_HIDDEN_RESTORE_MUST_NOT_REOPEN_STORAGE", current() instanceof StorageDemoFragment);
    assertTrue("NAV_HIDDEN_RESTORE_SETTINGS_FALLBACK", current() instanceof SettingsFragment);
    assertNull("NAV_HIDDEN_RESTORE_NO_VISIBLE_ENTRY", visibleText(current().requireView(), "存储总览 · 合成演示"));
    openAbout();
    assertLegalAbout();
  }

  @Test public void hiddenSettingsEntryStillOpensLegalAbout() {
    ModuleRegistry registry = registry();
    assertTrue("NAV_HIDE_ENTRY_ACCEPTED", registry.setContributionHidden(
        BuiltinModules.STORAGE_DEMO_CONTRIBUTION.globalKey(), true).accepted());
    // Legal About is a host fallback even if its optional contribution card is also hidden.
    assertTrue("NAV_HIDE_ABOUT_CARD_ACCEPTED", registry.setContributionHidden(
        BuiltinModules.ABOUT_CONTRIBUTION_KEY, true).accepted());
    host.recreate();
    drain();
    assertTrue("NAV_HIDDEN_ENTRY_SETTINGS", current() instanceof SettingsFragment);
    assertNull("NAV_HIDDEN_ENTRY_NOT_RENDERED", visibleText(current().requireView(), "存储总览 · 合成演示"));
    openAbout();
    assertLegalAbout();
  }

  private ModuleRegistry registry() { return BuiltinModules.ensureRegistered(host.get()); }
  private Fragment current() { return host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container); }
  private void drain() {
    host.get().getSupportFragmentManager().executePendingTransactions();
    ShadowLooper.shadowMainLooper().idle();
  }
  private void clickDemo() {
    clickText(current().requireView(), "存储总览 · 合成演示");
    drain();
  }
  private void systemBack() {
    host.get().getOnBackPressedDispatcher().onBackPressed();
    drain();
  }
  private void openAbout() {
    assertTrue("NAV_ABOUT_ROW_CLICK", current().requireView().findViewById(R.id.settings_about).performClick());
    drain();
  }
  private void assertLegalAbout() {
    assertTrue("NAV_ABOUT_FRAGMENT", current() instanceof AboutFragment);
    assertNotNull("NAV_ABOUT_LEGAL_SECTION", visibleText(current().requireView(), "第三方许可"));
    assertNotNull("NAV_ABOUT_GPL_ROW", visibleText(current().requireView(), "GNU GPL v3.0"));
    assertNotNull("NAV_ABOUT_ONLINE_ENTRY", visibleText(current().requireView(), "许可声明全文（在线）"));
  }
  private static void clickText(View root, String label) {
    View found = visibleText(root, label);
    assertNotNull("NAV_CONTROL_PRESENT: " + label, found);
    assertTrue("NAV_CONTROL_CLICK: " + label, found.performClick());
  }
  private static View visibleText(View root, String label) {
    if (root.getVisibility() != View.VISIBLE) return null;
    if (root instanceof TextView && label.contentEquals(((TextView) root).getText())) return root;
    if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
      View match = visibleText(((ViewGroup) root).getChildAt(i), label);
      if (match != null) return match;
    }
    return null;
  }
  private static String words(View root) {
    StringBuilder value = new StringBuilder();
    if (root instanceof TextView) value.append(((TextView) root).getText()).append('\n');
    if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
      value.append(words(((ViewGroup) root).getChildAt(i)));
    return value.toString();
  }
}
