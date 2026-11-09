package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;

public class EngineCapabilitiesTest {
  @Test
  public void capabilityIdsAreStableAndRoundTrip() {
    assertEquals("linux.userspace", EngineCapabilities.Capability.LINUX_USERSPACE.id);
    assertEquals("plugins.glibc-native", EngineCapabilities.Capability.PLUGINS_GLIBC_NATIVE.id);
    assertEquals("plugins.pure-js", EngineCapabilities.Capability.PLUGINS_PURE_JS.id);
    assertEquals("terminal.linux", EngineCapabilities.Capability.TERMINAL_LINUX.id);
    assertEquals("adb.pairing", EngineCapabilities.Capability.ADB_PAIRING.id);
    assertEquals("device.bridge", EngineCapabilities.Capability.DEVICE_BRIDGE.id);
    for (EngineCapabilities.Capability capability : EngineCapabilities.Capability.values())
      assertEquals(Optional.of(capability), EngineCapabilities.Capability.fromId(capability.id));
    assertEquals(Optional.empty(), EngineCapabilities.Capability.fromId("Linux.Userspace"));
    assertEquals(Optional.empty(), EngineCapabilities.Capability.fromId(null));
  }

  @Test
  public void missingEntriesAreUnknownNotUnsupported() {
    EngineCapabilities empty = EngineCapabilities.of(Map.of());
    for (EngineCapabilities.Capability capability : EngineCapabilities.Capability.values())
      assertEquals(EngineCapabilities.Support.UNKNOWN, empty.support(capability));
  }

  @Test
  public void containerSupportsEveryDeclaredCapability() {
    EngineCapabilities container = EngineCapabilities.container();
    for (EngineCapabilities.Capability capability : EngineCapabilities.Capability.values())
      assertEquals(
          capability.id, EngineCapabilities.Support.SUPPORTED, container.support(capability));
  }

  @Test
  public void provisionalNativeOnlyStatesDefinitionalFacts() {
    EngineCapabilities nativeEngine = EngineCapabilities.nativeProvisional();
    assertEquals(
        EngineCapabilities.Support.UNSUPPORTED,
        nativeEngine.support(EngineCapabilities.Capability.LINUX_USERSPACE));
    assertEquals(
        EngineCapabilities.Support.UNSUPPORTED,
        nativeEngine.support(EngineCapabilities.Capability.PLUGINS_GLIBC_NATIVE));
    // 其余未经实测，不得预填为支持。
    for (EngineCapabilities.Capability capability :
        List.of(
            EngineCapabilities.Capability.PLUGINS_PURE_JS,
            EngineCapabilities.Capability.TERMINAL_LINUX,
            EngineCapabilities.Capability.ADB_PAIRING,
            EngineCapabilities.Capability.DEVICE_BRIDGE))
      assertEquals(
          capability.id, EngineCapabilities.Support.UNKNOWN, nativeEngine.support(capability));
  }

  @Test
  public void requirementVerdictPrefersBlockedOverUndetermined() {
    Map<EngineCapabilities.Capability, EngineCapabilities.Support> values =
        new EnumMap<>(EngineCapabilities.Capability.class);
    values.put(EngineCapabilities.Capability.PLUGINS_PURE_JS, EngineCapabilities.Support.SUPPORTED);
    values.put(
        EngineCapabilities.Capability.LINUX_USERSPACE, EngineCapabilities.Support.UNSUPPORTED);
    EngineCapabilities engine = EngineCapabilities.of(values);

    assertEquals(EngineCapabilities.Verdict.ALLOWED, engine.check(List.of()).verdict);
    assertEquals(
        EngineCapabilities.Verdict.ALLOWED, engine.check(List.of("plugins.pure-js")).verdict);

    EngineCapabilities.Check unknown = engine.check(List.of("plugins.pure-js", "adb.pairing"));
    assertEquals(EngineCapabilities.Verdict.UNDETERMINED, unknown.verdict);
    assertEquals(List.of("adb.pairing"), unknown.unknown);
    assertEquals(List.of(), unknown.unsupported);

    EngineCapabilities.Check blocked =
        engine.check(List.of("adb.pairing", "linux.userspace", "plugins.pure-js"));
    assertEquals(EngineCapabilities.Verdict.BLOCKED, blocked.verdict);
    assertEquals(List.of("linux.userspace"), blocked.unsupported);
    assertEquals(List.of("adb.pairing"), blocked.unknown);
  }

  @Test
  public void unrecognizedRequirementIsUnknownAndNeverAllowed() {
    EngineCapabilities.Check check =
        EngineCapabilities.container().check(List.of("gpu.vulkan", "plugins.pure-js"));
    assertEquals(EngineCapabilities.Verdict.UNDETERMINED, check.verdict);
    assertEquals(List.of("gpu.vulkan"), check.unknown);
  }

  @Test
  public void duplicateRequirementsAreReportedOnce() {
    EngineCapabilities.Check check =
        EngineCapabilities.nativeProvisional()
            .check(List.of("linux.userspace", "linux.userspace", "x", "x"));
    assertEquals(List.of("linux.userspace"), check.unsupported);
    assertEquals(List.of("x"), check.unknown);
  }

  @Test
  public void switchingLossesListOnlyWhatWasSupportedBefore() {
    List<EngineCapabilities.Capability> lost =
        EngineCapabilities.lostWhenSwitching(
            EngineCapabilities.container(), EngineCapabilities.nativeProvisional());
    assertEquals(List.of(EngineCapabilities.Capability.values()), lost);

    assertEquals(
        List.of(),
        EngineCapabilities.lostWhenSwitching(
            EngineCapabilities.nativeProvisional(), EngineCapabilities.container()));
  }
}
