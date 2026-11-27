package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.backup.NativeBackupJobs;
import com.deepseekharness.app.backup.StorageMaintenance;
import com.deepseekharness.app.contract.BuiltinModules;
import com.deepseekharness.app.contract.ModuleRegistry;
import com.deepseekharness.app.core.BackupTask;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.BackupTaskState;
import com.deepseekharness.app.util.UiText;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/** SDK34 真 Settings→Workspace→概览；父页外部业务只提供内存快照，不作为真实环境验收。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = SettingsStorageNavigationTest.App.class,
    qualifiers = "zh-rCN-w360dp-h800dp-mdpi",
    shadows = {
      SettingsStorageNavigationTest.ControllerConfig.class,
      WorkspaceStorageOverviewTest.MemoryConfig.class,
      WorkspaceStorageOverviewTest.Tasks.class,
      WorkspaceStorageOverviewTest.Jobs.class,
      WorkspaceStorageOverviewTest.Environment.class,
      WorkspaceStorageOverviewTest.NoScan.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class WorkspaceStorageOverviewTest {
  @Implements(value = ConfigStore.class, isInAndroidSdk = false)
  public static final class MemoryConfig {
    @Implementation
    protected void __constructor__(Context ignored) {}

    @Implementation
    protected boolean isEcoMode() {
      return false;
    }

    @Implementation
    protected boolean isKeepScreenOn() {
      return false;
    }

    @Implementation
    protected boolean isHideFromRecents() {
      return false;
    }

    @Implementation
    protected boolean isHideRecentsStrict() {
      return false;
    }

    @Implementation
    protected long getLastBackupSuccess() {
      return 0L;
    }

    @Implementation
    protected String getUiLanguagePreference() {
      return "zh";
    }

    @Implementation
    protected String getUiLanguage() {
      return "zh";
    }

    @Implementation
    protected String getUiPalette() {
      return "standard";
    }

    @Implementation
    protected String getLastBackupError() {
      return "";
    }

    @Implementation
    protected String getLastBackupName() {
      return "";
    }
  }

  @Implements(value = BackupTask.class, isInAndroidSdk = false)
  public static final class Tasks {
    @Implementation
    protected static BackupTask get(Context ignored) {
      return org.robolectric.shadow.api.Shadow.newInstanceOf(BackupTask.class);
    }

    @Implementation
    protected BackupTaskState.Snapshot snapshot() {
      return new BackupTaskState().snapshot();
    }

    @Implementation
    protected boolean busy() {
      return false;
    }
  }

  @Implements(value = NativeBackupJobs.class, isInAndroidSdk = false)
  public static final class Jobs {
    @Implementation
    protected static NativeBackupJobs get(Context ignored) {
      return org.robolectric.shadow.api.Shadow.newInstanceOf(NativeBackupJobs.class);
    }

    @Implementation
    protected androidx.lifecycle.LiveData<NativeBackupJobs.State> changes() {
      return new MutableLiveData<>();
    }
  }

  @Implements(value = EnvironmentUiStatus.class, isInAndroidSdk = false)
  public static final class Environment {
    @Implementation
    protected static EnvironmentUiStatus.Snapshot get(Context ignored) {
      return new EnvironmentUiStatus.Snapshot(true, false, true, false);
    }
  }

  @Implements(value = StorageMaintenance.class, isInAndroidSdk = false)
  public static final class NoScan {
    static int calls;

    @Implementation
    protected static java.util.Map<String, StorageMaintenance.Size> inspect(Context ignored) {
      calls++;
      throw new AssertionError("overview must never scan");
    }

    @Implementation
    protected static long clean(Context ignored) {
      calls++;
      throw new AssertionError("overview must never clean");
    }
  }

  @Test
  public void realRouteBackHiddenRestoreAndNoSyntheticOrScan() {
    String previous = UiText.language();
    UiText.setLanguage("zh");
    NoScan.calls = 0;
    var host = Robolectric.buildActivity(SettingsStorageNavigationTest.Host.class).setup();
    try {
      var manager = host.get().getSupportFragmentManager();
      manager
          .beginTransaction()
          .replace(R.id.fragment_container, new SettingsFragment())
          .commitNow();
      click(manager.findFragmentById(R.id.fragment_container).requireView(), "数据与备份");
      manager.executePendingTransactions();
      ShadowLooper.shadowMainLooper().idle();
      Fragment workspace = manager.findFragmentById(R.id.fragment_container);
      assertTrue(workspace instanceof WorkspaceFragment);
      assertTrue(
          "parent maintenance notice retained",
          words(workspace.requireView()).contains("未完成的环境维护"));
      workspace.requireView().findViewById(R.id.workspace_storage).performClick();
      manager.executePendingTransactions();
      Fragment overview = manager.findFragmentById(R.id.fragment_container);
      assertTrue(overview instanceof StorageOverviewFragment);
      String text = words(overview.requireView());
      for (String label : new String[] {"未统计", "设备卷", "逻辑占用", "备份与保留", "文件权限"})
        assertTrue(label, text.contains(label));
      assertFalse(text.contains("合成"));
      assertFalse(text.matches("(?s).*\\d+\\s*(B|GB|MB).*"));
      assertNull(
          org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowActivity>extract(
                  host.get())
              .getNextStartedActivity());
      ModuleRegistry registry = BuiltinModules.ensureRegistered(host.get());
      assertFalse(registry.isRegistered(BuiltinModules.STORAGE_DEMO_MODULE_ID));
      assertEquals(4, BuiltinModuleCenter.productIds().size());
      host.get().getOnBackPressedDispatcher().onBackPressed();
      manager.executePendingTransactions();
      assertTrue(manager.findFragmentById(R.id.fragment_container) instanceof WorkspaceFragment);
      manager
          .findFragmentById(R.id.fragment_container)
          .requireView()
          .findViewById(R.id.workspace_storage)
          .performClick();
      manager.executePendingTransactions();
      registry.setContributionHidden(
          BuiltinModules.STORAGE_OVERVIEW_CONTRIBUTION.globalKey(), true);
      host.recreate();
      host.get().getSupportFragmentManager().executePendingTransactions();
      ShadowLooper.shadowMainLooper().idle();
      Fragment restored =
          host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      assertTrue(restored instanceof StorageOverviewFragment);
      assertTrue(words(restored.requireView()).contains("入口不可用"));
      assertFalse(words(restored.requireView()).contains("逻辑占用"));
      registry = BuiltinModules.ensureRegistered(host.get());
      registry.setContributionHidden(
          BuiltinModules.STORAGE_OVERVIEW_CONTRIBUTION.globalKey(), false);
      host.pause().resume();
      assertTrue("same page recovers after unhide", words(restored.requireView()).contains("逻辑占用"));
      assertFalse(words(restored.requireView()).contains("入口不可用"));
      assertEquals(0, NoScan.calls);
    } finally {
      BuiltinModules.ensureRegistered(host.get())
          .setContributionHidden(BuiltinModules.STORAGE_OVERVIEW_CONTRIBUTION.globalKey(), false);
      host.pause().stop().destroy();
      UiText.setLanguage(previous);
    }
  }

  private static void click(View root, String text) {
    View found = find(root, text);
    assertNotNull(text, found);
    while (found != null && !found.isClickable()) found = (View) found.getParent();
    assertNotNull(text, found);
    assertTrue(found.performClick());
  }

  private static View find(View root, String text) {
    if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View match = find(((ViewGroup) root).getChildAt(i), text);
        if (match != null) return match;
      }
    return null;
  }

  private static String words(View root) {
    StringBuilder text = new StringBuilder();
    if (root instanceof TextView) text.append(((TextView) root).getText()).append('\n');
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
        text.append(words(((ViewGroup) root).getChildAt(i)));
    return text.toString();
  }
}
