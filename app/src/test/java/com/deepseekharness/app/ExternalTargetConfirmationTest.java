package com.deepseekharness.app;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import com.deepseekharness.app.extdev.ExternalDeviceHost;
import com.deepseekharness.app.util.ExternalTargetBinding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(com.deepseekharness.app.core.PluginRepositoryQueryLifecycleTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public final class ExternalTargetConfirmationTest {
  private Context app;
  private SharedPreferences prefs;
  private ExternalDeviceHost settings;
  private final AtomicBoolean requestLive = new AtomicBoolean(true);
  private final AtomicInteger requestChecks = new AtomicInteger();

  @Before
  public void setUp() {
    app = RuntimeEnvironment.getApplication();
    prefs = app.getSharedPreferences("dsha_external_device_grants", Context.MODE_PRIVATE);
    settings = new ExternalDeviceHost(app);
    if (BuildConfig.REBIRTH_CANDIDATE) {
      assertFalse(settings.setEnabled(true));
      // Simulate a preference left by an older install; it is not authority in Rebirth.
      assertTrue(prefs.edit().putBoolean("external_device_enabled_v1", true).commit());
      assertFalse(settings.enabled());
    } else {
      assertTrue(settings.setEnabled(true));
    }
    bindFixture("first", "first-fingerprint", 7);
  }

  private void bindFixture(String serial, String fingerprint, long epoch) {
    assertTrue(
        prefs
            .edit()
            .putString("target_serial_v1", serial)
            .putString("target_product_v1", "product")
            .putString("target_fingerprint_v1", fingerprint)
            .putLong("target_epoch_v1", epoch)
            .commit());
  }

  private ExternalDeviceHost capture() {
    return new ExternalDeviceHost(
        app,
        () -> {
          requestChecks.incrementAndGet();
          return requestLive.get();
        });
  }

  private boolean current(ExternalDeviceHost host) {
    try {
      Method method = ExternalDeviceHost.class.getDeclaredMethod("dispatchCurrent");
      method.setAccessible(true);
      return (boolean) method.invoke(host);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private void assertNoRequestBytes(ExternalDeviceHost host) throws Exception {
    var sink = new ByteArrayOutputStream();
    assertFalse(RootShell.writeExternalRequest(sink, new byte[] {1}, () -> current(host)));
    assertEquals(0, sink.size());
    IOException refusal =
        assertThrows(IOException.class, () -> host.operate("reboot", "", "system", true));
    assertEquals("EXTERNAL_AUTHORITY_CHANGED_BEFORE_DISPATCH", refusal.getMessage());
  }

  private void assertTrialRefusal(ExternalDeviceHost host) throws Exception {
    assertNull(host.confirmationTarget());
    assertNull(host.binding());
    assertFalse(current(host));
    assertNoRequestBytes(host);
    assertEquals(0, requestChecks.get());
  }

  @Test
  public void unchangedTargetCanHandOverOnlyTheCapturedCommand() throws Exception {
    var host = capture();
    if (BuildConfig.REBIRTH_CANDIDATE) {
      assertTrialRefusal(host);
      return;
    }
    ExternalTargetBinding target = host.confirmationTarget();
    assertEquals("first", target.serial);
    var sink = new ByteArrayOutputStream();
    byte[] payload = target.serial.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(RootShell.writeExternalRequest(sink, payload, () -> current(host)));
    assertArrayEquals(payload, sink.toByteArray());
  }

  @Test
  public void confirmedFirstNeverDispatchesToReplacementOrChangedIdentity() throws Exception {
    var host = capture();
    bindFixture("second", "different-fingerprint", 8);
    if (BuildConfig.REBIRTH_CANDIDATE) {
      assertTrialRefusal(host);
      bindFixture("first", "different-fingerprint", 7);
      assertTrialRefusal(host);
      bindFixture("first", "first-fingerprint", 8);
      assertTrialRefusal(host);
      return;
    }
    assertEquals("first", host.confirmationTarget().serial);
    assertNoRequestBytes(host);
    bindFixture("first", "different-fingerprint", 7);
    assertNoRequestBytes(host);
    bindFixture("first", "first-fingerprint", 8);
    assertNoRequestBytes(host);
  }

  @Test
  public void forgetAndOffOnInvalidateEvenIfIdenticalBindingReturns() throws Exception {
    var forgotten = capture();
    assertTrue(settings.forgetTarget());
    bindFixture("first", "first-fingerprint", 7);
    assertNoRequestBytes(forgotten);
    var toggled = capture();
    assertTrue(settings.setEnabled(false));
    if (BuildConfig.REBIRTH_CANDIDATE) {
      assertFalse(settings.setEnabled(true));
      assertFalse(prefs.getBoolean("external_device_enabled_v1", true));
      assertTrialRefusal(toggled);
    } else {
      assertTrue(settings.setEnabled(true));
      assertNoRequestBytes(toggled);
    }
  }

  @Test
  public void requestRevokedDuringPreparationCannotHandOffPayload() throws Exception {
    var host = capture();
    if (BuildConfig.REBIRTH_CANDIDATE) {
      assertTrialRefusal(host);
      requestLive.set(false);
      assertTrialRefusal(host);
      return;
    }
    assertTrue(current(host));
    requestLive.set(false);
    assertNoRequestBytes(host);
  }

  @Test
  public void anUnboundRequestCannotSurviveAChangedPersistedEpoch() throws Exception {
    assertTrue(settings.forgetTarget());
    var host = capture();
    assertNull(host.confirmationTarget());
    if (BuildConfig.REBIRTH_CANDIDATE) {
      assertTrialRefusal(host);
      assertTrue(prefs.edit().putLong("target_epoch_v1", 9).commit());
      assertTrialRefusal(host);
      return;
    }
    assertTrue(current(host));
    assertTrue(prefs.edit().putLong("target_epoch_v1", 9).commit());
    assertNoRequestBytes(host);
  }

  @Test
  public void twoInspectedCandidatesCannotPublishTheSameNextEpoch() throws Exception {
    var first = capture();
    var second = capture();
    var commit =
        ExternalDeviceHost.class.getDeclaredMethod(
            "commitInspectedBinding", ExternalTargetBinding.class);
    commit.setAccessible(true);
    if (BuildConfig.REBIRTH_CANDIDATE) {
      var firstRefusal =
          assertThrows(
              java.lang.reflect.InvocationTargetException.class,
              () ->
                  commit.invoke(
                      first, new ExternalTargetBinding("second", "product", "fingerprint-b", 8)));
      var secondRefusal =
          assertThrows(
              java.lang.reflect.InvocationTargetException.class,
              () ->
                  commit.invoke(
                      second, new ExternalTargetBinding("third", "product", "fingerprint-c", 8)));
      for (var refusal : java.util.List.of(firstRefusal, secondRefusal)) {
        assertTrue(refusal.getCause() instanceof IOException);
        assertEquals("EXTERNAL_AUTHORITY_CHANGED_BEFORE_DISPATCH", refusal.getCause().getMessage());
      }
      assertTrialRefusal(first);
      assertTrialRefusal(second);
      assertEquals("first", prefs.getString("target_serial_v1", ""));
      assertEquals(7L, prefs.getLong("target_epoch_v1", 0));
      return;
    }
    assertTrue(
        (boolean)
            commit.invoke(
                first, new ExternalTargetBinding("second", "product", "fingerprint-b", 8)));
    var denied =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () ->
                commit.invoke(
                    second, new ExternalTargetBinding("third", "product", "fingerprint-c", 8)));
    assertTrue(denied.getCause() instanceof IOException);
    assertEquals("second", settings.binding().serial);
    assertEquals(8, settings.binding().epoch);
    assertNoRequestBytes(second);
  }

  @Test
  public void failedCommitCanPublishCacheButCannotAuthorizeAnyNewDispatch() throws Exception {
    ExternalDeviceHost original = capture();
    var edits = new AtomicInteger();
    Context failedWrite =
        new ContextWrapper(app) {
          @Override
          public Context getApplicationContext() {
            return this;
          }

          @Override
          public SharedPreferences getSharedPreferences(String name, int mode) {
            return (SharedPreferences)
                Proxy.newProxyInstance(
                    SharedPreferences.class.getClassLoader(),
                    new Class<?>[] {SharedPreferences.class},
                    (self, method, args) -> {
                      if (!method.getName().equals("edit")) return method.invoke(prefs, args);
                      edits.incrementAndGet();
                      SharedPreferences.Editor editor = prefs.edit();
                      return Proxy.newProxyInstance(
                          SharedPreferences.Editor.class.getClassLoader(),
                          new Class<?>[] {SharedPreferences.Editor.class},
                          (editing, operation, values) -> {
                            if (operation.getName().equals("commit")) {
                              editor.apply();
                              return false;
                            }
                            Object result = operation.invoke(editor, values);
                            return result == editor ? editing : result;
                          });
                    });
          }
        };
    assertFalse(new ExternalDeviceHost(failedWrite).setEnabled(true));
    if (BuildConfig.REBIRTH_CANDIDATE) {
      assertEquals(0, edits.get());
      assertFalse(settings.enabled());
      assertTrialRefusal(original);
      assertTrialRefusal(capture());
      return;
    }
    assertTrue(prefs.getBoolean("external_device_enabled_v1", false));
    assertFalse(settings.enabled());
    assertNoRequestBytes(original);
    assertNoRequestBytes(capture());
    assertTrue(settings.setEnabled(true));
    assertNull(settings.binding());
    assertNoRequestBytes(original);
  }
}
