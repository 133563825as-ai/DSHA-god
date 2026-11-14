package com.deepseekharness.app;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.DeviceShellPolicy;
import com.deepseekharness.app.util.RootHelperSlots;
import com.deepseekharness.app.util.RootShellCandidates;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * 执行真实 RootShell.exec，到 helper 已启动、stdin 尚未交付的边界。
 * su 候选替换为本机 java（收到不支持的 -c 后退出），绝不提权或执行请求中的设备命令。
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    instrumentedPackages = {"com.deepseekharness.app.util"},
    shadows = {RootShellRequestGateTest.Candidate.class, RootShellRequestGateTest.Started.class})
public final class RootShellRequestGateTest {
  private static CountDownLatch helperStarted, releaseHelper;
  private Context context;
  private ConfigStore config;

  @Implements(value = RootShellCandidates.class, isInAndroidSdk = false)
  public static final class Candidate {
    @Implementation
    protected static String firstVerified(Predicate<String> ignored) {
      String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
      return new File(System.getProperty("java.home"), "bin/" + executable).getPath();
    }
  }

  @Implements(value = RootHelperSlots.class, isInAndroidSdk = false)
  public static final class Started {
    @Implementation
    protected void attach(RootHelperSlots.Ticket<?> ticket, Object process) {
      // 仅以槽位登记边界做暂停点；真实槽位逻辑由 RootHelperSlotsTest 单独覆盖。
      helperStarted.countDown();
      try {
        if (!releaseHelper.await(5, TimeUnit.SECONDS)) throw new AssertionError("helper barrier");
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError(error);
      }
    }
  }

  @Before
  public void prepare() {
    context = RuntimeEnvironment.getApplication();
    config = new ConfigStore(context);
    config.setRootShellAllowed(true);
    config.setRootPolicyMode(DeviceShellPolicy.Mode.STRICT);
    helperStarted = new CountDownLatch(1);
    releaseHelper = new CountDownLatch(1);
  }

  private void rejectAfterHelperStarted(Runnable change) throws Exception {
    rejectAfterHelperStarted(
        () -> RootShell.exec(context, "id", -1), change, "ROOT_AUTHORITY_CHANGED_BEFORE_DISPATCH");
  }

  private void rejectAfterHelperStarted(
      java.util.concurrent.Callable<String> request, Runnable change, String code)
      throws Exception {
    var worker = Executors.newSingleThreadExecutor();
    try {
      var result = worker.submit(request);
      assertTrue("必须实际到达 helper 启动后边界", helperStarted.await(5, TimeUnit.SECONDS));
      change.run();
      releaseHelper.countDown();
      String output = result.get(5, TimeUnit.SECONDS);
      assertTrue(output, output.startsWith("[POLICY_BLOCKED] " + code));
      assertTrue(output, output.endsWith("[EXIT=126]"));
    } finally {
      releaseHelper.countDown();
      worker.shutdownNow();
      assertTrue("测试 worker 必须退出", worker.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void rootDisabledAfterHelperStartPreventsRequestHandover() throws Exception {
    rejectAfterHelperStarted(() -> config.setRootShellAllowed(false));
  }

  @Test
  public void offThenOnAfterHelperStartDoesNotReviveRequest() throws Exception {
    rejectAfterHelperStarted(
        () -> {
          config.setRootShellAllowed(false);
          config.setRootShellAllowed(true);
        });
  }

  @Test
  public void policyChangeAfterHelperStartRejectsPreviouslyCheckedRequest() throws Exception {
    config.setRootPolicyMode(DeviceShellPolicy.Mode.FULL);
    rejectAfterHelperStarted(() -> config.setRootPolicyMode(DeviceShellPolicy.Mode.STRICT));
  }

  @org.junit.Rule
  public final org.junit.rules.TemporaryFolder tools =
      new org.junit.rules.TemporaryFolder(new File(System.getProperty("user.dir")));

  private void rejectExternal(int kind, Runnable change) throws Exception {
    File root = tools.newFolder("rootfs");
    String path = root.getAbsolutePath().replace(File.separatorChar, '/');
    if (File.separatorChar == '\\') path = path.substring(2);
    java.util.List<String> prefix =
        java.util.List.of(
            path + "/lib/ld-linux-aarch64.so.1",
            "--library-path",
            path + "/lib",
            path + "/usr/bin/adb");
    var server = new java.util.ArrayList<>(prefix);
    server.addAll(java.util.List.of("-P", "5038", "nodaemon", "server"));
    var client = new java.util.ArrayList<>(prefix);
    client.addAll(java.util.List.of("-P", "5038", "devices"));
    assertNull(com.deepseekharness.app.util.ExternalToolPolicy.rejection(client, root.getPath()));
    assertNull(
        com.deepseekharness.app.util.ExternalToolPolicy.rejectionAdbServer(server, root.getPath()));
    rejectAfterHelperStarted(
        () ->
            switch (kind) {
              case 0 ->
                  RootShell.execExternal(
                      context, client, java.util.Map.of(), 1000, root.getPath(), () -> true);
              case 1 ->
                  RootShell.execExternalAdbSession(
                      context,
                      server,
                      client,
                      java.util.Map.of(),
                      1000,
                      root.getPath(),
                      () -> true);
              default ->
                  RootShell.execExternalAdbSteps(
                      context,
                      server,
                      java.util.List.of(client),
                      java.util.Map.of(),
                      1000,
                      root.getPath(),
                      null,
                      "0123456789abcdef0123456789abcdef",
                      false,
                      () -> true);
            },
        change,
        "EXTERNAL_AUTHORITY_CHANGED_BEFORE_DISPATCH");
  }

  private void toggleRoot() {
    config.setRootShellAllowed(false);
    config.setRootShellAllowed(true);
  }

  @Test
  public void externalArgvOffOnCannotReviveOldRequest() throws Exception {
    rejectExternal(0, this::toggleRoot);
  }

  @Test
  public void externalSessionOffOnCannotReviveOldRequest() throws Exception {
    rejectExternal(1, this::toggleRoot);
  }

  @Test
  public void externalStepsOffOnCannotReviveOldRequest() throws Exception {
    rejectExternal(2, this::toggleRoot);
  }
}
