package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.DeviceShellPolicy;
import java.io.ByteArrayOutputStream;
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
    assertArrayEquals(new byte[] {2}, output.toByteArray());
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
}
