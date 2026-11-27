package com.deepseekharness.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.PackageManager;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.extdev.ExternalDeviceHost;
import com.deepseekharness.app.screen.ScreenActivityRecorderTest.NoApkRunner;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.DeviceShellPolicy;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.annotation.Config;

/** Candidate lane only; null/empty payloads are deliberately not executable on any backend. */
@RunWith(NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class TrialNativeDeviceOffTest {
  private static final String REFUSAL =
      "[POLICY_BLOCKED] TRIAL_DEVICE_CHANNEL_UNAVAILABLE\n[EXIT=126]";

  @Test
  public void legacyFlagsCannotEnableAChannelAndLocalSettingsStillWork() {
    if (!BuildConfig.REBIRTH_CANDIDATE) {
      Context app = RuntimeEnvironment.getApplication();
      var store = new ConfigStore(app);
      store.setRootShellAllowed(true);
      assertTrue(ConfigStore.deviceAuthorization(app).rootAllowed());
      store.setRootPolicyMode(DeviceShellPolicy.Mode.RELAXED);
      assertEquals(DeviceShellPolicy.Mode.RELAXED, ConfigStore.deviceAuthorization(app).rootMode());
      var prefs = app.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
      assertTrue(prefs.edit().putBoolean("adb_enabled", true).commit());
      assertTrue(ConfigStore.deviceAuthorization(app).adbAllowed());
      store.setRootShellAllowed(false);
      assertFalse(ConfigStore.deviceAuthorization(app).rootAllowed());
      assertTrue(prefs.edit().putBoolean("adb_enabled", false).commit());
      assertFalse(ConfigStore.deviceAuthorization(app).adbAllowed());
      store.setRootPolicyMode(DeviceShellPolicy.Mode.STRICT);
      store.setConfirmShell(false);
      assertFalse(store.isConfirmShell());
      store.setConfirmShell(true);
      assertTrue(store.isConfirmShell());
      return;
    }
    assertTrue(BuildConfig.REBIRTH_CANDIDATE);
    Context app = RuntimeEnvironment.getApplication();
    var prefs = app.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    assertTrue(
        prefs
            .edit()
            .putBoolean(Constants.KEY_ALLOW_ROOT_SHELL, true)
            .putBoolean("adb_enabled", true)
            .putString(Constants.KEY_ROOT_POLICY_MODE, "full")
            .commit());
    var store = new ConfigStore(app);
    var snapshot = ConfigStore.deviceAuthorization(app);
    assertFalse(snapshot.rootAllowed());
    assertFalse(snapshot.adbAllowed());
    assertEquals(DeviceShellPolicy.Mode.STRICT, snapshot.rootMode());
    assertFalse(RootShell.enabled(app));
    assertFalse(RootShell.present());
    assertFalse(store.isRootShellAllowed());
    store.setRootShellAllowed(false);
    store.setRootShellAllowed(true);
    assertFalse(prefs.getBoolean(Constants.KEY_ALLOW_ROOT_SHELL, true));
    store.setRootPolicyMode(DeviceShellPolicy.Mode.STRICT);
    store.setRootPolicyMode(DeviceShellPolicy.Mode.RELAXED);
    assertEquals("strict", prefs.getString(Constants.KEY_ROOT_POLICY_MODE, ""));
    assertFalse(ConfigStore.setAdbEnabled(app, true, true));
    store.setConfirmShell(false);
    assertFalse(store.isConfirmShell());
    store.setConfirmShell(true);
    assertTrue(store.isConfirmShell());
  }

  @Test
  public void directCallsAndReconnectFailBeforeDispatch() {
    if (!BuildConfig.REBIRTH_CANDIDATE) {
      // No Root/Shizuku discovery: an invalid ordinary request is rejected by the actual Binder
      // entry.
      var service = new ShellService();
      String denied = service.exec(null);
      assertTrue(denied.contains("[POLICY_BLOCKED]"));
      assertFalse(denied.contains("TRIAL_DEVICE_CHANNEL_UNAVAILABLE"));
      assertTrue(DeviceShellPolicy.inspect("cat /fixture/one").allowed());
      assertFalse(DeviceShellPolicy.inspect(null).allowed());
      return;
    }
    assertTrue(BuildConfig.REBIRTH_CANDIDATE);
    var service = new ShellService();
    for (String output :
        List.of(
            service.exec(null),
            service.execVirtualScreen(null),
            service.execAuthorizedSms(null, -1),
            service.execExternal(null, null, 0),
            service.execExternalAdbSession(null, null, null, 0),
            service.execExternalAdbSteps(null, null, null, 0, null, false)))
      assertEquals(REFUSAL, output);
    // 03 is a distinct guard: the raw entry never falls back to generic exec.
    assertEquals(
        "[POLICY_BLOCKED] TRIAL_RAW_DEVICE_SHELL_UNAVAILABLE\n[EXIT=126]",
        service.execUnrestricted(null));
    ShizukuShell.init(null);
    ShizukuShell.ensureBound(null);
    assertFalse(ShizukuShell.awaitReady(null, 0));
    assertFalse(ShizukuShell.isAvailable());
    assertFalse(ShizukuShell.hasPermission());
    assertNull(ShizukuManagerCompat.manager(null));
    AtomicInteger callback = new AtomicInteger(99);
    ShizukuShell.requestPermission((code, result) -> callback.set(result));
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(PackageManager.PERMISSION_DENIED, callback.get());
  }

  @Test
  public void staleExternalBindingAndCallbacksCannotReachTarget() throws Exception {
    if (!BuildConfig.REBIRTH_CANDIDATE) {
      // ExternalDeviceHost's ordinary constructor opens an ephemeral socket. Use its immutable
      // binding contract instead; do not pretend that ordinary transport was exercised here.
      var binding =
          new com.deepseekharness.app.util.ExternalTargetBinding(
              "SYNTHETIC_TARGET", "fake", "fingerprint", 21L);
      assertTrue(binding.matches("SYNTHETIC_TARGET", "fake", "fingerprint"));
      assertFalse(binding.matches("OTHER_TARGET", "fake", "fingerprint"));
      assertEquals(21L, binding.epoch);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new com.deepseekharness.app.util.ExternalTargetBinding(
                  "SYNTHETIC_TARGET", "fake", "fingerprint", 0L));
      return;
    }
    assertTrue(BuildConfig.REBIRTH_CANDIDATE);
    Context app = RuntimeEnvironment.getApplication();
    var prefs = app.getSharedPreferences("dsha_external_device_grants", Context.MODE_PRIVATE);
    assertTrue(
        prefs
            .edit()
            .putBoolean("external_device_enabled_v1", true)
            .putString("target_serial_v1", "SYNTHETIC_TARGET")
            .putString("target_product_v1", "fake")
            .putString("target_fingerprint_v1", "fake")
            .putLong("target_epoch_v1", 21L)
            .commit());
    AtomicInteger authorizations = new AtomicInteger();
    var host =
        new ExternalDeviceHost(
            app,
            () -> {
              authorizations.incrementAndGet();
              return true;
            });
    assertFalse(host.enabled());
    assertFalse(host.setEnabled(true));
    assertNull(host.confirmationTarget());
    assertNull(host.binding());
    assertFalse(host.status().has("serial"));
    assertEquals("TRIAL_DEVICE_CHANNEL_UNAVAILABLE", host.status().getString("reason"));
    // Fake inputs stop before device discovery, metadata inspection, file resolution or transport.
    assertThrows(IOException.class, host::discover);
    assertThrows(IOException.class, () -> host.connect(null));
    assertThrows(IOException.class, () -> host.pair(null));
    assertThrows(IOException.class, () -> host.operate(null, null, null, true));
    assertThrows(IOException.class, () -> host.importImage(null));
    assertThrows(IOException.class, host::importRoot);
    assertEquals(0, authorizations.get());
    assertTrue(host.forgetTarget());
    assertFalse(prefs.contains("target_serial_v1"));
  }
}
