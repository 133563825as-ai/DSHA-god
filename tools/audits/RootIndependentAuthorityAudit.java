package com.deepseekharness.app.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Diagnostic only: all requests are synthetic and all output stays in memory. */
public final class RootIndependentAuthorityAudit {
  private static final byte[] PAYLOAD = "synthetic-request".getBytes(StandardCharsets.UTF_8);

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void await(CountDownLatch latch) {
    try {
      require(latch.await(10, TimeUnit.SECONDS), "audit barrier timed out");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }

  private static void businessRevokedBeforeFinalCheck() throws Exception {
    var gate = new RootRequestGate();
    var businessAllowed = new AtomicBoolean(true);
    var permit = gate.capture(() -> true);
    var output = new ByteArrayOutputStream();
    boolean sent = gate.write(output, PAYLOAD, permit, businessAllowed::get,
        () -> businessAllowed.set(false));
    require(!sent, "revocation visible to final check must reject");
    require(output.size() == 4 + PAYLOAD.length, "no commit byte may be written");
    System.out.println("CONTROL_PASS business_revoke_before_final_check: staged_only=true");
  }

  private static void rootRevokedBeforeFinalCheck() throws Exception {
    var gate = new RootRequestGate();
    var rootAllowed = new AtomicBoolean(true);
    var permit = gate.capture(rootAllowed::get);
    var output = new ByteArrayOutputStream();
    boolean sent = gate.write(output, PAYLOAD, permit, rootAllowed::get,
        () -> gate.update(() -> rootAllowed.set(false)));
    require(!sent, "Root generation revocation must reject");
    require(output.size() == 4 + PAYLOAD.length, "no commit byte may be written");
    System.out.println("CONTROL_PASS root_revoke_before_final_check: staged_only=true");
  }

  private static void independentBusinessRevocationAfterSnapshot() throws Exception {
    var gate = new RootRequestGate();
    var businessAllowed = new AtomicBoolean(true);
    var staged = new AtomicBoolean();
    var finalSnapshotRead = new CountDownLatch(1);
    var releaseSnapshot = new CountDownLatch(1);
    var output = new ByteArrayOutputStream();
    var permit = gate.capture(() -> true);
    var worker = Executors.newSingleThreadExecutor();
    try {
      var sent = worker.submit(() -> gate.write(output, PAYLOAD, permit, () -> {
        boolean snapshot = businessAllowed.get();
        if (staged.get()) {
          finalSnapshotRead.countDown();
          await(releaseSnapshot);
        }
        return snapshot;
      }, () -> staged.set(true)));
      await(finalSnapshotRead);
      // Models TARGET_LOCK being released by dispatchCurrent before Root commit.
      require(output.size() == 4 + PAYLOAD.length, "revocation must precede commit byte");
      businessAllowed.set(false);
      require(!businessAllowed.get(), "independent revocation has completed");
      releaseSnapshot.countDown();
      // This is an expected unsafe observation, not the desired security contract.
      require(sent.get(10, TimeUnit.SECONDS), "known gap no longer reproduced: reassess");
      require(java.util.Arrays.equals(PAYLOAD,
          RootRequestWire.readCommitted(new ByteArrayInputStream(output.toByteArray()))),
          "the real wire decoder must accept commit and EOF after revocation");
      require(!gate.write(new ByteArrayOutputStream(), PAYLOAD, permit, () -> true),
          "a consumed permit must still reject replay");
      System.out.println("KNOWN_GAP independent_business_revoke_after_snapshot: "
          + "revoked_before_commit=true committed_after_revoke=true replay_rejected=true");
    } finally {
      releaseSnapshot.countDown();
      worker.shutdownNow();
      require(worker.awaitTermination(10, TimeUnit.SECONDS), "audit worker did not stop");
    }
  }

  public static void main(String[] args) throws Exception {
    businessRevokedBeforeFinalCheck();
    rootRevokedBeforeFinalCheck();
    independentBusinessRevocationAfterSnapshot();
    System.out.println("AUDIT_COMPLETE controls=2 known_gaps=1 security_acceptance=NOT_PASSED");
  }
}
