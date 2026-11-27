package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.control.ControlTaskAuthority;
import com.deepseekharness.app.core.DeviceGrants;
import com.deepseekharness.app.screen.ScreenActivityRecorder;
import com.deepseekharness.app.screen.ScreenActivityRecorderTest;
import com.deepseekharness.app.util.DeviceShellPolicy;
import com.deepseekharness.app.util.ScreenActivityEvent;
import com.deepseekharness.app.util.ScreenActivityFeed;
import com.deepseekharness.app.util.ScreenTarget;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Staged acceptance test: run specifically against the Rebirth candidate; wrong variant is red. */
@RunWith(ScreenActivityRecorderTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class TrialCapabilityBoundaryTest {
  @Test
  public void legacyGrantAndDirectCallsCannotStartOrDispatch() throws Exception {
    if (!BuildConfig.REBIRTH_CANDIDATE) {
      Context app = RuntimeEnvironment.getApplication();
      DeviceGrants grants = new DeviceGrants(app);
      assertTrue(grants.setPrivilegedExecutionAllowed(true));
      assertTrue(grants.privilegedExecutionAllowed());
      assertTrue(grants.setPrivilegedExecutionAllowed(false));
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
      assertFalse(manager.info().getBoolean("ok"));
      assertEquals(1, calls[0]);
      assertEquals(0, calls[1]);
      assertTrue(manager.close().getBoolean("ok"));
      return;
    }
    assertTrue(BuildConfig.REBIRTH_CANDIDATE);
    Context app = RuntimeEnvironment.getApplication();
    var prefs = app.getSharedPreferences("dsha_device_grants", Context.MODE_PRIVATE);
    assertTrue(prefs.edit().putBoolean("privileged_execution_v1", true).commit());
    DeviceGrants grants = new DeviceGrants(app);
    assertFalse(grants.privilegedExecutionAllowed());
    assertFalse(grants.setPrivilegedExecutionAllowed(true));
    assertTrue(grants.setPrivilegedExecutionAllowed(false));
    int[] calls = {0, 0};
    JSONObject marker = new JSONObject().put("observationId", "SYNTHETIC_B");
    var manager =
        new PhysicalScreenManager(
            app,
            ctx -> {
              calls[0]++;
              return null;
            },
            (ep, route, query) -> {
              calls[1]++;
              return marker;
            },
            () -> 1L);
    for (var result :
        List.of(
            manager.info(),
            manager.frame(),
            manager.tap(5, 4, 0),
            manager.key(4, 0),
            manager.swipe(1, 2, 3, 4, 50, 0),
            manager.configure(640, 50, 3),
            manager.benchmark(2, 5, 4, 0),
            manager.tap(5, 4, 0))) assertPhysicalRefusal(result);
    for (String op : List.of("info", "frame", "tap", "key", "swipe", "config", "future"))
      assertPhysicalRefusal(
          PhysicalScreenCore.route("/priv/" + op, Map.of("expectedPackage", "fake.a")));
    assertTrue(manager.close().getBoolean("ok"));
    assertTrue(PhysicalScreenCore.route("/priv/close", Map.of()).getBoolean("ok"));
    assertTrue(PhysicalScreenCore.route("/priv/ping", Map.of()).getBoolean("ok"));
    assertArrayEquals(new int[] {0, 0}, calls);
  }

  private static void assertPhysicalRefusal(JSONObject result) throws Exception {
    assertFalse(result.getBoolean("ok"));
    assertEquals("NOT_SENT", result.getString("state"));
    assertEquals("TRIAL_PHYSICAL_SCREEN_UNAVAILABLE", result.getString("error"));
    assertFalse(result.getBoolean("automaticRetry"));
    assertFalse(result.has("observationId"));
    assertFalse(result.has("observation"));
    assertFalse(result.has("frame"));
    assertFalse(result.toString().contains("SYNTHETIC_B"));
  }

  @Test
  public void recorderDoesNotStartCollectOrDeliverEvenWithOldFeed() throws Exception {
    if (!BuildConfig.REBIRTH_CANDIDATE) {
      var task =
          new ControlTaskAuthority.Binding(
              ControlTaskAuthority.Owner.LOCAL_DSH, "fixture", "normal", "1");
      ScreenActivityRecorder.clear();
      try {
        ScreenActivityRecorder.observeTask(RuntimeEnvironment.getApplication(), task);
        ScreenActivityRecorder.assistantGesture(
            "tap", new ScreenTarget(0, 1, "com.example.fixture"), 1, 2, 1, 2);
        var reply = new JSONObject(ScreenActivityRecorder.attach("{\"ok\":true}", task));
        assertEquals(1, reply.getJSONArray("recentActivity").length());
        assertEquals(
            1,
            new JSONObject(ScreenActivityRecorder.history("0", "20", task.identity()))
                .getJSONArray("events")
                .length());
        assertEquals(
            0,
            new JSONObject(ScreenActivityRecorder.history("0", "20", "other-task"))
                .getJSONArray("events")
                .length());
      } finally {
        ScreenActivityRecorder.clear();
      }
      return;
    }
    assertTrue(BuildConfig.REBIRTH_CANDIDATE);
    var task =
        new ControlTaskAuthority.Binding(ControlTaskAuthority.Owner.LOCAL_DSH, "fake", "task", "1");
    var target = new ScreenTarget(0, 1, "fake.b");
    var field = ScreenActivityRecorder.class.getDeclaredField("FEED");
    field.setAccessible(true);
    var feed = (ScreenActivityFeed) field.get(null);
    var click = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
    var node = AccessibilityNodeInfo.obtain();
    node.setText("SYNTHETIC_B");
    click.setPackageName("fake.b");
    ScreenActivityRecorder.clear();
    try {
      ScreenActivityRecorder.observeTask(RuntimeEnvironment.getApplication(), task);
      assertFalse(feed.recording());
      // Inject old in-memory state, not a product setter; touch neither device nor user data.
      assertTrue(feed.begin(task.identity()));
      feed.record(
          new ScreenActivityEvent(
              0,
              0,
              ScreenActivityEvent.Kind.USER_TAP,
              ScreenActivityEvent.Source.USER,
              "fake.b",
              "",
              1,
              "SYNTHETIC_OLD",
              "",
              0,
              0,
              false,
              false,
              ""));
      ScreenActivityRecorder.recordAccessibilityEvent(click);
      ScreenActivityRecorder.assistantNode("tap", target, node);
      ScreenActivityRecorder.assistantKey("home", target);
      ScreenActivityRecorder.assistantGesture("tap", target, 1, 1, 2, 2);
      assertEquals(1, feed.size());
      for (String reply : List.of("{\"ok\":true}", "{\"ok\":false}", "OK"))
        assertEquals(reply, ScreenActivityRecorder.attach(reply, task));
      for (String owner : List.of(task.identity(), "other-task")) {
        var history = new JSONObject(ScreenActivityRecorder.history("0", "20", owner));
        assertEquals("NOT_SENT", history.getString("state"));
        assertEquals("TRIAL_ACTIVITY_HISTORY_UNAVAILABLE", history.getString("error"));
        assertFalse(history.has("events"));
        assertFalse(history.toString().contains("SYNTHETIC"));
      }
    } finally {
      ScreenActivityRecorder.clear();
      click.recycle();
      node.recycle();
    }
    assertEquals(0, feed.size());
  }

  @Test
  public void strictPlansRefuseWalkersAndPreserveSingleFileControl() {
    if (!BuildConfig.REBIRTH_CANDIDATE) {
      for (String input :
          List.of("find /fixture", "du /fixture", "ls /fixture", "grep TOKEN /fixture"))
        assertEquals(DeviceShellPolicy.Kind.READ, DeviceShellPolicy.inspect(input).kind);
      assertTrue(
          DeviceShellPolicy.inspectFileArguments(
                  List.of("cp", "-r", "/fixture", "/data/local/tmp/fake"))
              .allowed());
      for (var mode : List.of(DeviceShellPolicy.Mode.RELAXED, DeviceShellPolicy.Mode.FULL))
        assertEquals(
            DeviceShellPolicy.Kind.SHELL, DeviceShellPolicy.inspect("fake-operation", mode).kind);
      assertEquals(DeviceShellPolicy.Kind.READ, DeviceShellPolicy.inspect("cat /fixture/one").kind);
      return;
    }
    assertTrue(BuildConfig.REBIRTH_CANDIDATE);
    for (String input :
        List.of("find /fixture", "du /fixture", "ls /fixture", "grep TOKEN /fixture")) {
      var plan = DeviceShellPolicy.inspect(input);
      assertFalse(plan.allowed());
      assertTrue(plan.argv.isEmpty());
      assertTrue(plan.reason.contains("TRIAL_DEVICE_TRAVERSAL_UNAVAILABLE"));
    }
    assertFalse(
        DeviceShellPolicy.inspectFileArguments(
                List.of("cp", "-r", "/fixture", "/data/local/tmp/fake"))
            .allowed());
    for (var mode : List.of(DeviceShellPolicy.Mode.RELAXED, DeviceShellPolicy.Mode.FULL))
      assertFalse(DeviceShellPolicy.inspect("fake-operation", mode).allowed());
    assertEquals(DeviceShellPolicy.Kind.READ, DeviceShellPolicy.inspect("cat /fixture/one").kind);
  }
}
