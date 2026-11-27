package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.contract.BuiltinModules;
import com.deepseekharness.app.contract.ModuleRegistry;
import com.deepseekharness.app.util.StartupHistoryStore;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
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
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

/** 原 Activity/布局/点击/弹层，读接口只返回合成记录，绝不构造真实 history store。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = SettingsStorageNavigationTest.App.class,
    qualifiers = "zh-rCN-w360dp-h800dp-mdpi",
    shadows = DiagnosticStartupHistoryModuleTest.Reads.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DiagnosticStartupHistoryModuleTest {
  @Implements(value = DiagnosticActivity.class, isInAndroidSdk = false)
  public static final class Reads extends ShadowActivity {
    static ArrayDeque<Runnable> workers;
    static StartupHistoryReadOwner.Reader reader;
    static int reads;

    @Implementation
    protected static StartupHistoryReadOwner newHistoryOwner(
        BooleanSupplier active, Executor delivery) {
      return new StartupHistoryReadOwner(active, workers::add, delivery);
    }

    @Implementation
    protected static StartupHistoryReadOwner.Reader historyReader(Context context) {
      return () -> {
        reads++;
        return reader.read();
      };
    }
  }

  private ActivityController<DiagnosticActivity> page;
  private ModuleRegistry registry;
  private String language;
  private String key;

  @Before
  public void setup() {
    language = UiText.language();
    UiText.setLanguage("zh");
    Reads.workers = new ArrayDeque<>();
    Reads.reader = List::of;
    Reads.reads = 0;
    registry =
        BuiltinModules.ensureProductModules(org.robolectric.RuntimeEnvironment.getApplication());
    key = BuiltinModules.STARTUP_HISTORY_CONTRIBUTION.globalKey();
    registry.setEnabled(BuiltinModules.STARTUP_HISTORY_MODULE_ID, true);
    registry.setContributionHidden(key, false);
    page = open();
  }

  private ActivityController<DiagnosticActivity> open() {
    var result = Robolectric.buildActivity(DiagnosticActivity.class);
    result.get().setTheme(R.style.Theme_DeepseekHarness);
    return result.setup();
  }

  @After
  public void teardown() {
    if (page != null) page.pause().stop().destroy();
    while (!Reads.workers.isEmpty()) Reads.workers.remove().run();
    ShadowLooper.shadowMainLooper().idle();
    registry.setEnabled(BuiltinModules.STARTUP_HISTORY_MODULE_ID, true);
    registry.setContributionHidden(key, false);
    UiText.setLanguage(language);
  }

  private void click() {
    assertTrue(page.get().findViewById(R.id.diagnostic_history).performClick());
  }

  private void complete() {
    assertEquals(1, Reads.workers.size());
    Reads.workers.remove().run();
    ShadowLooper.shadowMainLooper().idle();
  }

  private androidx.appcompat.app.AlertDialog dialog() {
    return (androidx.appcompat.app.AlertDialog) ShadowDialog.getLatestDialog();
  }

  private static View text(View view, String value) {
    if (view instanceof TextView label && label.getText().toString().equals(value)) return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = text(group.getChildAt(i), value);
        if (found != null) return found;
      }
    return null;
  }

  private static StartupHistoryStore.Entry entry() throws Exception {
    var ctor =
        StartupHistoryStore.Entry.class.getDeclaredConstructor(
            String.class, Properties.class, String.class);
    ctor.setAccessible(true);
    Properties properties = new Properties();
    properties.setProperty("started", "1700000000000");
    properties.setProperty("status", "failed");
    properties.setProperty("zhStage", "合成阶段");
    properties.setProperty("zhLog", "合成脱敏时间线");
    properties.setProperty("reason", "合成失败原因");
    return ctor.newInstance("synthetic-only", properties, "zh");
  }

  @Test
  public void clickRendersSyntheticHistoryAndDetail() throws Exception {
    assertEquals(0, Reads.reads);
    assertTrue(Reads.workers.isEmpty());
    Reads.reader = () -> List.of(entry());
    click();
    var history = dialog();
    click();
    assertSame(history, dialog());
    complete();
    assertEquals(1, Reads.reads);
    assertNotNull(
        text(
            history.getWindow().getDecorView(),
            page.get().getString(R.string.ui134_recent_startups)));
    View row = text(history.getWindow().getDecorView(), "合成阶段 · failed");
    assertNotNull(row);
    assertTrue(((View) row.getParent().getParent()).performClick());
    assertNotSame(history, dialog());
    assertNotNull(text(dialog().getWindow().getDecorView(), "合成阶段\n\n合成失败原因\n\n合成脱敏时间线"));
    assertEquals(1, Reads.reads);
  }

  @Test
  public void emptyFailureAndCloseDropDelivery() {
    click();
    complete();
    assertNotNull(text(dialog().getWindow().getDecorView(), "还没有启动记录。"));
    text(dialog().getWindow().getDecorView(), "关闭").performClick();
    Reads.reader =
        () -> {
          throw new java.io.IOException("synthetic failure");
        };
    click();
    complete();
    assertNotNull(text(dialog().getWindow().getDecorView(), "无法读取启动记录，请稍后重试。"));
    dialog().dismiss();
    click();
    var closed = dialog();
    text(closed.getWindow().getDecorView(), "关闭").performClick();
    click();
    var busy = dialog();
    assertNotNull(text(busy.getWindow().getDecorView(), "上次读取尚未结束，请关闭后重试。"));
    complete();
    assertFalse(closed.isShowing());
    assertSame(busy, dialog());
    assertNotNull(text(busy.getWindow().getDecorView(), "上次读取尚未结束，请关闭后重试。"));
  }

  @Test
  public void hideRecreateRevokeAndRecoveryStayIndependent() {
    var lease = BuiltinModules.startupHistoryLease(registry);
    click();
    var old = dialog();
    registry.setContributionHidden(key, true);
    assertTrue(lease.active());
    complete();
    assertTrue(old.isShowing()); // 隐藏不撤销已打开页的读取。
    page.recreate();
    assertFalse(old.isShowing());
    assertEquals(View.GONE, page.get().findViewById(R.id.diagnostic_history).getVisibility());
    assertEquals(1, Reads.reads);
    registry.setContributionHidden(key, false);
    page.pause().stop().start().resume();
    click();
    var pending = dialog();
    page.recreate();
    assertFalse(pending.isShowing());
    click();
    assertEquals(1, Reads.workers.size()); // 重建的新 owner 不能另起真实 IO。
    complete();
    assertNotNull(text(dialog().getWindow().getDecorView(), "上次读取尚未结束，请关闭后重试。"));
    assertTrue(registry.revoke(key).accepted());
    ShadowLooper.shadowMainLooper().idle();
    assertFalse(lease.active());
    assertFalse(page.get().isFinishing());
    assertEquals(View.GONE, page.get().findViewById(R.id.diagnostic_history).getVisibility());
    assertTrue(page.get().findViewById(R.id.diagnostic_recovery).performClick());
    Intent recovery =
        org.robolectric.shadow.api.Shadow.<ShadowActivity>extract(page.get())
            .getNextStartedActivity();
    assertEquals(StartupRecoveryActivity.class.getName(), recovery.getComponent().getClassName());
    assertTrue(registry.isInstalled(BuiltinModules.ABOUT_MODULE_ID));
    assertTrue(registry.isInstalled(BuiltinModules.THEME_MODULE_ID));
    assertTrue(registry.setEnabled(BuiltinModules.STARTUP_HISTORY_MODULE_ID, true).accepted());
    assertNotEquals(lease.generation(), BuiltinModules.startupHistoryLease(registry).generation());
    page.pause().stop().start().resume();
    assertEquals(View.VISIBLE, page.get().findViewById(R.id.diagnostic_history).getVisibility());
    click();
    assertEquals(1, Reads.workers.size());
    registry.revoke(key);
    ShadowLooper.shadowMainLooper().idle();
    var retired = dialog();
    complete();
    assertFalse(retired.isShowing());
  }
}
