package com.deepseekharness.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Base64;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.DeviceGrants;
import com.deepseekharness.app.util.DeviceShellPolicy;
import com.deepseekharness.app.util.RootRequestWire;
import com.deepseekharness.app.util.ShellQuote;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real su/helper integration, id only. Never packaged in the production application. */
public final class RootHandoverInstrumentation extends Instrumentation {
  private final JSONArray results = new JSONArray();
  private ConfigStore config;
  private DeviceGrants grants;

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    Bundle receipt = new Bundle();
    boolean passed = false;
    try {
      require(
          getTargetContext().getPackageName().equals("dsh.android.client.SunsetDev"),
          "ISOLATED_DEV_PACKAGE_REQUIRED");
      require(BuildConfig.TEST_PACKAGE, "TEST_PACKAGE_REQUIRED");
      require(
          RootShell.class.getClassLoader() == ConfigStore.class.getClassLoader(),
          "TARGET_CLASS_LOADER_REQUIRED");
      config = new ConfigStore(getTargetContext());
      grants = new DeviceGrants(getTargetContext());
      runCase("control-before", "none", false);
      runCase("root-revoke", "root-off", false);
      runCase("root-aba", "root-aba", false);
      runCase("sms-revoke", "sms-off", false);
      runCase("ordinary-after-sms-revoke", "none", false);
      runCase("sms-aba", "sms-aba", false);
      runCase("missing-commit", "none", true);
      // An explicit bypass control, not a claim of mutation-testing production code.
      runBypassControl();
      runCase("control-after", "none", false);
      passed = true;
    } catch (Throwable error) {
      receipt.putString("failure", error.getClass().getSimpleName() + ": " + error.getMessage());
    } finally {
      try {
        if (grants != null) require(grants.setSmsReadAllowed(false), "SMS_CLEANUP_FAILED");
        if (config != null) {
          config.setRootShellAllowed(false);
          config.setRootPolicyMode(DeviceShellPolicy.Mode.STRICT);
          require(!config.isRootShellAllowed(), "ROOT_CLEANUP_FAILED");
        }
      } catch (Throwable cleanup) {
        passed = false;
        receipt.putString("cleanupFailure", cleanup.toString());
      }
    }
    receipt.putString("result", passed ? "PASS" : "FAIL");
    receipt.putString("cases", results.toString());
    receipt.putString("scope", "real-helper/config-gate/id-only; not RootShell.exec SMS E2E");
    receipt.putString("processExitScope", "su launcher exited and output EOF; descendants not independently identified");
    finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, receipt);
  }

  private void resetRoot() {
    config.setRootPolicyMode(DeviceShellPolicy.Mode.STRICT);
    config.setRootShellAllowed(true);
    require(config.isRootShellAllowed(), "ROOT_ENABLE_FAILED");
  }

  private void runCase(String name, String mutation, boolean missingCommit) throws Exception {
    resetRoot();
    boolean smsCase = mutation.startsWith("sms-");
    if (smsCase) require(grants.setSmsReadAllowed(true), "SMS_ENABLE_FAILED");
    var permit = config.captureRootRequest();
    require(permit != null, "PERMIT_REQUIRED");
    String nonce = nonce();
    Process helper = startHelper();
    boolean[] staged = {false};
    boolean accepted;
    try {
      if (missingCommit) {
        RootRequestWire.stage(helper.getOutputStream(), payload(nonce));
        awaitStaged(helper, nonce);
        staged[0] = true;
        helper.getOutputStream().close();
        accepted = false;
      } else {
        accepted =
            config.writeRootRequest(
                helper.getOutputStream(),
                payload(nonce),
                permit,
                () -> {
                  awaitStaged(helper, nonce);
                  staged[0] = true;
                  // Runs after the actual matching UID0 helper acknowledgement, outside gate lock.
                  switch (mutation) {
                    case "root-off" -> config.setRootShellAllowed(false);
                    case "root-aba" -> {
                      config.setRootShellAllowed(false);
                      config.setRootShellAllowed(true);
                    }
                    case "sms-off" -> require(grants.setSmsReadAllowed(false), "SMS_SAVE_FAILED");
                    case "sms-aba" -> {
                      require(grants.setSmsReadAllowed(false), "SMS_SAVE_FAILED");
                      require(grants.setSmsReadAllowed(true), "SMS_SAVE_FAILED");
                    }
                    case "none" -> {}
                    default -> throw new AssertionError("UNKNOWN_MUTATION");
                  }
                },
                () -> !smsCase || grants.smsReadAllowed());
      }
      require(staged[0], "MATCHED_STAGED_REQUIRED");
      String output = result(helper);
      boolean expectExecution = mutation.equals("none") && !missingCommit;
      require(accepted == expectExecution, "GATE_VERDICT_MISMATCH " + name);
      if (expectExecution) requireId(output);
      else {
        require(output.contains("ROOT_REQUEST_NOT_COMMITTED"), "EXPLICIT_EOF_REJECTION_REQUIRED " + output);
        require(!output.contains("uid=0"), "REVOKED_REQUEST_EXECUTED");
      }
      if (smsCase) require(config.isRootShellAllowed(), "SMS_MUST_NOT_DISABLE_ROOT");
      record(name, nonce, accepted, output);
    } finally {
      closeHelper(helper);
    }
  }

  private void runBypassControl() throws Exception {
    resetRoot();
    String nonce = nonce();
    Process helper = startHelper();
    try {
      RootRequestWire.stage(helper.getOutputStream(), payload(nonce));
      awaitStaged(helper, nonce);
      config.setRootShellAllowed(false);
      // Deliberately bypasses ConfigStore gate in this test APK, sending only harmless id.
      RootRequestWire.commit(helper.getOutputStream());
      helper.getOutputStream().close();
      String output = result(helper);
      requireId(output);
      record("explicit-gate-bypass-control", nonce, true, output);
    } finally {
      closeHelper(helper);
    }
  }

  private byte[] payload(String nonce) throws Exception {
    return new JSONObject()
        .put("command", "id")
        .put("smsUser", -1)
        .put("selfPackage", getTargetContext().getPackageName())
        .put("selfUid", getTargetContext().getApplicationInfo().uid)
        .put("handoverNonce", nonce)
        .put("helperDeadlineMs", 15000)
        .toString()
        .getBytes(StandardCharsets.UTF_8);
  }

  private Process startHelper() throws IOException {
    String entry =
        "CLASSPATH="
            + ShellQuote.arg(getTargetContext().getApplicationInfo().sourceDir)
            + " /system/bin/app_process /system/bin "
            + RootShellMain.class.getName();
    return new ProcessBuilder("/system/bin/su", "-c", entry).redirectErrorStream(true).start();
  }

  private static void awaitStaged(Process helper, String nonce) throws IOException, InterruptedException {
    try {
      var method = RootShell.class.getDeclaredMethod("awaitStaged", Process.class, String.class, long.class);
      method.setAccessible(true);
      method.invoke(null, helper, nonce, 15000L);
    } catch (InvocationTargetException wrapper) {
      if (wrapper.getCause() instanceof IOException error) throw error;
      if (wrapper.getCause() instanceof InterruptedException error) throw error;
      throw new IOException("STAGED_CALLBACK_FAILED", wrapper.getCause());
    } catch (ReflectiveOperationException error) {
      throw new IOException("PRODUCTION_STAGED_METHOD_REQUIRED", error);
    }
  }

  private static String result(Process helper) throws Exception {
    var worker = Executors.newSingleThreadExecutor();
    try {
      var remaining = worker.submit(() -> {
        var bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int count;
        while ((count = helper.getInputStream().read(buffer)) != -1) {
          if (bytes.size() + count > 16384) throw new IOException("HELPER_OUTPUT_LIMIT");
          bytes.write(buffer, 0, count);
        }
        return bytes.toString(StandardCharsets.UTF_8);
      });
      require(helper.waitFor(20, TimeUnit.SECONDS), "SU_LAUNCHER_EXIT_REQUIRED");
      require(helper.exitValue() == 0, "HELPER_LAUNCHER_NONZERO_EXIT");
      String raw = remaining.get(5, TimeUnit.SECONDS);
      int marker = raw.indexOf(RootShellMain.RESULT);
      require(marker >= 0 && marker == raw.lastIndexOf(RootShellMain.RESULT), "ONE_COMPLETE_RESULT_REQUIRED " + raw);
      String encoded = raw.substring(marker + RootShellMain.RESULT.length()).trim();
      require(!encoded.contains("\n"), "TRAILING_OUTPUT_REJECTED");
      return new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
    } finally {
      try { helper.getInputStream().close(); } catch (IOException ignored) {}
      worker.shutdownNow();
      require(worker.awaitTermination(5, TimeUnit.SECONDS), "READER_TERMINATION_REQUIRED");
    }
  }

  private static void closeHelper(Process helper) {
    try { helper.getOutputStream().close(); } catch (IOException ignored) {}
    try { helper.getInputStream().close(); } catch (IOException ignored) {}
    try { helper.getErrorStream().close(); } catch (IOException ignored) {}
    try { helper.destroy(); } catch (RuntimeException ignored) {}
  }

  private void record(String name, String nonce, boolean accepted, String output) throws Exception {
    results.put(new JSONObject().put("case", name).put("nonce", nonce)
        .put("matchedStaged", true).put("gateAccepted", accepted)
        .put("launcherExited", true).put("output", output));
  }

  private static String nonce() { return UUID.randomUUID().toString().replace("-", ""); }

  private static void requireId(String output) {
    require(output.contains("uid=0(root)") && output.endsWith("[EXIT=0]"), "REAL_ID_CONTROL_REQUIRED " + output);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
