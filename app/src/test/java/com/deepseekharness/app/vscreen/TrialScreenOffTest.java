package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.screen.AccessibilityRepairOwner;
import com.deepseekharness.app.screen.ScreenActivityRecorderTest;
import com.deepseekharness.app.screen.ScreenMode;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.ScreenModePolicy;
import com.deepseekharness.app.util.ScreenModePolicy.Domain;
import com.deepseekharness.app.util.ScreenModePolicy.Mode;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Dedicated candidate-only acceptance source; no APK, real device, transport or commands. */
@RunWith(ScreenActivityRecorderTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class TrialScreenOffTest {
  @Test
  public void storedGrantDoesNotOpenPhysicalOrVirtualModeAfterRestart() {
    if (!BuildConfig.REBIRTH_CANDIDATE) {
      Context ctx = RuntimeEnvironment.getApplication();
      var prefs = ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
      assertTrue(prefs.edit().putString(ScreenMode.KEY, "physical").commit());
      assertEquals(Mode.PHYSICAL, ScreenMode.current(ctx));
      assertNull(ScreenMode.bridgeRejection(ctx, Domain.PHYSICAL, "tree"));
      assertNotNull(ScreenMode.bridgeRejection(ctx, Domain.VIRTUAL, "tree"));
      assertTrue(prefs.edit().putString(ScreenMode.KEY, "off").commit());
      assertEquals(Mode.OFF, ScreenMode.current(ctx));
      assertNotNull(ScreenMode.bridgeRejection(ctx, Domain.PHYSICAL, "tree"));
      assertNull(ScreenMode.bridgeRejection(ctx, Domain.PHYSICAL, "task-end"));
      return;
    }
    assertTrue(BuildConfig.REBIRTH_CANDIDATE);
    Context ctx = RuntimeEnvironment.getApplication();
    for (String stale : List.of("virtual", "physical", "future-mode")) {
      assertTrue(
          ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
              .edit()
              .putString(ScreenMode.KEY, stale)
              .commit());
      assertEquals(Mode.OFF, ScreenMode.current(ctx));
      for (Mode target : List.of(Mode.PHYSICAL, Mode.VIRTUAL)) {
        var denied = ScreenMode.request(ctx, target, "portrait");
        assertEquals(ScreenModePolicy.Switch.NOT_ADMITTED, denied.outcome);
        assertEquals("TRIAL_SCREEN_UNAVAILABLE", denied.code);
        assertNull(denied.start);
      }
      assertEquals(Mode.OFF, ScreenMode.current(ctx));
    }
    assertTrue(ScreenMode.request(ctx, Mode.OFF, "").ok());
    assertNull(ScreenMode.bridgeRejection(ctx, Domain.PHYSICAL, "task-end"));
    assertNull(ScreenMode.bridgeRejection(ctx, Domain.VIRTUAL, "close"));
    for (Domain domain : Domain.values())
      for (String op :
          List.of(
              "tree", "screenshot", "history", "senseState", "tap", "create", "preview", "future"))
        assertEquals("TRIAL_SCREEN_UNAVAILABLE", ScreenMode.bridgeRejection(ctx, domain, op));
  }

  @Test
  public void fakeChannelAndPairListenerNeverRun() throws Exception {
    if (!BuildConfig.REBIRTH_CANDIDATE) {
      Context app = RuntimeEnvironment.getApplication();
      int[] calls = {0, 0};
      var manager =
          new PhysicalScreenManager(
              app,
              ctx -> {
                calls[0]++;
                return null;
              },
              (ep, route, query) -> {
                calls[1]++;
                throw new AssertionError("NO_TRANSPORT");
              },
              () -> 1L);
      assertFalse(manager.frame().getBoolean("ok"));
      assertEquals(1, calls[0]);
      assertEquals(0, calls[1]);
      assertTrue(manager.close().getBoolean("ok"));
      assertFalse(manager.running());
      return;
    }
    assertTrue(BuildConfig.REBIRTH_CANDIDATE);
    var channels =
        new VirtualScreenManager.Channels() {
          public VirtualScreenChannel choose(Context c) {
            throw new AssertionError("CHANNEL_CHOSEN");
          }

          public VirtualScreenChannel find(String id) {
            throw new AssertionError("CHANNEL_PROBED");
          }
        };
    var manager =
        new VirtualScreenManager(
            null,
            () -> 100L,
            new VirtualScreenManager.Visuals() {
              public void present() {
                throw new AssertionError("FRAME_PRESENTED");
              }

              public void stopped(long f) {}
            },
            channels);
    var refused = manager.start("portrait");
    assertEquals("NOT_SENT", refused.getString("state"));
    assertEquals("TRIAL_SCREEN_UNAVAILABLE", refused.getString("error"));
    assertFalse(manager.preview().optBoolean("ok"));
    assertFalse(manager.tree().optBoolean("ok"));
    assertFalse(manager.authorizeAdbLaunchPlan("mockTicket", "dummy"));
    assertFalse(manager.commitAdbLaunch("mockTicket"));
    assertTrue(manager.close().getBoolean("ok"));
    DshaAccessibilityService.startWatch(
        (a, b, c, d) -> {
          throw new AssertionError("PAIR_CALLBACK");
        });
    assertFalse(DshaAccessibilityService.watching());
    assertFalse(DshaAccessibilityService.enabled(null));
    assertFalse(
        AccessibilityRepairOwner.afterAuthenticatedProbe(
            null,
            "FAKE",
            "FAKE",
            1,
            () -> {
              throw new AssertionError("REPAIR_PROBED");
            }));
    var fake = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
    try {
      fake.setPackageName("test.synthetic.target");
      new DshaAccessibilityService().onAccessibilityEvent(fake);
      assertFalse(DshaAccessibilityService.isConnected());
    } finally {
      fake.recycle();
    }
  }
}
