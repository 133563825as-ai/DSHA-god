package com.deepseekharness.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class Rc1MigrationReuseTest {
  private static final String GENERATION = "aef8c2a7-00b2-4141-a0a8-1da30ba47981";
  private static final String SHA = "a".repeat(64);
  private static final String CHANGED = "b".repeat(64);

  private static Map<String, Object> current(Map<String, String> inputs) {
    return new HashMap<>(
        Map.of(
            "version",
            2L,
            "generation",
            GENERATION,
            "dshHome",
            "/root/.dsh",
            "dataRoot",
            Map.of("path", "/root/.dsh", "device", "179", "inode", "41001"),
            "inputs",
            inputs));
  }

  private static Map<String, Object> prepared(Map<String, String> inputs) {
    Map<String, Object> value = current(inputs);
    value.put("status", "prepared");
    value.put("protectionComplete", true);
    value.put("dshVersion", "0.2.0-rc.2");
    return value;
  }

  private static Map<String, Object> receipt() {
    return new HashMap<>(
        Map.of(
            "version",
            2L,
            "generation",
            GENERATION,
            "status",
            "committed",
            "protectionComplete",
            true,
            "sourcePreserved",
            true,
            "settingsImported",
            true,
            "dshVersion",
            "0.2.0-rc.2"));
  }

  private static boolean matches(Map<String, String> old, Map<String, String> now) {
    return Rc1MigrationReuse.matches(current(old), prepared(old), receipt(), "179", "41001", now);
  }

  @Test
  public void expectedSectionsCannotBeInferredFromCurrentLedgerKeys() {
    var ledger = new HashMap<String, Object>();
    ledger.put("version", 2L);
    ledger.put("generation", GENERATION);
    ledger.put("sourceSha256", SHA);
    ledger.put("status", "verified");
    ledger.put("sections", Map.of("first", Map.of("status", "verified")));
    org.junit.Assert.assertEquals("unknown", Rc1MigrationReuse.ledgerState(ledger));
    var checkpoint = Map.<String, Object>of("settingsSections", java.util.List.of("first"));
    org.junit.Assert.assertEquals(
        "verified",
        Rc1MigrationReuse.ledgerState(
            ledger, Rc1MigrationReuse.expectedSections(ledger, checkpoint)));
    ledger.put("expectedSections", java.util.List.of("first", "second"));
    org.junit.Assert.assertNull(Rc1MigrationReuse.expectedSections(ledger, checkpoint));
    ledger.put("expectedSections", java.util.List.of("first", "first"));
    org.junit.Assert.assertNull(Rc1MigrationReuse.expectedSections(ledger, checkpoint));
    ledger.put("expectedSections", java.util.List.of(""));
    org.junit.Assert.assertNull(Rc1MigrationReuse.expectedSections(ledger, checkpoint));
    ledger.put("expectedSections", java.util.List.of("first"));
    for (Object status : java.util.List.of("future", "writing", "unknown", true)) {
      ledger.put("status", status);
      org.junit.Assert.assertEquals("unknown", Rc1MigrationReuse.ledgerState(ledger));
    }
    ledger.put("status", "verified");
    ledger.put("expectedSections", java.util.List.of());
    ledger.put("sections", Map.of());
    org.junit.Assert.assertEquals("verified", Rc1MigrationReuse.ledgerState(ledger));
    assertTrue(Rc1MigrationReuse.ledgerBinding(GENERATION, SHA, ledger));
    ledger.put("version", 3L);
    assertFalse(Rc1MigrationReuse.ledgerBinding(GENERATION, SHA, ledger));
    assertFalse(Rc1MigrationReuse.ledgerBinding(GENERATION, null, ledger));
  }

  @Test
  public void checkpointCannotUseNullShaToHideSettingsOrMalformedInputs() {
    var index = current(Map.of("settings.yaml", SHA));
    var checkpoint = prepared(Map.of("settings.yaml", SHA));
    checkpoint.put("inputDigest", SHA);
    checkpoint.put("settingsSha256", SHA);
    assertTrue(Rc1MigrationReuse.checkpointBinding(index, checkpoint));
    index.put("startupContext", Map.of("inputFingerprint", CHANGED));
    assertFalse(Rc1MigrationReuse.checkpointBinding(index, checkpoint));
    index.remove("startupContext");
    for (String field :
        java.util.List.of("version", "generation", "inputDigest", "settingsSha256", "inputs")) {
      var bad = new HashMap<>(checkpoint);
      bad.remove(field);
      assertFalse(field, Rc1MigrationReuse.checkpointBinding(index, bad));
    }
    checkpoint.put("settingsSha256", null);
    assertFalse(Rc1MigrationReuse.checkpointBinding(index, checkpoint));
    var empty = prepared(Map.of());
    empty.put("inputDigest", SHA);
    empty.put("settingsSha256", null);
    assertTrue(Rc1MigrationReuse.checkpointBinding(current(Map.of()), empty));
    assertFalse(Rc1MigrationReuse.sameInputs(Map.of(), null));
  }

  @Test
  public void completedCheckpointAllowsIdenticalOrAbsentInputs() {
    assertTrue(matches(Map.of("settings.yaml", SHA), Map.of("settings.yaml", SHA)));
    assertTrue(matches(Map.of(), Map.of()));
  }

  @Test
  public void singleSettingsRenameKeepsSourceContentEvidence() {
    assertTrue(matches(Map.of("settings.yaml", SHA), Map.of("settings.yaml.imported", SHA)));
    assertTrue(matches(Map.of("settings.yaml.imported", SHA), Map.of("settings.yaml", SHA)));
    assertFalse(matches(Map.of("settings.yaml", SHA), Map.of("settings.yaml.imported", CHANGED)));
  }

  @Test
  public void changedAddedOrRemovedSettingsRequireGuestProtection() {
    assertFalse(matches(Map.of("settings.yaml", SHA), Map.of("settings.yaml", CHANGED)));
    assertFalse(matches(Map.of(), Map.of("settings.yaml", SHA)));
    assertFalse(matches(Map.of("settings.yaml", SHA), Map.of()));
  }

  @Test
  public void twoSettingsInputsCannotHideChangesInImportedInput() {
    Map<String, String> both = Map.of("settings.yaml", SHA, "settings.yaml.imported", CHANGED);
    assertTrue(matches(both, both));
    assertFalse(matches(both, Map.of("settings.yaml", SHA, "settings.yaml.imported", SHA)));
    assertFalse(matches(both, Map.of("settings.yaml", SHA)));
    assertFalse(matches(Map.of("settings.yaml", SHA), both));
  }

  @Test
  public void restoredGenerationAlwaysInvalidatesOldCheckpoint() {
    assertFalse(matches(Map.of("restore", SHA), Map.of("restore", CHANGED)));
    assertFalse(matches(Map.of(), Map.of("restore", SHA)));
    assertFalse(matches(Map.of("restore", SHA), Map.of()));
  }

  @Test
  public void unavailableOrMalformedFreshInputCannotAuthorizeReuse() {
    assertFalse(matches(Map.of(), null));
    assertFalse(matches(Map.of(), Map.of("settings.yaml", "")));
    assertFalse(matches(Map.of(), Map.of("settings.yaml", SHA.toUpperCase())));
    assertFalse(matches(Map.of(), Map.of("archiveVersion", SHA)));
  }

  @Test
  public void selectedRootIdentityMustMatchBothHostRecords() {
    Map<String, String> inputs = Map.of("settings.yaml", SHA);
    assertFalse(
        Rc1MigrationReuse.matches(
            current(inputs), prepared(inputs), receipt(), "179", "41002", inputs));
    assertFalse(
        Rc1MigrationReuse.matches(
            current(inputs), prepared(inputs), receipt(), "180", "41001", inputs));
    Map<String, Object> bad = prepared(inputs);
    bad.put("dataRoot", Map.of("path", "/root/.dsh", "device", "179", "inode", "41002"));
    assertFalse(Rc1MigrationReuse.matches(current(inputs), bad, receipt(), "179", "41001", inputs));
  }

  @Test
  public void generationVersionAndHomeMustBeVerifiedHostSchema() {
    Map<String, String> inputs = Map.of();
    for (Map.Entry<String, Object> change :
        Map.<String, Object>of(
                "version",
                1L,
                "generation",
                "not-a-generation",
                "dshHome",
                "/another/home",
                "inputs",
                Map.of("settings.yaml", "bad"))
            .entrySet()) {
      Map<String, Object> bad = current(inputs);
      bad.put(change.getKey(), change.getValue());
      assertFalse(
          Rc1MigrationReuse.matches(bad, prepared(inputs), receipt(), "179", "41001", inputs));
    }
  }

  @Test
  public void receiptMustBeCommittedCompleteAndForExactPreparedGeneration() {
    Map<String, String> inputs = Map.of();
    for (Map.Entry<String, Object> change :
        Map.<String, Object>of(
                "status",
                "pending",
                "protectionComplete",
                false,
                "sourcePreserved",
                false,
                "settingsImported",
                false,
                "generation",
                "d92bb647-7dad-4e67-9c48-82c1a7e16d62",
                "dshVersion",
                "another-runtime")
            .entrySet()) {
      Map<String, Object> bad = receipt();
      bad.put(change.getKey(), change.getValue());
      assertFalse(
          Rc1MigrationReuse.matches(
              current(inputs), prepared(inputs), bad, "179", "41001", inputs));
    }
  }

  @Test
  public void preparedProtectionCannotBeReplacedWithReceiptSuccess() {
    Map<String, String> inputs = Map.of();
    Map<String, Object> bad = prepared(inputs);
    bad.put("protectionComplete", false);
    assertFalse(Rc1MigrationReuse.matches(current(inputs), bad, receipt(), "179", "41001", inputs));
    bad = prepared(inputs);
    bad.put("inputs", Map.of("settings.yaml", SHA));
    assertFalse(Rc1MigrationReuse.matches(current(inputs), bad, receipt(), "179", "41001", inputs));
  }

  @Test
  public void missingHostRecordsAndUnknownIdentityRequireActualGuestCheck() {
    assertFalse(
        Rc1MigrationReuse.matches(null, prepared(Map.of()), receipt(), "179", "41001", Map.of()));
    assertFalse(
        Rc1MigrationReuse.matches(current(Map.of()), null, receipt(), "179", "41001", Map.of()));
    assertFalse(
        Rc1MigrationReuse.matches(
            current(Map.of()), prepared(Map.of()), null, "179", "41001", Map.of()));
    assertFalse(
        Rc1MigrationReuse.matches(
            current(Map.of()), prepared(Map.of()), receipt(), "?", "41001", Map.of()));
  }
}
