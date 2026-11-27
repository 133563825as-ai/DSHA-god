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

  @Test
  public void parsesExactRecordWithBothLineEndings() throws Exception {
    var expected = result("prepare", "prepared", "current", "pending", "COMPLETED");
    String json = new String(BackupJson.write(expected, 65536), StandardCharsets.UTF_8);
    for (String ending : new String[] {"\n", "\r\n"}) {
      var parsed =
          Rc1MigrationResult.parse("notice" + ending + "DSHA_RC1_MIGRATION=" + json + ending);
      assertEquals(expected, parsed);
      assertTrue(Rc1MigrationResult.boundTo(parsed, "startup", "prepare", GENERATION));
      assertTrue(Rc1MigrationResult.allowsStart(parsed));
      assertThrows(
          IOException.class,
          () -> Rc1MigrationResult.parse(" DSHA_RC1_MIGRATION=" + json + ending));
    }
    assertThrows(IOException.class, () -> Rc1MigrationResult.parse("DSHA_RC1_MIGRATION=[]"));
    assertThrows(
        IOException.class,
        () -> Rc1MigrationResult.parse("DSHA_RC1_MIGRATION={\"status\":\"a\",\"status\":\"b\"}"));
    var legacy = new HashMap<>(expected);
    legacy.put("resultVersion", 2L);
    var parsedLegacy =
        Rc1MigrationResult.parse(
            "DSHA_RC1_MIGRATION="
                + new String(BackupJson.write(legacy, 65536), StandardCharsets.UTF_8));
    assertEquals(2L, parsedLegacy.get("resultVersion"));
    assertFalse(Rc1MigrationResult.versionThree(parsedLegacy));
    assertFalse(Rc1MigrationResult.allowsStart(parsedLegacy));
  }

  @Test
  public void historicalCompletionAllowsOnlyOrdinaryStartWithoutMigrationWrites() {
    var historical =
        result("prepare", "historical-complete", "historical", "verified-historical", "NOT_SENT");
    var unchanged = new HashMap<>(historical);
    assertTrue(Rc1MigrationResult.isHistorical(historical));
    assertTrue(Rc1MigrationResult.allowsStart(historical));
    assertFalse(Rc1MigrationResult.allowsFinalize(historical));
    assertEquals("historical", historical.get("verificationScope"));
    assertEquals("NOT_SENT", historical.get("executionState"));
    assertEquals(unchanged, historical);
    var finalizeHistorical = new HashMap<>(historical);
    finalizeHistorical.put("phase", "finalize");
    assertTrue(Rc1MigrationResult.isHistorical(finalizeHistorical));
    assertTrue(Rc1MigrationResult.allowsFinalize(finalizeHistorical));
    assertFalse(Rc1MigrationResult.allowsStart(finalizeHistorical));
    historical.put("executionState", "COMPLETED");
    assertFalse(Rc1MigrationResult.isHistorical(historical));
    assertFalse(Rc1MigrationResult.allowsStart(historical));
    historical.put("executionState", "NOT_SENT");
    historical.put("phase", "verify-existing");
    assertFalse(Rc1MigrationResult.allowsStart(historical));
    assertFalse(Rc1MigrationResult.allowsFinalize(historical));
  }

  @Test
  public void historicalCompletionRequiresVerifiedHistoricalSettings() {
    for (String settings : new String[] {"verified-historical", "not-required"}) {
      var historical = result("prepare", "historical-complete", "historical", settings, "NOT_SENT");
      assertTrue(settings, Rc1MigrationResult.isHistorical(historical));
      assertTrue(settings, Rc1MigrationResult.allowsStart(historical));
    }
    for (String settings : new String[] {"pending", "verified-current", "unknown", "failed", ""}) {
      var gap = result("prepare", "historical-complete", "historical", settings, "NOT_SENT");
      assertFalse(settings, Rc1MigrationResult.isHistorical(gap));
      assertFalse(settings, Rc1MigrationResult.allowsStart(gap));
      assertTrue(settings, Rc1MigrationResult.needsAttention(gap));
    }
    var missing =
        result("finalize", "historical-complete", "historical", "verified-historical", "NOT_SENT");
    assertTrue(Rc1MigrationResult.allowsFinalize(missing));
    missing.remove("settingsImportStatus");
    assertFalse(Rc1MigrationResult.isHistorical(missing));
    assertFalse(Rc1MigrationResult.allowsFinalize(missing));
  }

  @Test
  public void resultMustBindTheCurrentStartupTicket() {
    var current = result("prepare", "prepared", "current", "pending", "COMPLETED");
    assertTrue(Rc1MigrationResult.boundTo(current, "startup"));
    assertTrue(Rc1MigrationResult.boundTo(current, "startup", "prepare", GENERATION));
    assertFalse(Rc1MigrationResult.boundTo(current, null));
    assertFalse(Rc1MigrationResult.boundTo(current, ""));
    assertFalse(Rc1MigrationResult.boundTo(current, "previous-startup"));
    assertFalse(Rc1MigrationResult.boundTo(current, "startup", "finalize", GENERATION));
    assertFalse(Rc1MigrationResult.boundTo(current, "startup", "prepare", "invalid-generation"));
    current.put("startupId", "previous-startup");
    assertTrue(Rc1MigrationResult.allowsStart(current));
    assertFalse(Rc1MigrationResult.boundTo(current, "startup", "prepare", GENERATION));
    current.put("startupId", "startup");
    current.put("generation", "11234567-89ab-cdef-0123-456789abcdef");
    assertTrue(Rc1MigrationResult.boundTo(current, "startup"));
    assertFalse(Rc1MigrationResult.boundTo(current, "startup", "prepare", GENERATION));
    current.remove("verificationScope");
    assertFalse(Rc1MigrationResult.boundTo(current, "startup"));
    assertFalse(Rc1MigrationResult.boundTo(Map.of("startupId", "startup"), "startup"));
  }

  @Test
  public void routineReceiptsAreQuietButUnknownOrHistoricalGapsStayVisible() {
    var prepared = result("prepare", "prepared", "current", "pending", "COMPLETED");
    var committed = result("finalize", "committed", "current", "verified-current", "COMPLETED");
    var historical =
        result("prepare", "historical-complete", "historical", "verified-historical", "NOT_SENT");
    for (var routine : java.util.List.of(prepared, committed, historical)) {
      assertTrue(Rc1MigrationResult.isRoutineReceipt(routine));
      assertFalse(Rc1MigrationResult.needsAttention(routine));
    }
    prepared.put("executionState", "UNKNOWN");
    committed.put("contentCopyStatus", "unknown");
    historical.put("settingsImportStatus", "unknown");
    for (var gap : java.util.List.of(prepared, committed, historical, Map.<String, Object>of())) {
      assertFalse(Rc1MigrationResult.isRoutineReceipt(gap));
      assertTrue(Rc1MigrationResult.needsAttention(gap));
    }
    assertFalse(Rc1MigrationResult.isRoutineReceipt(null));
    assertTrue(Rc1MigrationResult.needsAttention(null));
  }

  @Test
  public void statusTextCannotReplaceCurrentVerification() {
    for (String phase : new String[] {"prepare", "finalize"}) {
      String status = "prepare".equals(phase) ? "prepared" : "committed";
      String settings = "prepare".equals(phase) ? "pending" : "verified-current";
      var good = result(phase, status, "current", settings, "COMPLETED");
      assertTrue(Rc1MigrationResult.isRoutineReceipt(good));
      for (var change :
          Map.<String, Object>of(
                  "resultVersion", 2L,
                  "verificationScope", "historical",
                  "contentCopyStatus", "unknown",
                  "executionState", "NOT_SENT")
              .entrySet()) {
        var bad = new HashMap<>(good);
        bad.put(change.getKey(), change.getValue());
        assertEquals(status, bad.get("status"));
        assertFalse(change.getKey(), Rc1MigrationResult.allowsStart(bad));
        assertFalse(change.getKey(), Rc1MigrationResult.allowsFinalize(bad));
        assertTrue(change.getKey(), Rc1MigrationResult.needsAttention(bad));
      }
      var textOnly = Map.<String, Object>of("status", status, "resultVersion", 3L);
      assertFalse(Rc1MigrationResult.isRoutineReceipt(textOnly));
      assertTrue(Rc1MigrationResult.needsAttention(textOnly));
    }
  }
}
