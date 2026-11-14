package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.BackupJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class Rc1MigrationResultTest {
  private static final String GENERATION = "01234567-89ab-cdef-0123-456789abcdef";

  private static Map<String, Object> result(
      String phase, String status, String scope, String settings, String execution) {
    var value = new HashMap<String, Object>();
    value.put("resultVersion", 3L);
    value.put("phase", phase);
    value.put("status", status);
    value.put("startupId", "startup");
    value.put("generation", GENERATION);
    value.put("verificationScope", scope);
    value.put("contentCopyStatus", "verified");
    value.put("sourceObjectIdentity", "unknown");
    value.put("settingsImportStatus", settings);
    value.put("executionState", execution);
    return value;
  }

  @Test
  public void parsesExactRecordWithBothLineEndingsAndRejectsDuplicates() throws Exception {
    var value = result("prepare", "prepared", "current", "pending", "COMPLETED");
    String json = new String(BackupJson.write(value, 65536), StandardCharsets.UTF_8);
    for (String ending : new String[] {"\n", "\r\n"})
      assertTrue(
          Rc1MigrationResult.allowsStart(
              Rc1MigrationResult.parse("notice" + ending + "DSHA_RC1_MIGRATION=" + json + ending)));
    assertThrows(IOException.class, () -> Rc1MigrationResult.parse("status=prepared"));
    assertThrows(
        IOException.class,
        () -> Rc1MigrationResult.parse("DSHA_RC1_MIGRATION={}\nDSHA_RC1_MIGRATION={}"));
  }

  @Test
  public void positiveCombinationsArePhaseSpecific() {
    for (String settings : new String[] {"pending", "not-required"})
      assertTrue(
          Rc1MigrationResult.allowsStart(
              result("prepare", "prepared", "current", settings, "COMPLETED")));
    for (String settings : new String[] {"verified-historical", "not-required"}) {
      assertTrue(
          Rc1MigrationResult.allowsStart(
              result("prepare", "historical-complete", "historical", settings, "NOT_SENT")));
      var historical =
          result("finalize", "historical-complete", "historical", settings, "NOT_SENT");
      assertTrue(Rc1MigrationResult.allowsFinalize(historical));
      assertFalse(Rc1MigrationResult.allowsStart(historical));
    }
    for (String settings :
        new String[] {"verified-current", "verified-historical", "not-required"}) {
      var committed = result("finalize", "committed", "current", settings, "COMPLETED");
      assertTrue(Rc1MigrationResult.allowsFinalize(committed));
      assertFalse(Rc1MigrationResult.allowsStart(committed));
    }
  }

  @Test
  public void everyRequiredFieldMissingOrWrongTypeIsDenied() {
    var good = result("prepare", "prepared", "current", "pending", "COMPLETED");
    for (String key : good.keySet()) {
      var bad = new HashMap<>(good);
      bad.remove(key);
      assertFalse(key, Rc1MigrationResult.allowsStart(bad));
      bad.put(key, Boolean.TRUE);
      assertFalse(key, Rc1MigrationResult.allowsStart(bad));
    }
    assertTrue(Rc1MigrationResult.boundTo(good, "startup", "prepare", GENERATION));
    assertFalse(Rc1MigrationResult.boundTo(good, "other", "prepare", GENERATION));
    assertFalse(Rc1MigrationResult.boundTo(good, "startup", "finalize", GENERATION));
    assertFalse(
        Rc1MigrationResult.boundTo(
            good, "startup", "prepare", "11234567-89ab-cdef-0123-456789abcdef"));
  }

  @Test
  public void wrongCombinationsCannotBorrowLocalSuccessFields() {
    var good = result("prepare", "prepared", "current", "pending", "COMPLETED");
    for (var change :
        Map.<String, Object>of(
                "resultVersion",
                2L,
                "phase",
                "finalize",
                "status",
                "committed",
                "generation",
                "bad",
                "verificationScope",
                "historical",
                "contentCopyStatus",
                "unknown",
                "sourceObjectIdentity",
                "verified",
                "settingsImportStatus",
                "verified-current",
                "executionState",
                "UNKNOWN")
            .entrySet()) {
      var bad = new HashMap<>(good);
      bad.put(change.getKey(), change.getValue());
      assertFalse(change.getKey(), Rc1MigrationResult.allowsStart(bad));
    }
    var historical =
        result("finalize", "historical-complete", "historical", "verified-historical", "COMPLETED");
    assertFalse(Rc1MigrationResult.isRoutineReceipt(historical));
    assertFalse(
        Rc1MigrationResult.isRoutineReceipt(
            Map.of("status", "committed", "sourcePreserved", true, "settingsImported", true)));
    assertFalse(Rc1MigrationResult.allowsStart(null));
  }

  @Test
  public void dshMissingIsTheOnlyGenerationExceptionAndNeedsAllFields() {
    var skipped = result("prepare", "skipped", "none", "unknown", "NOT_SENT");
    skipped.remove("generation");
    skipped.put("contentCopyStatus", "unknown");
    skipped.put("reason", "DSH_MISSING");
    assertTrue(Rc1MigrationResult.allowsStart(skipped));
    skipped.put("generation", GENERATION);
    assertFalse(Rc1MigrationResult.allowsStart(skipped));
    assertFalse(
        Rc1MigrationResult.allowsStart(
            Map.of("resultVersion", 3L, "status", "skipped", "reason", "DSH_MISSING")));
  }
}
