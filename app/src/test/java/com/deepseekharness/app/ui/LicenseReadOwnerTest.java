package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class LicenseReadOwnerTest {
  private static LicenseReadOwner.Source text(String value) {
    return cancelled ->
        AboutLicenses.read(
            new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)), cancelled);
  }

  @Test
  public void cancelledQueuedReadNeverOpensAndHoldsSlotUntilWorkerExits() {
    LicenseReadOwner old = new LicenseReadOwner(), fresh = new LicenseReadOwner();
    List<Runnable> io = new ArrayList<>(), ui = new ArrayList<>();
    LicenseReadOwner.Source forbidden =
        cancelled -> {
          throw new AssertionError("stale open");
        };
    assertTrue(old.read(forbidden, io::add, ui::add, result -> fail("stale result")));
    old.cancel();
    assertFalse(fresh.read(forbidden, io::add, ui::add, result -> {}));
    io.remove(0).run();
    assertTrue(ui.isEmpty());
    assertTrue(fresh.read(text("ok"), io::add, ui::add, result -> {}));
    io.remove(0).run();
    ui.remove(0).run();
  }

  @Test
  public void cancellationDuringReadClosesAndSuppressesDelivery() {
    LicenseReadOwner owner = new LicenseReadOwner();
    AtomicBoolean closed = new AtomicBoolean();
    List<Runnable> ui = new ArrayList<>();
    InputStream stream =
        new ByteArrayInputStream(new byte[16384]) {
          @Override
          public synchronized int read(byte[] b, int start, int length) {
            int count = super.read(b, start, length);
            owner.cancel();
            return count;
          }

          @Override
          public void close() {
            closed.set(true);
          }
        };
    assertTrue(
        owner.read(
            cancelled -> AboutLicenses.read(stream, cancelled),
            Runnable::run,
            ui::add,
            result -> fail("cancelled result")));
    assertTrue(closed.get());
    assertTrue(ui.isEmpty());
  }

  @Test
  public void streamCloseMustFinishBeforeAnotherOwnerCanRead() {
    LicenseReadOwner owner = new LicenseReadOwner(), other = new LicenseReadOwner();
    AtomicBoolean closed = new AtomicBoolean();
    InputStream stream =
        new ByteArrayInputStream(new byte[0]) {
          @Override
          public void close() {
            owner.cancel();
            assertFalse(other.read(text("next"), Runnable::run, Runnable::run, result -> fail()));
            closed.set(true);
          }
        };
    assertTrue(
        owner.read(
            cancelled -> AboutLicenses.read(stream, cancelled),
            Runnable::run,
            Runnable::run,
            result -> fail("retired during close")));
    assertTrue(closed.get());
    assertTrue(
        other.read(
            text("next"),
            Runnable::run,
            Runnable::run,
            result -> assertEquals("next", result.text())));
  }

  @Test
  public void oldQueuedReplyCannotReplaceNewRequest() {
    LicenseReadOwner owner = new LicenseReadOwner();
    List<Runnable> ui = new ArrayList<>();
    List<String> results = new ArrayList<>();
    assertTrue(owner.read(text("old"), Runnable::run, ui::add, result -> fail("old result")));
    assertTrue(
        owner.read(text("new"), Runnable::run, ui::add, result -> results.add(result.text())));
    ui.forEach(Runnable::run);
    assertEquals(List.of("new"), results);
  }

  @Test
  public void cancellationAfterReadButBeforeUiDeliverySuppressesDialog() {
    LicenseReadOwner owner = new LicenseReadOwner();
    List<Runnable> ui = new ArrayList<>();
    assertTrue(owner.read(text("old"), Runnable::run, ui::add, result -> fail("late dialog")));
    owner.cancel();
    ui.remove(0).run();
  }

  @Test
  public void typedStrictReadResultsArePassedThroughWithoutReinterpretation() {
    LicenseReadOwner owner = new LicenseReadOwner();
    for (AboutLicenses.Failure failure : AboutLicenses.Failure.values()) {
      AboutLicenses.ReadResult expected = new AboutLicenses.ReadResult(null, failure);
      assertTrue(
          owner.read(
              cancelled -> expected,
              Runnable::run,
              Runnable::run,
              result -> assertSame(expected, result)));
    }
    assertTrue(
        owner.read(
            text("\uFEFF许可"),
            Runnable::run,
            Runnable::run,
            result -> {
              assertTrue(result.ok());
              assertEquals("许可", result.text());
            }));
  }

  @Test
  public void rejectedExecutorsAndReadRuntimeFailureDoNotStrandSlot() {
    LicenseReadOwner owner = new LicenseReadOwner();
    assertFalse(
        owner.read(
            text("ok"),
            command -> {
              throw new java.util.concurrent.RejectedExecutionException();
            },
            Runnable::run,
            result -> fail()));
    assertTrue(
        owner.read(
            text("ok"),
            Runnable::run,
            command -> {
              throw new java.util.concurrent.RejectedExecutionException();
            },
            result -> fail()));
    assertTrue(
        owner.read(
            cancelled -> {
              throw new IllegalStateException("synthetic");
            },
            Runnable::run,
            Runnable::run,
            result -> assertEquals(AboutLicenses.Failure.UNREADABLE, result.failure())));
    assertTrue(
        owner.read(text("next"), Runnable::run, Runnable::run, result -> assertTrue(result.ok())));
  }
}
