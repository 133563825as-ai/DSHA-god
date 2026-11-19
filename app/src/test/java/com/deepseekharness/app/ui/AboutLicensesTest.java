package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** 合成流覆盖严格语义与取消边界，不读取真实资产或设备数据。 */
public final class AboutLicensesTest {
  @Test
  public void exact256KiBLimitIsAcceptedButOneMoreByteIsTypedFailure() {
    byte[] exact = new byte[256 * 1024];
    java.util.Arrays.fill(exact, (byte) 'a');
    assertTrue(AboutLicenses.read(new ByteArrayInputStream(exact)).ok());
    assertEquals(
        AboutLicenses.Failure.TOO_LARGE,
        AboutLicenses.read(new ByteArrayInputStream(new byte[exact.length + 1])).failure());
  }

  @Test
  public void strictUtf8AndBomSemanticsRemainUnchanged() {
    assertEquals(
        "许可",
        AboutLicenses.read(new ByteArrayInputStream("\uFEFF许可".getBytes(StandardCharsets.UTF_8)))
            .text());
    assertEquals(
        AboutLicenses.Failure.INVALID_UTF8,
        AboutLicenses.read(new ByteArrayInputStream(new byte[] {(byte) 0xff})).failure());
    assertEquals(
        AboutLicenses.Failure.EMPTY,
        AboutLicenses.read(new ByteArrayInputStream(new byte[0])).failure());
    assertEquals(
        AboutLicenses.Failure.EMPTY,
        AboutLicenses.read(new ByteArrayInputStream("\uFEFF".getBytes(StandardCharsets.UTF_8)))
            .failure());
  }

  @Test
  public void unreadableStreamStillClosesAndReturnsTypedFailure() {
    AtomicBoolean closed = new AtomicBoolean();
    InputStream broken =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("synthetic");
          }

          @Override
          public void close() {
            closed.set(true);
          }
        };
    assertEquals(AboutLicenses.Failure.UNREADABLE, AboutLicenses.read(broken).failure());
    assertTrue(closed.get());
  }

  @Test
  public void alreadyCancelledStreamIsClosedWithoutReading() {
    AtomicBoolean closed = new AtomicBoolean();
    InputStream stream =
        new InputStream() {
          @Override
          public int read() {
            throw new AssertionError("cancelled read");
          }

          @Override
          public void close() {
            closed.set(true);
          }
        };
    assertEquals(
        AboutLicenses.Failure.UNREADABLE, AboutLicenses.read(stream, () -> true).failure());
    assertTrue(closed.get());
  }

  @Test
  public void cancellationAfterAChunkDoesNotReadAnotherChunkOrDecode() {
    AtomicBoolean cancelled = new AtomicBoolean(), closed = new AtomicBoolean();
    InputStream stream =
        new InputStream() {
          @Override
          public int read() {
            throw new AssertionError("single byte read");
          }

          @Override
          public int read(byte[] bytes, int offset, int length) {
            assertFalse("取消后不得再读", cancelled.get());
            cancelled.set(true);
            bytes[offset] = (byte) 0xff;
            return 1;
          }

          @Override
          public void close() {
            closed.set(true);
          }
        };
    assertEquals(
        AboutLicenses.Failure.UNREADABLE, AboutLicenses.read(stream, cancelled::get).failure());
    assertTrue(closed.get());
  }

  @Test
  public void indexSafetyAndReadableNamesArePreserved() throws Exception {
    var entries =
        AboutLicenses.index(
            new String[] {"../bad.txt", ".hidden.txt", "GPL-3.0.txt", "DSHA-MIT.txt", "x.md"});
    assertEquals(2, entries.size());
    assertEquals("DSHA · MIT", entries.get(0).displayName());
    assertEquals("GNU GPL v3.0", entries.get(1).displayName());
    assertThrows(IOException.class, () -> AboutLicenses.index(null));
    String[] huge = new String[129];
    for (int i = 0; i < huge.length; i++) huge[i] = "x" + i + ".txt";
    assertThrows(IOException.class, () -> AboutLicenses.index(huge));
  }
}
