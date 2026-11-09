package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ContainerDshEngineTest {
  @Test
  public void launchForwardsTheExactCommandAndEnvironment() throws IOException {
    String[] seenCommand = new String[1];
    Map<String, String> seenEnvironment = new HashMap<>();
    ContainerDshEngine engine =
        new ContainerDshEngine(
            (command, environment) -> {
              seenCommand[0] = command;
              seenEnvironment.putAll(environment);
              return null;
            },
            () -> "proot");
    Map<String, String> environment = Map.of("DSHA_WEB_GENERATION", "7", "A", "b");

    assertNull(engine.launch("exec node x", environment));
    assertEquals("exec node x", seenCommand[0]);
    assertEquals(environment, seenEnvironment);
  }

  @Test
  public void launchFailureIsNotSwallowedOrRetried() {
    AtomicInteger calls = new AtomicInteger();
    ContainerDshEngine engine =
        new ContainerDshEngine(
            (command, environment) -> {
              calls.incrementAndGet();
              throw new IOException("boom");
            },
            () -> "proroot");
    try {
      engine.launch("cmd", Map.of());
      fail("expected IOException");
    } catch (IOException expected) {
      assertEquals("boom", expected.getMessage());
    }
    assertEquals(1, calls.get());
  }

  @Test
  public void runtimeNameIsReadWhenAskedSoItReflectsTheActualBootstrap() {
    String[] name = {"proot"};
    ContainerDshEngine engine = new ContainerDshEngine((c, e) -> null, () -> name[0]);
    assertEquals("proot", engine.runtimeName());
    name[0] = "proroot";
    assertEquals("proroot", engine.runtimeName());
  }

  @Test
  public void idIsTheStoredContainerChoice() {
    assertEquals("ubuntu", new ContainerDshEngine((c, e) -> null, () -> "proot").id());
  }

  @Test
  public void stateDirectoryIsGuestRootUnderTheBoundRootfs() {
    java.io.File[] rootfs = {new java.io.File("/data/x/linux/ubuntu")};
    ContainerDshEngine engine =
        new ContainerDshEngine((c, e) -> null, () -> "proot", () -> rootfs[0]);
    assertEquals(new java.io.File("/data/x/linux/ubuntu/root"), engine.stateDirectory());
    // 与停止链此前的写法逐字节相同。
    assertEquals(
        new java.io.File(
            rootfs[0],
            com.deepseekharness.app.util.WebProcSel.pidFileRel(
                com.deepseekharness.app.util.WebProcSel.STOP_SENTINEL)),
        com.deepseekharness.app.util.EngineStateFiles.stopSentinel(engine.stateDirectory()));
    rootfs[0] = new java.io.File("/data/y/linux/ubuntu");
    assertEquals(new java.io.File("/data/y/linux/ubuntu/root"), engine.stateDirectory());
  }

  @Test
  public void stateDirectoryWithoutABoundRootfsFailsLoudly() {
    ContainerDshEngine engine = new ContainerDshEngine((c, e) -> null, () -> "proot");
    try {
      engine.stateDirectory();
      fail("expected IllegalStateException");
    } catch (IllegalStateException expected) {
      assertEquals("ROOTFS_NOT_BOUND", expected.getMessage());
    }
  }

  @Test
  public void containerDeclaresEveryCapabilityAsSupported() {
    var capabilities = new ContainerDshEngine((c, e) -> null, () -> "proot").capabilities();
    for (var capability : com.deepseekharness.app.util.EngineCapabilities.Capability.values())
      assertEquals(
          capability.id,
          com.deepseekharness.app.util.EngineCapabilities.Support.SUPPORTED,
          capabilities.support(capability));
  }
}
