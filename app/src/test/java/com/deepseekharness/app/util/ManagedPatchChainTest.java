package com.deepseekharness.app.util;

import org.junit.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ManagedPatchChainTest {
  @Test
  public void onlyCompleteSignedPredecessorCanBeRestoredBeforeApplyingItsSuccessor()
      throws Exception {
    var old =
        List.of(
            new ManagedPatchChain.Patch("provide;", "old-helper;provide;old-effect;"),
            new ManagedPatchChain.Patch("resolve;", "old-resolve;"));
    var current =
        List.of(
            new ManagedPatchChain.Patch("provide;", "new-helper;provide;new-effect;"),
            new ManagedPatchChain.Patch("resolve;", "new-resolve;"));
    var chain = List.of(new ManagedPatchChain.Recipe("language", current, List.of(), old));
    String archived = "old-helper;provide;old-effect; old-resolve;";
    String expected = "new-helper;provide;new-effect; new-resolve;";
    assertEquals(expected, ManagedPatchChain.apply(archived, chain, () -> null));
    assertEquals(expected, ManagedPatchChain.apply(expected, chain, () -> null));
    AtomicInteger reads = new AtomicInteger();
    assertEquals(
        expected,
        ManagedPatchChain.apply(
            "old-helper;provide;old-effect; resolve;",
            chain,
            () -> {
              reads.incrementAndGet();
              return archived;
            }));
    assertEquals(1, reads.get());
    assertThrows(
        java.io.IOException.class,
        () ->
            ManagedPatchChain.apply("old-helper;provide;old-effect; resolve;", chain, () -> null));
    assertThrows(
        java.io.IOException.class,
        () -> ManagedPatchChain.apply(archived + archived, chain, () -> null));
  }

  @Test
  public void canonicalRepairReappliesEveryPatchForTheModuleAndIsIdempotent() throws Exception {
    var chain =
        List.of(
            new ManagedPatchChain.Recipe(
                "composer",
                List.of(new ManagedPatchChain.Patch("composer-old", "composer-new")),
                List.of()),
            new ManagedPatchChain.Recipe(
                "queue",
                List.of(new ManagedPatchChain.Patch("queue-old", "queue-new")),
                List.of()));
    AtomicInteger reads = new AtomicInteger();
    String original = "composer-new incompatible-queue";
    String updated =
        ManagedPatchChain.apply(
            original,
            chain,
            () -> {
              reads.incrementAndGet();
              return "composer-old queue-old";
            });
    assertEquals("composer-new queue-new", updated);
    assertEquals(1, reads.get());
    assertEquals(
        updated,
        ManagedPatchChain.apply(
            updated,
            chain,
            () -> {
              throw new AssertionError("no restore for current chain");
            }));
    assertEquals("composer-new incompatible-queue", original);
  }

  private static String sha(String value) throws Exception {
    byte[] digest =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var output = new StringBuilder();
    for (byte part : digest)
      output.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
    return output.toString();
  }

  @Test
  public void completeOutputIdentityRejectsOldSemanticsWithSharedAnchors() throws Exception {
    String canonical = "current semantics anchor", expected = "current semantics patched";
    var chain =
        List.of(
            new ManagedPatchChain.Recipe(
                "strict",
                List.of(new ManagedPatchChain.Patch("anchor", "patched")),
                List.of(),
                List.of(),
                sha(expected)));
    var reads = new AtomicInteger();
    assertEquals(
        expected,
        ManagedPatchChain.apply(
            "old semantics anchor",
            chain,
            () -> {
              reads.incrementAndGet();
              return canonical;
            }));
    assertEquals(1, reads.get());
    assertEquals(
        expected,
        ManagedPatchChain.apply(
            expected,
            chain,
            () -> {
              throw new AssertionError("current repeat must not restore");
            }));
    assertThrows(
        java.io.IOException.class,
        () -> ManagedPatchChain.apply("old semantics anchor", chain, () -> "old semantics anchor"));
    assertThrows(
        java.io.IOException.class,
        () -> ManagedPatchChain.apply("old semantics patched", chain, () -> null));
    // 未opt-in的其它recipe保留原行为，不暗中扩大整模块约束。
    var legacy =
        List.of(
            new ManagedPatchChain.Recipe(
                "legacy", List.of(new ManagedPatchChain.Patch("anchor", "patched")), List.of()));
    assertEquals(
        "old semantics patched",
        ManagedPatchChain.apply("old semantics anchor", legacy, () -> null));
  }

  @Test
  public void markerSkipCannotBypassWholeModuleIdentity() throws Exception {
    String expected = "current marker";
    var chain =
        List.of(
            new ManagedPatchChain.Recipe(
                "strict", List.of(), List.of("marker"), List.of(), sha(expected)));
    assertEquals(expected, ManagedPatchChain.apply(expected, chain, () -> null));
    assertThrows(
        java.io.IOException.class,
        () -> ManagedPatchChain.apply("old marker", chain, () -> "wrong marker"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ManagedPatchChain.Recipe("bad", List.of(), List.of(), List.of(), ""));
  }

  @Test
  public void failedFullChainCannotReturnAPartialRestore() {
    var chain =
        List.of(
            new ManagedPatchChain.Recipe(
                "one", List.of(new ManagedPatchChain.Patch("before", "after")), List.of()),
            new ManagedPatchChain.Recipe(
                "two", List.of(new ManagedPatchChain.Patch("missing", "changed")), List.of()));
    assertThrows(
        java.io.IOException.class, () -> ManagedPatchChain.apply("damaged", chain, () -> "before"));
  }
}
