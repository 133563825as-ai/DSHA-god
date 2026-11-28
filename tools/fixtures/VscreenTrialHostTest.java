package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.BuildConfig;
import org.junit.Test;

/** Checks only dry plans: the APK path is synthetic and no device command is executed. */
public final class VscreenTrialHostTest {
  private final String app = AppIdentity.applicationId();
  private final String apk = "/data/app/" + app + "/base.apk";
  private final String prefix =
      "app_process -Djava.class.path="
          + apk
          + " /system/bin com.deepseekharness.app.vscreen.VirtualScreenCore ";
  private final String suffix = " --package " + app;

  private DeviceShellPolicy.Plan inspect(String args) {
    return DeviceShellPolicy.inspectManagedVirtualScreen(prefix + args + suffix, apk, app);
  }

  @Test
  public void trialIdentityRejectsBothLaunchPlans() {
    assertTrue(BuildConfig.REBIRTH_CANDIDATE);
    assertEquals("dsh.android.client.rebirth", app);
    assertFalse(inspect("--launch --port 0").allowed());
    assertFalse(inspect("--launch --physical --port 0").allowed());
  }

  @Test
  public void trialAllowsOnlyTheBoundExitProbe() {
    String probe =
        "--probe-exit --identity 123:456:2000:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee:11111111-2222-3333-4444-555555555555";
    assertEquals(DeviceShellPolicy.Kind.VIRTUAL_SCREEN, inspect(probe).kind);
    assertFalse(inspect("--probe-exit --identity 123").allowed());
    assertFalse(inspect(probe + " --signal 9").allowed());
  }
}
