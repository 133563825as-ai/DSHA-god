package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.R;
import com.deepseekharness.app.contract.*;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.SystemLanguage;
import com.deepseekharness.app.util.UiText;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

/** SDK34 真 Settings 行与原 Picker 点击；配置不伪造，仅隔离无关宿主运行时。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = SettingsStorageNavigationTest.App.class,
    qualifiers = "zh-rCN-w360dp-h800dp-mdpi",
    shadows = SettingsBuiltinModuleCompositionTest.ControllerConfig.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SettingsLanguageModuleTest {
  private ActivityController<SettingsStorageNavigationTest.Host> host;
  private ModuleRegistry registry;
  private Locale original;

  @Before
  public void setup() throws Exception {
    original = Locale.getDefault();
    resetSystem();
    var initialize =
        SystemLanguage.class.getDeclaredMethod("initializeFromSystemTag", String.class);
    initialize.setAccessible(true);
    initialize.invoke(null, "ja-JP");
    host = Robolectric.buildActivity(SettingsStorageNavigationTest.Host.class).setup().visible();
    new ConfigStore(host.get()).setUiLanguage("system");
    LanguageController.apply(host.get());
    registry = BuiltinModules.ensureProductModules(host.get());
    settings();
  }

  @After
  public void teardown() throws Exception {
    if (host != null) host.pause().stop().destroy();
    drain();
    Locale.setDefault(original);
    UiText.setLanguage("zh");
    resetSystem();
  }

  private static void resetSystem() throws Exception {
    var reset = SystemLanguage.class.getDeclaredMethod("resetForTest");
    reset.setAccessible(true);
    reset.invoke(null);
  }

  private View settings() {
    host.get()
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, new SettingsFragment())
        .commitNow();
    return root();
  }

  private View root() {
    return host.get()
        .getSupportFragmentManager()
        .findFragmentById(R.id.fragment_container)
        .requireView();
  }

  private AlertDialog open() {
    assertTrue(root().findViewById(R.id.settings_appearance).performClick());
    AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
    assertNotNull(dialog);
    assertTrue(dialog.isShowing());
    return dialog;
  }

  private void pick(AlertDialog dialog, String value) {
    View tile = dialog.getWindow().getDecorView().findViewWithTag(value);
    assertNotNull(tile);
    assertTrue(tile.performClick());
  }

  private static void drain() {
    ShadowLooper.shadowMainLooper().idle();
  }

  private void preference(String expected) {
    assertEquals(expected, new ConfigStore(host.get()).getUiLanguagePreference());
    assertEquals(
        expected,
        host.get()
            .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
            .getString("ui_language", null));
  }

  @Test
  public void actualPickerSystemExplicitRecreateSystemKeepsLiteralAndSummary() {
    assertSame(registry, BuiltinModules.ensureRegistered(host.get()));
    assertNotNull(SettingsFragment.visibleLanguage(registry));
    assertEquals(
        ContractCodes.MODULE_ALWAYS_ON,
        registry.setEnabled(BuiltinModules.LANGUAGE_MODULE_ID, false).code());
    assertEquals(
        BuiltinModules.LANGUAGE_CONTRIBUTION.globalKey(),
        root().findViewById(R.id.settings_appearance).getTag());
    pick(open(), "ar");
    drain();
    preference("ar");
    assertEquals("ar", UiText.language());
    assertEquals("١٢٣", String.format(Locale.getDefault(), "%d", 123));
    host.recreate();
    assertEquals(
        "العربية", ((TextView) root().findViewById(R.id.settings_language)).getText().toString());
    pick(open(), "system");
    drain();
    preference("system");
    assertEquals("ja", UiText.language());
    assertEquals("123", String.format(Locale.getDefault(), "%d", 123));
    host.recreate();
    assertEquals(
        "日本語", ((TextView) root().findViewById(R.id.settings_language)).getText().toString());
  }

  @Test
  public void withdrawnQueuedConfirmationCannotBorrowRecoveryButNextClickCan() {
    var captured = BuiltinModules.languageLease(registry);
    pick(open(), "fr");
    assertTrue(registry.revoke(BuiltinModules.LANGUAGE_CONTRIBUTION.globalKey()).accepted());
    assertTrue("generic ALWAYS_ON revoke intentionally leaves lease active", captured.active());
    assertFalse(BuiltinModules.languageActive(registry, captured));
    drain();
    preference("system");
    assertNull(SettingsFragment.visibleLanguage(registry));
    pick(open(), "fr");
    assertEquals("host-language-recovery", root().findViewById(R.id.settings_appearance).getTag());
    drain();
    preference("fr");
    host.recreate();
    pick(open(), "system");
    drain();
    preference("system");
    assertEquals("ja", UiText.language());
  }

  @Test
  public void queuedStopDestroyViewAndReopenedDialogNeverCommitOldChoice() {
    pick(open(), "fr");
    host.pause().stop();
    drain();
    preference("system");
    host.start().resume();
    pick(open(), "fr");
    settings();
    drain();
    preference("system");
    pick(open(), "fr");
    AlertDialog next = open();
    drain();
    preference("system");
    next.cancel();
    drain();
    preference("system");
    AlertDialog once = open();
    pick(once, "fr");
    pick(once, "ar");
    drain();
    preference("fr");
    pick(open(), "ar");
    assertTrue(
        host.get()
            .getSupportFragmentManager()
            .findFragmentById(R.id.fragment_container)
            .isResumed());
    host.get().finish();
    assertTrue(host.get().isFinishing());
    // finish 已生效但 STOP 尚未分发，不能只依赖 Fragment.isResumed / onStop。
    assertTrue(
        host.get()
            .getSupportFragmentManager()
            .findFragmentById(R.id.fragment_container)
            .isResumed());
    drain();
    preference("fr");
  }

  @Test
  public void hiddenAndClosedModuleKeepSameRecoveryRowAndPicker() {
    assertTrue(
        registry
            .setContributionHidden(BuiltinModules.LANGUAGE_CONTRIBUTION.globalKey(), true)
            .accepted());
    assertNull(SettingsFragment.visibleLanguage(registry));
    assertEquals(View.VISIBLE, root().findViewById(R.id.settings_appearance).getVisibility());
    pick(open(), "fr");
    drain();
    preference("fr");
    host.recreate();
    assertTrue(
        registry
            .setContributionHidden(BuiltinModules.LANGUAGE_CONTRIBUTION.globalKey(), false)
            .accepted());
    var old = BuiltinModules.languageLease(registry);
    pick(open(), "ar");
    registry.close(BuiltinModules.LANGUAGE_MODULE_ID, ModuleInstance.CloseReason.HOST_CLOSE);
    assertTrue(registry.install(BuiltinModules.LANGUAGE_MODULE_ID).accepted());
    assertNotEquals(old.generation(), BuiltinModules.languageLease(registry).generation());
    drain();
    preference("fr");
    registry.close(BuiltinModules.LANGUAGE_MODULE_ID, ModuleInstance.CloseReason.HOST_CLOSE);
    pick(open(), "system");
    drain();
    preference("system");
  }
}
