package com.deepseekharness.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.Constants;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Guest device plans must use the same effective Root grant as native dispatch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public final class HttpShellServiceRootPlanTest {
  private static JSONObject plan(Context context) throws Exception {
    Method method =
        HttpShellService.class.getDeclaredMethod("devicePlan", String.class, boolean.class);
    method.setAccessible(true);
    return new JSONObject((String) method.invoke(new HttpShellService(context), "id", true));
  }

  @Test
  public void stalePersistedTrueDoesNotAuthorizeGuestRootPlan() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    ConfigStore config = new ConfigStore(context);
    config.setRootShellAllowed(false);
    context
        .getSharedPreferences(Constants.PREFS, 0)
        .edit()
        .putBoolean(Constants.KEY_ALLOW_ROOT_SHELL, true)
        .commit();
    JSONObject result = plan(context);
    assertEquals("DENY", result.getString("kind"));
    assertTrue(result.getString("reason").contains("POLICY_BLOCKED"));
  }

  @Test
  public void malformedPersistedFlagDoesNotAuthorizeGuestRootPlan() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    ConfigStore config = new ConfigStore(context);
    config.setRootShellAllowed(true);
    context
        .getSharedPreferences(Constants.PREFS, 0)
        .edit()
        .putString(Constants.KEY_ALLOW_ROOT_SHELL, "true")
        .commit();
    JSONObject result = plan(context);
    assertEquals("DENY", result.getString("kind"));
    assertTrue(result.getString("reason").contains("POLICY_BLOCKED"));
  }
}
