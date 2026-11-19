package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.WebSessionProof;
import com.deepseekharness.app.util.WebStopDiagnostic;
import com.deepseekharness.app.util.WebStopException;
import org.junit.Test;

/** 只用假观察验证三态映射；不读取内核、文件或发信号。 */
public final class WebRecordedForcePreparationTest {
  private static final String BOOT = "12345678-1234-1234-1234-123456789abc";
  private static final WebSessionProof.Saved SAVED =
      new WebSessionProof.Saved(
          BOOT, 7, BOOT, "ab".repeat(32), 42, 123, "web", 41, 122, "launcher");

  private static final class Manager extends WebProcessManager {
    SessionObservation observation = SessionObservation.UNKNOWN;
    int observations;

    Manager() {
      super(null);
    }

    @Override
    public SessionObservation observeRecordedSession(WebSessionProof.Saved saved) {
      assertSame(SAVED, saved);
      observations++;
      return observation;
    }
  }

  @Test
  public void missingRecordIsUnknownWithoutObservation() throws Exception {
    Manager manager = new Manager();
    var prepared = manager.prepareRecordedForceStopResult(null);
    assertEquals(WebProcessManager.ForcePreparationStatus.UNKNOWN, prepared.status());
    assertNull(prepared.candidate());
    assertEquals(0, manager.observations);
    assertEquals(WebStopDiagnostic.Code.RECORDED_SESSION_UNCONFIRMED, prepared.diagnostic().code());
    assertThrows(WebStopException.class, () -> manager.prepareRecordedForceStop(null));
  }

  @Test
  public void liveAloneCarriesGuestOnlyCandidate() throws Exception {
    Manager manager = new Manager();
    manager.observation = WebProcessManager.SessionObservation.LIVE;
    var prepared = manager.prepareRecordedForceStopResult(SAVED);
    assertEquals(WebProcessManager.ForcePreparationStatus.LIVE_CANDIDATE, prepared.status());
    assertEquals(42, prepared.candidate().pid());
    assertFalse(prepared.candidate().retiresPreviousLauncher());
    assertNotNull(manager.prepareRecordedForceStop(SAVED));
  }

  @Test
  public void exitKeepsPositiveFactAndLegacyThrowsStableCode() throws Exception {
    Manager manager = new Manager();
    manager.observation = WebProcessManager.SessionObservation.EXITED;
    var prepared = manager.prepareRecordedForceStopResult(SAVED);
    assertEquals(WebProcessManager.ForcePreparationStatus.ALREADY_EXITED, prepared.status());
    assertNull(prepared.candidate());
    assertEquals(
        WebStopDiagnostic.Code.RECORDED_GUEST_ALREADY_EXITED, prepared.diagnostic().code());
    WebStopException failure =
        assertThrows(WebStopException.class, () -> manager.prepareRecordedForceStop(SAVED));
    assertEquals(prepared.diagnostic(), failure.diagnostic());
  }

  @Test
  public void unknownNeverCarriesCandidateOrClaimsExit() throws Exception {
    Manager manager = new Manager();
    var prepared = manager.prepareRecordedForceStopResult(SAVED);
    assertEquals(WebProcessManager.ForcePreparationStatus.UNKNOWN, prepared.status());
    assertNull(prepared.candidate());
    assertEquals(WebStopDiagnostic.Code.RECORDED_SESSION_UNCONFIRMED, prepared.diagnostic().code());
    assertThrows(WebStopException.class, () -> manager.prepareRecordedForceStop(SAVED));
  }

  @Test
  public void trialIsRejectedBeforeAnyObservation() throws Exception {
    Manager manager = new Manager();
    var records = WebProcessManager.class.getDeclaredField("records");
    records.setAccessible(true);
    records.set(manager, new java.io.File("synthetic-trial-records"));
    WebStopException failure =
        assertThrows(WebStopException.class, () -> manager.prepareRecordedForceStopResult(SAVED));
    assertEquals(WebStopDiagnostic.Code.FORCE_EVIDENCE_UNCONFIRMED, failure.diagnostic().code());
    assertTrue(failure.diagnostic().arguments().contains("TRIAL_FORBIDDEN"));
    assertEquals(0, manager.observations);
  }

  @Test
  public void recordConstructorRejectsCandidateStatusMismatch() throws Exception {
    Manager manager = new Manager();
    manager.observation = WebProcessManager.SessionObservation.LIVE;
    var candidate = manager.prepareRecordedForceStopResult(SAVED).candidate();
    var diagnostic = WebStopDiagnostic.of(WebStopDiagnostic.Code.RECORDED_SESSION_UNCONFIRMED);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WebProcessManager.RecordedForcePreparation(
                WebProcessManager.ForcePreparationStatus.UNKNOWN, candidate, diagnostic));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WebProcessManager.RecordedForcePreparation(
                WebProcessManager.ForcePreparationStatus.LIVE_CANDIDATE, null, diagnostic));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WebProcessManager.RecordedForcePreparation(
                WebProcessManager.ForcePreparationStatus.UNKNOWN, null, null));
    assertThrows(
        NullPointerException.class,
        () -> new WebProcessManager.RecordedForcePreparation(null, null, diagnostic));
  }
}
