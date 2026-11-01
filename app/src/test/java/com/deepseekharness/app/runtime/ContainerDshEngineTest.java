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
  public void containerDeclaresEveryCapabilityAsSupported() {
    var capabilities = new ContainerDshEngine((c, e) -> null, () -> "proot").capabilities();
    for (var capability : com.deepseekharness.app.util.EngineCapabilities.Capability.values())
      assertEquals(
          capability.id,
          com.deepseekharness.app.util.EngineCapabilities.Support.SUPPORTED,
          capabilities.support(capability));
  }
}
