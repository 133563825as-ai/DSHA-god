package com.deepseekharness.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class WebProcSelNativeTest {
  private static final String NODE = "/data/app/x/lib/arm64/libnode.so";
  private static final String ENTRY =
      "/data/data/p/files/native-runtime/a/node_modules/@deepseek-ai/dsh/lib/bin.js";
  // 用 String.join 拼 NUL 分隔：字面量 "\0127…" 会被 Java 当成八进制转义 \012。
  private static final String NATIVE =
      String.join(
              "\u0000",
              NODE,
              "--expose-internals",
              ENTRY,
              "web",
              "--no-open",
              "--host",
              "127.0.0.1",
              "--port",
              "0")
          + "\u0000";

  @After
  public void clear() {
    WebProcSel.clearNativeForTest();
  }

  @Test
  public void nativeWebIsUnknownUntilRegistered() {
    assertFalse(WebProcSel.looksLikeWeb(NATIVE));
    assertFalse(WebProcSel.maySignalWeb(NATIVE));
    WebProcSel.registerNative(NODE, ENTRY);
    assertTrue(WebProcSel.looksLikeWeb(NATIVE));
    assertTrue(WebProcSel.maySignalWeb(NATIVE));
  }

  @Test
  public void registrationDoesNotWidenOtherRules() {
    WebProcSel.registerNative(NODE, ENTRY);
    assertFalse(WebProcSel.maySignalWeb(NATIVE.replace(NODE, "/tmp/libnode.so")));
    assertFalse(
        WebProcSel.looksLikeWeb("/system/bin/sh\u0000-c\u0000" + NATIVE.replace('\0', ' ')));
    // proot / proroot 启动器仍然绝不算 Web。
    assertFalse(WebProcSel.looksLikeWeb("/data/app/x/lib/arm64/libproot.so\u0000-r\u0000/x\u0000"));
    assertFalse(WebProcSel.maySignalWeb("/data/app/x/lib/arm64/libproroot.so\u0000--\u0000"));
    // 容器的既有规则不受影响。
    assertTrue(
        WebProcSel.maySignalWeb(
            "/usr/local/bin/node\u0000/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js\u0000web\u0000"));
  }
}
