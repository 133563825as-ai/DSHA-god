package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.PluginRepository;
import com.deepseekharness.app.plugins.PluginManagementEngine;
import com.deepseekharness.app.plugins.PluginOperationQueue;
import com.deepseekharness.app.util.PluginSource;
import com.deepseekharness.app.util.UiText;
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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;

/** 仅测试安装页自身。应用级普通前台观察不在此夹具内；后端只有计数和内存操作队列。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = SettingsStorageNavigationTest.App.class,
    shadows = {
      PluginInstallActivityConsentTest.Local.class,
      PluginInstallActivityConsentTest.Manager.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class PluginInstallActivityConsentTest {
  private static final String URL = "https://example.invalid/synthetic-only.zip";
  private static final String HASH = "0".repeat(64);

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static final class Local {
    static boolean ready;

    @Implementation
    protected static HarnessController get(Context ignored) {
      return Shadow.newInstanceOf(HarnessController.class);
    }

    @Implementation
    protected boolean isEnvironmentReady() {
      return ready;
    }
  }

  @Implements(value = PluginManagementEngine.class, isInAndroidSdk = false)
  public static final class Manager {
    static int gets, loads, installs;
    static MutableLiveData<PluginRepository.State> state;
    static PluginOperationQueue.Queue<String> queue;
    static com.deepseekharness.app.plugins.PluginRegistry catalog;
    static String last;

    @Implementation
    protected static PluginManagementEngine get(Context ignored) {
      gets++;
      return Shadow.newInstanceOf(PluginManagementEngine.class);
    }

    @Implementation
    protected LiveData<PluginRepository.State> state() {
      return state;
    }

    @Implementation
    protected void ensureLoaded() {
      loads++;
      var path = new java.io.File("synthetic-path-not-read");
      var identity =
          new com.deepseekharness.app.plugins.PluginSourceIdentity(
              path, "data", 1, path, "root", 1, HASH, 1);
      assertTrue(
          catalog.completeQuery(
              catalog.beginQuery(identity), java.util.List.of(), java.util.List.of(), false));
    }

    @Implementation
    protected com.deepseekharness.app.plugins.PluginRegistry registry() {
      return catalog;
    }

    @Implementation
    protected PluginOperationQueue.Operation operation(String id) {
      return queue.get(id);
    }

    @Implementation
    protected String inspect(PluginSource source, String hash, String name, String version) {
      assertEquals(URL, source.url);
      assertEquals(HASH, hash);
      assertEquals("synthetic-only", name);
      assertEquals("1.0", version);
      installs++;
      last =
          queue.enqueue(PluginOperationQueue.Type.INSTALL, "fixture", null, version, null, "inert")
              .operationId;
      return last;
    }
  }

  private ActivityController<PluginInstallActivity> page;
  private String language;

  @Before
  public void setUp() {
    language = UiText.language();
    UiText.setLanguage("en");
    Local.ready = true;
    Manager.gets = Manager.loads = Manager.installs = 0;
    Manager.state = new MutableLiveData<>();
    Manager.catalog = new com.deepseekharness.app.plugins.PluginRegistry();
    Manager.queue = new PluginOperationQueue.Queue<>();
  }

  @After
  public void tearDown() {
    if (page != null) page.pause().stop().destroy();
    UiText.setLanguage(language);
  }

  private static Uri link(String source) {
    return Uri.parse("dsha://install")
        .buildUpon()
        .appendQueryParameter("url", source)
        .appendQueryParameter("name", "synthetic-only")
        .appendQueryParameter("version", "1.0")
        .appendQueryParameter("sha256", HASH)
        .build();
  }

  private void open(Uri uri) {
    page =
        Robolectric.buildActivity(
            PluginInstallActivity.class,
            new Intent(Intent.ACTION_VIEW, uri).putExtra("approved", true));
    page.get().setTheme(R.style.Theme_DeepseekHarness);
    page.setup();
    ShadowLooper.shadowMainLooper().idle();
  }

  private Button action() {
    return page.get().findViewById(R.id.link_install_confirm);
  }

  private CheckBox scripts() {
    ViewGroup group = (ViewGroup) action().getParent();
    for (int i = 0; i < group.getChildCount(); i++)
      if (group.getChildAt(i) instanceof CheckBox box) return box;
    throw new AssertionError("script consent missing");
  }

  private void noManager() {
    assertEquals(0, Manager.gets);
    assertEquals(0, Manager.loads);
    assertEquals(0, Manager.installs);
  }

  @Test
  public void setupResumeAndRecreateNeverSubmitOrLoadManager() {
    Local.ready = false;
    open(link(URL));
    noManager();
    action().performClick(); // 本机检查
    action().performClick(); // 仅初始化
    noManager();
    var prefs = page.get().getSharedPreferences("dsha-install-link", Context.MODE_PRIVATE);
    String pending = prefs.getString("pending", null);
    assertEquals(link(URL).toString(), pending);
    page.pause().stop().destroy();
    Local.ready = true;
    open(Uri.parse(pending)); // 与 MainActivity 交回的 data 相同，不执行真实初始化。
    page.recreate();
    ShadowLooper.shadowMainLooper().idle();
    noManager();
    page.get().findViewById(R.id.link_install_close).performClick();
    noManager();
  }

  @Test
  public void exactSourceAndFreshScriptConsentRequiredForEachSubmission() {
    open(link(URL));
    noManager();
    String details =
        ((TextView) page.get().findViewById(R.id.link_install_details)).getText().toString();
    for (String part :
        new String[] {URL, HASH, "synthetic-only", "1.0", "replaces", "enables", "Scripts"})
      assertTrue(details.contains(part));
    action().performClick();
    assertEquals(1, Manager.loads);
    assertFalse(action().isEnabled());
    action().performClick();
    assertEquals(0, Manager.installs);
    scripts().performClick();
    Manager.catalog.failSource("fixture"); // 更新先于界面观察回调到达
    action().performClick();
    assertEquals(0, Manager.installs);
    assertFalse(scripts().isChecked());
    action().performClick(); // 重新检查，不重放
    scripts().performClick();
    page.pause().resume();
    assertFalse(scripts().isChecked());
    assertEquals(0, Manager.installs);
    scripts().performClick();
    action().performClick();
    action().performClick();
    assertEquals(1, Manager.installs);
    assertNotNull(Manager.queue.poll());
    assertTrue(Manager.queue.complete(Manager.last, PluginOperationQueue.Phase.FAILED, "fixture"));
    Manager.state.setValue(null);
    assertFalse(action().isEnabled());
    action().performClick();
    assertEquals(1, Manager.installs);
    scripts().performClick();
    action().performClick();
    assertEquals(2, Manager.installs);
    int loads = Manager.loads, gets = Manager.gets;
    page.recreate();
    assertEquals(loads, Manager.loads);
    assertEquals(gets, Manager.gets);
    Manager.queue = new PluginOperationQueue.Queue<>(); // 进程丢失操作回执
    action().performClick();
    assertFalse(action().isEnabled());
    assertEquals(2, Manager.installs);
  }

  @Test
  public void obscuredInputRevokesConsentAndCannotSubmit() {
    open(link(URL));
    action().performClick();
    for (int flag :
        new int[] {
          MotionEvent.FLAG_WINDOW_IS_OBSCURED, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        }) {
      scripts().performClick();
      assertTrue(action().isEnabled());
      MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
      pointer.id = 0;
      pointer.toolType = MotionEvent.TOOL_TYPE_FINGER;
      MotionEvent.PointerCoords pos = new MotionEvent.PointerCoords();
      MotionEvent event =
          MotionEvent.obtain(
              1,
              1,
              MotionEvent.ACTION_DOWN,
              1,
              new MotionEvent.PointerProperties[] {pointer},
              new MotionEvent.PointerCoords[] {pos},
              0,
              0,
              1,
              1,
              0,
              0,
              android.view.InputDevice.SOURCE_TOUCHSCREEN,
              flag);
      try {
        assertTrue(page.get().dispatchTouchEvent(event));
      } finally {
        event.recycle();
      }
      assertFalse(scripts().isChecked());
      assertFalse(action().isEnabled());
      assertEquals(0, Manager.installs);
    }
  }

  @Test
  public void opaqueSourceIsNotTruncatedOrEchoedForApproval() {
    open(link(URL + "?label=synthetic-private"));
    assertFalse(action().isEnabled());
    assertFalse(
        ((TextView) page.get().findViewById(R.id.link_install_details))
            .getText()
            .toString()
            .contains("synthetic-private"));
    noManager();
  }
}
