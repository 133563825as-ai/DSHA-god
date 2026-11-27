package com.deepseekharness.app.bridge;

import static org.junit.Assert.*;

import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.capabilities.CapabilityResult;
import com.deepseekharness.app.screen.ScreenActivityRecorderTest;
import com.deepseekharness.app.util.AdbResult;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Staged Rebirth-only SDK34 acceptance. No phone, sockets, commands, key files, or credentials. */
@RunWith(ScreenActivityRecorderTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class TrialAdbOffTest {
  @Test
  public void publicNativeEntriesStopBeforeRootfsAndNetworkDiscovery() throws Exception {
    // A default/non-Rebirth build must not accidentally count as acceptance for this test.
    if (!BuildConfig.REBIRTH_CANDIDATE) {
      // The actual ordinary adapter rejects an invalid ticket before touching Context/rootfs.
      var denied = AdbBridge.executeVirtualScreenTyped(null, "unused", "invalid");
      assertEquals(CapabilityResult.State.NOT_SENT, denied.outcome.state);
      assertEquals("VSCREEN_ADB_INVALID_TICKET", denied.outcome.code);
      int[] calls = {0};
      var cancelled =
          AdbVirtualScreenStart.execute(
              "0123456789abcdef0123456789abcdef0123456789abcdef",
              () -> true,
              () -> "cancelled",
              boundary -> {
                calls[0]++;
                throw new AssertionError("NO_HELPER");
              });
      assertEquals(CapabilityResult.State.NOT_SENT, cancelled.outcome.state);
      assertEquals("VSCREEN_ADB_CANCELLED_BEFORE_START", cancelled.outcome.code);
      assertEquals(0, calls[0]);
      return;
    }
    assertTrue(BuildConfig.REBIRTH_CANDIDATE);
    int[] callbacks = {0};
    try {
      AdbBridge.runEnvironmentTask(
          null,
          "fixture",
          () -> {
            callbacks[0]++;
            return "FIXTURE";
          });
      fail("operation admitted");
    } catch (IllegalStateException expected) {
      assertEquals("TRIAL_ADB_UNAVAILABLE", expected.getMessage());
    }
    // The deliberately absent Android context/rootfs is a poison dependency, not a phone.
    for (String reply :
        List.of(
            AdbBridge.ensureReady(null, null, progress -> callbacks[0]++),
            AdbBridge.pair(null, "111111", "1234", "1235", "fixture.invalid"),
            AdbBridge.verify(null, "1235", "fixture.invalid"),
            AdbBridge.probe(null, null),
            AdbBridge.status(null),
            AdbBridge.inject(null, null),
            AdbBridge.applySettings(null, null),
            AdbBridge.extractWheelsJava(null, null))) {
      assertEquals("[POLICY_BLOCKED] TRIAL_ADB_UNAVAILABLE\n[EXIT=126]", reply);
      assertEquals(AdbResult.PairState.FAILED, AdbResult.pairState(reply));
      assertFalse(AdbResult.shellReady(reply));
    }
    assertNull(AdbBridge.discover(null, "fixture", 1L, progress -> callbacks[0]++));
    assertEquals(-1, AdbBridge.savedConnectPort(null));
    assertFalse(AdbBridge.injected(null));
    var launch = AdbBridge.executeVirtualScreenTyped(null, "FIXTURE_NOT_A_COMMAND", "a".repeat(48));
    assertEquals(CapabilityResult.State.NOT_SENT, launch.outcome.state);
    assertEquals("TRIAL_ADB_UNAVAILABLE", launch.outcome.code);
    assertFalse(launch.remoteExitConfirmed);
    assertEquals(0, callbacks[0]);
  }

  @Test
  public void legacyPositiveResultIsNotConnectionAuthority() {
    if (!BuildConfig.REBIRTH_CANDIDATE) {
      assertEquals(AdbResult.PairState.CONNECTED, AdbResult.pairState("CONNECT_OK: fixture"));
      assertEquals(
          AdbResult.PairState.FAILED, AdbResult.pairState("[POLICY_BLOCKED] denied\n[EXIT=126]"));
      var permit = new com.deepseekharness.app.util.OneShotLaunchAuthority();
      String ticket = "0123456789abcdef0123456789abcdef0123456789abcdef";
      assertTrue(permit.issue(ticket, "fixture-not-executed", 4, 100, 1000));
      assertTrue(permit.authorize(ticket, "fixture-not-executed", 4, 101));
      assertFalse(permit.commit(ticket, 5, 102));
      assertTrue(permit.commit(ticket, 4, 102));
      assertFalse(permit.commit(ticket, 4, 103));
      return;
    }
    assertTrue(BuildConfig.REBIRTH_CANDIDATE);
    String oldState = DeviceBridgeService.adbState;
    String oldDetail = DeviceBridgeService.adbDetail;
    try {
      assertFalse(DeviceBridgeService.isAdbEnabled(null));
      assertFalse(DeviceBridgeService.apply(null));
      DeviceBridgeService.recordPairResult(null, AdbResult.PairState.CONNECTED, "SYNTHETIC_OLD");
      assertEquals("disabled", DeviceBridgeService.adbState);
      assertEquals("TRIAL_ADB_UNAVAILABLE", DeviceBridgeService.adbDetail);
    } finally {
      DeviceBridgeService.adbState = oldState;
      DeviceBridgeService.adbDetail = oldDetail;
    }
    // Control: markers are still parsed correctly; refusal is at execution, not the renderer.
    assertEquals(AdbResult.PairState.CONNECTED, AdbResult.pairState("CONNECT_OK: fixture"));
  }
}
