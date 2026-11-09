package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class NativeWebLaunchTest {
  private static final String NODE =
      "/data/app/~~x/io.github.sunsetrne.dsha.test-y/lib/arm64/libnode.so";
  private static final String ENTRY =
      "/data/data/io.github.sunsetrne.dsha.test/files/native-runtime/abc/node_modules/@deepseek-ai/dsh/lib/bin.js";

  private static NativeWebLaunch.Paths paths() {
    String files = "/data/data/io.github.sunsetrne.dsha.test/files";
    return new NativeWebLaunch.Paths(
        NODE,
        ENTRY,
        files + "/native-home",
        files + "/native-home/.dsh",
        files + "/user-data-v5/dsh",
        files + "/native-home/tmp",
        files + "/native-runtime/abc/lib",
        files + "/native-runtime/abc/etc/tls/cert.pem",
        "/data/app/~~x/io.github.sunsetrne.dsha.test-y/lib/arm64/librg.so",
        files + "/native-home/Documents",
        files + "/native-runtime/abc/dsha-tools/dsha-app.cjs");
  }

  private static String cmdline(List<String> argv) {
    return String.join("\0", argv) + "\0";
  }

  @Test
  public void argvIsTheFixedDirectWebCommand() {
    assertEquals(
        List.of(
            NODE,
            "--expose-internals",
            ENTRY,
            "web",
            "--no-open",
            "--host",
            "127.0.0.1",
            "--port",
            "0"),
        NativeWebLaunch.argv(paths(), 0));
    assertThrows(IllegalArgumentException.class, () -> NativeWebLaunch.argv(paths(), -1));
    assertThrows(IllegalArgumentException.class, () -> NativeWebLaunch.argv(paths(), 65536));
  }

  @Test
  public void exactLaunchedCommandIsRecognized() {
    assertTrue(NativeWebLaunch.matches(cmdline(NativeWebLaunch.argv(paths(), 0)), NODE, ENTRY));
    assertTrue(NativeWebLaunch.matches(cmdline(NativeWebLaunch.argv(paths(), 43819)), NODE, ENTRY));
  }

  @Test
  public void lookalikesAreNotRecognized() {
    List<String> real = NativeWebLaunch.argv(paths(), 0);
    // 只有文件名相同、或别的目录里的 node。
    assertFalse(NativeWebLaunch.matches(cmdline(replace(real, 0, "/tmp/libnode.so")), NODE, ENTRY));
    assertFalse(NativeWebLaunch.matches(cmdline(replace(real, 0, "libnode.so")), NODE, ENTRY));
    // 别的 bin.js。
    assertFalse(
        NativeWebLaunch.matches(
            cmdline(replace(real, 2, "/sdcard/x/node_modules/@deepseek-ai/dsh/lib/bin.js")),
            NODE,
            ENTRY));
    // 缺 --expose-internals、换 profile、追加参数、端口越界或非十进制。
    assertFalse(NativeWebLaunch.matches(cmdline(replace(real, 1, "--inspect")), NODE, ENTRY));
    assertFalse(NativeWebLaunch.matches(cmdline(replace(real, 3, "--profile")), NODE, ENTRY));
    assertFalse(NativeWebLaunch.matches(cmdline(append(real, "--evil")), NODE, ENTRY));
    assertFalse(NativeWebLaunch.matches(cmdline(replace(real, 8, "65536")), NODE, ENTRY));
    assertFalse(NativeWebLaunch.matches(cmdline(replace(real, 8, "0x10")), NODE, ENTRY));
    assertFalse(NativeWebLaunch.matches(cmdline(replace(real, 8, "007")), NODE, ENTRY));
    // shell 文本里出现同样的字符串不算（必须是 NUL 分隔的 argv）。
    assertFalse(NativeWebLaunch.matches(String.join(" ", real), NODE, ENTRY));
    assertFalse(
        NativeWebLaunch.matches(
            cmdline(List.of("/system/bin/sh", "-c", String.join(" ", real))), NODE, ENTRY));
    assertFalse(NativeWebLaunch.matches(null, NODE, ENTRY));
    assertFalse(NativeWebLaunch.matches(cmdline(real), null, ENTRY));
  }

  @Test
  public void environmentUsesHostPathsAndDropsInjectedPreloads() {
    Map<String, String> launch = new HashMap<>();
    launch.put("DSH_TASK_GENERATION", "7");
    launch.put("NODE_OPTIONS", "--require=/tmp/evil.cjs");
    launch.put("LD_PRELOAD", "/tmp/evil.so");
    Map<String, String> env = NativeWebLaunch.environment(paths(), launch, true, "zh", "auto", 7);
    assertEquals("7", env.get("DSH_TASK_GENERATION"));
    assertNull(env.get("NODE_OPTIONS"));
    assertNull(env.get("LD_PRELOAD"));
    assertEquals(paths().libraries, env.get("LD_LIBRARY_PATH"));
    assertEquals(paths().dshHome, env.get("DSH_HOME"));
    assertEquals(paths().bridgeState, env.get("DSHA_BRIDGE_STATE_DIR"));
    assertEquals(paths().ripgrep, env.get("DSHA_RIPGREP_PATH"));
    assertEquals(PermissionMode.DEFAULT, env.get("DSH_PERMISSION_MODE"));
    assertEquals("1", env.get("DSH_CONFIRM"));
    assertEquals("7", env.get("DSHA_WEB_GENERATION"));
    assertEquals("native", env.get("DSHA_DSH_ENGINE"));
    assertEquals(paths().certificate, env.get("SSL_CERT_FILE"));
    assertEquals(NODE, env.get("DSHA_NODE"));
    assertEquals(paths().appTool, env.get("DSHA_APP"));
    assertEquals(paths().home + "/.dsha-web-activity.json", env.get("DSHA_ACTIVITY_FILE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> NativeWebLaunch.environment(paths(), launch, true, "zh", "auto", 0));
  }

  @Test
  public void pathsMustBeCanonicalAbsolute() {
    for (String bad :
        new String[] {"relative/x", "/a//b", "/a/./b", "/a/../b", "/a/b/", "/a/..", null})
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new NativeWebLaunch.Paths(
                  bad, ENTRY, "/h", "/h/.dsh", "/s", "/t", "/l", "/c", "/r", "/w"));
  }

  private static List<String> replace(List<String> argv, int index, String value) {
    String[] copy = argv.toArray(new String[0]);
    copy[index] = value;
    return List.of(copy);
  }

  private static List<String> append(List<String> argv, String value) {
    String[] copy = argv.toArray(new String[argv.size() + 1]);
    copy[argv.size()] = value;
    return List.of(copy);
  }
}
