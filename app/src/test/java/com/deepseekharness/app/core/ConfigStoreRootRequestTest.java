package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.DeviceShellPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 使用真实 ConfigStore 与模拟 Android 偏好；不连接 Root，不执行 shell。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public final class ConfigStoreRootRequestTest {
  private Context context;
  private ConfigStore config;

  @Before
  public void prepare() {
    context = RuntimeEnvironment.getApplication();
    config = new ConfigStore(context);
    config.setRootShellAllowed(true);
    config.setRootPolicyMode(DeviceShellPolicy.Mode.STRICT);
  }

  @Test
  public void anotherSettingsInstanceCannotReviveOldRequestByTurningRootBackOn() throws Exception {
    var permit = config.captureRootRequest();
    var other = new ConfigStore(context);
    other.setRootShellAllowed(false);
    other.setRootShellAllowed(true);
    var output = new ByteArrayOutputStream();
    assertFalse(config.writeRootRequest(output, new byte[] {1}, permit));
    assertEquals(0, output.size());
    assertTrue(other.writeRootRequest(output, new byte[] {2}, other.captureRootRequest()));
    assertArrayEquals(
        new byte[] {2},
        com.deepseekharness.app.util.RootRequestWire.readCommitted(
            new java.io.ByteArrayInputStream(output.toByteArray())));
  }

  @Test
  public void fullToStrictRejectsTheAlreadyCheckedFullRequest() throws Exception {
    config.setRootPolicyMode(DeviceShellPolicy.Mode.FULL);
    var permit = config.captureRootRequest();
    new ConfigStore(context).setRootPolicyMode(DeviceShellPolicy.Mode.STRICT);
    var output = new ByteArrayOutputStream();
    assertFalse(config.writeRootRequest(output, new byte[] {1}, permit));
    assertEquals(0, output.size());
    assertEquals(DeviceShellPolicy.Mode.STRICT, config.rootPolicyMode());
    assertNotNull(config.captureRootRequest());
  }

  @Test
  public void malformedRootFlagCannotAuthorizeOrDispatch() throws Exception {
    var permit = config.captureRootRequest();
    context
        .getSharedPreferences(Constants.PREFS, 0)
        .edit()
        .putString(Constants.KEY_ALLOW_ROOT_SHELL, "synthetic-invalid-value")
        .apply();
    assertNull(config.captureRootRequest());
    var output = new ByteArrayOutputStream();
    assertFalse(config.writeRootRequest(output, new byte[] {1}, permit));
    assertEquals(0, output.size());
  }

  private void rejectWrongType(Object value) throws Exception {
    var permit = config.captureRootRequest();
    var edit = context.getSharedPreferences(Constants.PREFS, 0).edit();
    if (value instanceof String) edit.putString(Constants.KEY_ALLOW_ROOT_SHELL, (String) value);
    else edit.putInt(Constants.KEY_ALLOW_ROOT_SHELL, (Integer) value);
    edit.apply();
    assertFalse("Root 授权不能兼容转换错误类型", config.isRootShellAllowed());
    assertNull(config.captureRootRequest());
    var output = new ByteArrayOutputStream();
    assertFalse(config.writeRootRequest(output, new byte[] {1}, permit));
    assertEquals(0, output.size());
    assertFalse(com.deepseekharness.app.RootShell.enabled(context));
  }

  @Test
  public void stringTrueCannotAuthorizeRoot() throws Exception {
    rejectWrongType("true");
  }

  @Test
  public void stringOneCannotAuthorizeRoot() throws Exception {
    rejectWrongType("1");
  }

  @Test
  public void integerOneCannotAuthorizeRoot() throws Exception {
    rejectWrongType(1);
  }

  @Test
  public void stalePersistedTrueCannotBypassExplicitSessionDisable() {
    config.setRootShellAllowed(false);
    context
        .getSharedPreferences(Constants.PREFS, 0)
        .edit()
        .putBoolean(Constants.KEY_ALLOW_ROOT_SHELL, true)
        .commit();
    assertFalse("旧磁盘 true 不能重新授予本进程 Root", config.isRootShellAllowed());
    assertNull(config.captureRootRequest());
  }

  @Test
  public void stalePersistedFullCannotOverrideExplicitStrictPolicy() {
    config.setRootPolicyMode(DeviceShellPolicy.Mode.STRICT);
    context
        .getSharedPreferences(Constants.PREFS, 0)
        .edit()
        .putString(Constants.KEY_ROOT_POLICY_MODE, DeviceShellPolicy.Mode.FULL.id)
        .commit();
    assertEquals(DeviceShellPolicy.Mode.STRICT, config.rootPolicyMode());
  }

  @Test
  public void pendingRootChangeDisarmsBeforeAnyDiskCommit() throws Exception {
    var permit = config.captureRootRequest();
    assertNotNull(permit);
    config.beginRootSettingsChange();
    assertFalse(config.isRootShellAllowed());
    assertTrue(
        Boolean.TRUE.equals(
            context
                .getSharedPreferences(Constants.PREFS, 0)
                .getAll()
                .get(Constants.KEY_ALLOW_ROOT_SHELL)));
    assertFalse(config.writeRootRequest(new ByteArrayOutputStream(), new byte[] {1}, permit));
  }

  @Test
  public void revocationCannotCompleteBetweenFinalCheckAndCommit() throws Exception {
    var permit = config.captureRootRequest();
    var enteredCommit = new CountDownLatch(1);
    var releaseCommit = new CountDownLatch(1);
    var revoking = new CountDownLatch(1);
    var output = new ByteArrayOutputStream();
    var workers = Executors.newFixedThreadPool(2);
    try {
      var sent =
          workers.submit(
              () ->
                  config.writeRootRequest(
                      new OutputStream() {
                        @Override
                        public void write(int value) throws IOException {
                          if (value == 0x43) {
                            enteredCommit.countDown();
                            try {
                              if (!releaseCommit.await(5, TimeUnit.SECONDS))
                                throw new IOException("commit barrier timeout");
                            } catch (InterruptedException interrupted) {
                              Thread.currentThread().interrupt();
                              throw new IOException(interrupted);
                            }
                          }
                          output.write(value);
                        }

                        @Override
                        public void write(byte[] bytes, int offset, int length) {
                          output.write(bytes, offset, length);
                        }
                      },
                      new byte[] {1},
                      permit,
                      () -> {}));
      assertTrue(enteredCommit.await(5, TimeUnit.SECONDS));
      var revoked =
          workers.submit(
              () -> {
                revoking.countDown();
                config.beginRootSettingsChange();
                return true;
              });
      assertTrue(revoking.await(5, TimeUnit.SECONDS));
      try {
        revoked.get(200, TimeUnit.MILLISECONDS);
        fail("撤销不能在最终确认仍被阻塞时宣称已生效");
      } catch (TimeoutException expected) {
        // 最终确认持有与撤销相同的门锁。
      }
      releaseCommit.countDown();
      assertTrue(sent.get(5, TimeUnit.SECONDS));
      assertTrue(revoked.get(5, TimeUnit.SECONDS));
      assertFalse(config.isRootShellAllowed());
      assertArrayEquals(
          new byte[] {1},
          com.deepseekharness.app.util.RootRequestWire.readCommitted(
              new java.io.ByteArrayInputStream(output.toByteArray())));
    } finally {
      releaseCommit.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  public void externalAuthorityRevokedDuringHelperStagingCannotCommit() throws Exception {
    var permit = config.captureRootRequest();
    var dispatchCurrent = new AtomicBoolean(true);
    var output = new ByteArrayOutputStream();
    assertFalse(
        config.writeRootRequest(
            output,
            new byte[] {1},
            permit,
            () -> dispatchCurrent.set(false),
            dispatchCurrent::get));
    assertTrue("请求预写已发生", output.size() > 0);
    try {
      com.deepseekharness.app.util.RootRequestWire.readCommitted(
          new java.io.ByteArrayInputStream(output.toByteArray()));
      fail("外接业务授权撤销后 helper 不能收到最终确认");
    } catch (java.io.EOFException expected) {
      // 预写请求无确认，不执行。
    }
  }

  @Test
  public void slowDiskSaveDoesNotBlockNextRevocation() throws Exception {
    var enteredSave = new CountDownLatch(1);
    var releaseSave = new CountDownLatch(1);
    var blockOnce = new AtomicBoolean(true);
    var persisted = context.getSharedPreferences(Constants.PREFS, 0);
    var proxy =
        (android.content.SharedPreferences)
            java.lang.reflect.Proxy.newProxyInstance(
                android.content.SharedPreferences.class.getClassLoader(),
                new Class<?>[] {android.content.SharedPreferences.class},
                (instance, method, args) -> {
                  if (!method.getName().equals("edit")) return method.invoke(persisted, args);
                  var editor = persisted.edit();
                  return java.lang.reflect.Proxy.newProxyInstance(
                      android.content.SharedPreferences.Editor.class.getClassLoader(),
                      new Class<?>[] {android.content.SharedPreferences.Editor.class},
                      (editorProxy, editMethod, editArgs) -> {
                        if (editMethod.getName().equals("commit") && blockOnce.getAndSet(false)) {
                          enteredSave.countDown();
                          if (!releaseSave.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("save barrier timeout");
                        }
                        Object result = editMethod.invoke(editor, editArgs);
                        return result == editor ? editorProxy : result;
                      });
                });
    var wrapped =
        new android.content.ContextWrapper(context) {
          @Override
          public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
            return proxy;
          }
        };
    var isolated = new ConfigStore(wrapped);
    var workers = Executors.newSingleThreadExecutor();
    var finished = new CountDownLatch(1);
    try {
      isolated.setRootShellAllowedAsync(true, saved -> {});
      assertTrue(enteredSave.await(5, TimeUnit.SECONDS));
      var next =
          workers.submit(
              () -> {
                isolated.setRootShellAllowedAsync(false, saved -> finished.countDown());
                return true;
              });
      assertTrue("新的撤销不能等待旧磁盘 commit", next.get(1, TimeUnit.SECONDS));
      assertFalse(isolated.isRootShellAllowed());
      releaseSave.countDown();
      // 后台队列完成后再交还，避免本测试的静态队列污染后续测试。
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (finished.getCount() != 0 && System.nanoTime() < deadline) {
        org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
        Thread.sleep(10);
      }
      assertEquals(0, finished.getCount());
      assertFalse(isolated.isRootShellAllowed());
    } finally {
      releaseSave.countDown();
      workers.shutdownNow();
    }
  }
}
