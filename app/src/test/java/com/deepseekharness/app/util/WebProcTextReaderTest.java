package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public final class WebProcTextReaderTest {
  private static class Chunks extends InputStream {
    final byte[] bytes;
    final int chunk;
    final boolean zeroBulk;
    int position, bulkCalls, singleCalls, eofCalls;
    boolean closed;

    Chunks(byte[] bytes, int chunk, boolean zeroBulk) {
      this.bytes = bytes;
      this.chunk = chunk;
      this.zeroBulk = zeroBulk;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
      bulkCalls++;
      assertTrue("读取预算必须随累计量收缩", length <= 16384 - position);
      if (zeroBulk) return 0;
      if (position == bytes.length) {
        eofCalls++;
        return -1;
      }
      int size = Math.min(Math.min(chunk, length), bytes.length - position);
      System.arraycopy(bytes, position, target, offset, size);
      position += size;
      return size;
    }

    @Override
    public int read() throws IOException {
      singleCalls++;
      if (position == bytes.length) {
        eofCalls++;
        return -1;
      }
      return bytes[position++] & 0xff;
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static Chunks chunks(String text, int chunk) {
    return new Chunks(text.getBytes(StandardCharsets.UTF_8), chunk, false);
  }

  @Test
  public void oneAndSeveralByteShortReadsContinueUntilEof() throws Exception {
    String text = "node\0/usr/local/bin/dsh\0web\0";
    for (int size : new int[] {1, 3, 7}) {
      Chunks input = chunks(text, size);
      assertEquals(text, WebProcTextReader.read(input));
      assertEquals(1, input.eofCalls);
      assertTrue(input.bulkCalls > 1);
      assertEquals(0, input.singleCalls);
      assertFalse(input.closed);
    }
  }

  @Test
  public void shortReadMustNotHideDuplicateMarkerTail() throws Exception {
    // 旧单次 read 会把首段误报为完整证据，漏掉后续重复标记。
    String prefix = "DSH_TASK_TOKEN=first\0";
    String full = prefix + "DSH_TASK_TOKEN=second\0";
    assertEquals(full, WebProcTextReader.read(chunks(full, prefix.length())));
  }

  @Test
  public void zeroBulkReadsUseBoundedSingleByteProgressIncludingNul() throws Exception {
    String text = "A\0中\0";
    Chunks input = new Chunks(text.getBytes(StandardCharsets.UTF_8), 1, true);
    assertEquals(text, WebProcTextReader.read(input));
    assertEquals(input.bytes.length + 1, input.bulkCalls);
    assertEquals(input.bulkCalls, input.singleCalls);
    assertEquals(1, input.eofCalls);
    assertFalse(input.closed);
  }

  @Test
  public void emptyEofAndZeroThenEofReturnEmpty() throws Exception {
    for (boolean zero : new boolean[] {false, true}) {
      Chunks input = new Chunks(new byte[0], 1, zero);
      assertEquals("", WebProcTextReader.read(input));
      assertEquals(1, input.bulkCalls);
      assertEquals(zero ? 1 : 0, input.singleCalls);
      assertEquals(1, input.eofCalls);
      assertFalse(input.closed);
    }
  }

  @Test
  public void belowLimitRequiresEofAndPreservesAllBytes() throws Exception {
    String text = "a".repeat(16383);
    for (int size : new int[] {1, 37, 16384}) {
      Chunks input = chunks(text, size);
      assertEquals(text, WebProcTextReader.read(input));
      assertEquals(16383, input.position);
      assertEquals(1, input.eofCalls);
    }
  }

  @Test
  public void fullBufferRejectsWithoutPeekForShortFullAndZeroReads() throws Exception {
    for (int length : new int[] {16384, 16385, 32768}) {
      for (int size : new int[] {1, 53, 16384}) {
        for (boolean zero : new boolean[] {false, true}) {
          byte[] bytes = new byte[length];
          Arrays.fill(bytes, (byte) 'a');
          Chunks input = new Chunks(bytes, size, zero);
          try {
            WebProcTextReader.read(input);
            fail("满 16384 必须拒绝，即使下一读会 EOF");
          } catch (WebProcTextReader.LimitException expected) {
            assertEquals("PROC_INFO_LIMIT", expected.getMessage());
          }
          assertEquals(16384, input.position);
          assertEquals(0, input.eofCalls);
          assertTrue(input.bulkCalls <= 16384);
          assertEquals(zero ? 16384 : 0, input.singleCalls);
          assertFalse(input.closed);
        }
      }
    }
  }

  @Test
  public void multibyteUtf8CanCrossEveryChunkBoundary() throws Exception {
    String text = "中🙂é\0尾";
    for (int size : new int[] {1, 2, 3, 4, 5}) {
      assertEquals(text, WebProcTextReader.read(chunks(text, size)));
    }
    String boundary = "a".repeat(16379) + "🙂";
    assertEquals(boundary, WebProcTextReader.read(chunks(boundary, 3)));
  }

  @Test
  public void malformedUtf8IsNeverReplacedOrReturnedAsPartialText() throws Exception {
    byte[][] invalid = {
      {(byte) 0x80},
      {(byte) 0xc0, (byte) 0xaf},
      {(byte) 0xe4, (byte) 0xb8},
      {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
      {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
      {'a', 0, (byte) 0xc3, 0x28}
    };
    for (byte[] bytes : invalid) {
      Chunks input = new Chunks(bytes, 1, false);
      try {
        WebProcTextReader.read(input);
        fail("非法 UTF-8 不是完整文本证据");
      } catch (IOException expected) {
        assertFalse(expected instanceof WebProcTextReader.LimitException);
        assertEquals("PROC_INFO_UTF8", expected.getMessage());
        assertTrue(expected.getCause() instanceof CharacterCodingException);
      }
      assertEquals(1, input.eofCalls);
      assertFalse(input.closed);
    }
  }

  @Test
  public void nulSeparatorsWhitespaceAndLiteralReplacementCharacterStayExact() throws Exception {
    String text = " \0\0A=x\0A=y\0\t\n\ufffd\0";
    assertEquals(text, WebProcTextReader.read(chunks(text, 2)));
  }

  @Test
  public void ioFailureAndInterruptedIoNeverReturnPrefixOrCloseOrRetry() throws Exception {
    for (boolean zero : new boolean[] {false, true}) {
      for (IOException failure :
          new IOException[] {new IOException("SYNTHETIC_IO"), new InterruptedIOException("HALT")}) {
        Chunks input =
            new Chunks(new byte[] {'a'}, 1, zero) {
              @Override
              public int read(byte[] target, int offset, int length) throws IOException {
                if (position > 0) throw failure;
                return super.read(target, offset, length);
              }

              @Override
              public int read() throws IOException {
                if (position > 0) throw failure;
                return super.read();
              }
            };
        try {
          WebProcTextReader.read(input);
          fail("失败后不得返回已读前缀");
        } catch (IOException expected) {
          assertSame(failure, expected);
        }
        assertEquals(1, input.position);
        assertFalse(input.closed);
      }
    }
  }

  @Test
  public void zeroReadFallbackFailurePropagatesOnceWithoutClosing() throws Exception {
    IOException failure = new InterruptedIOException("SINGLE_HALTED");
    Chunks input =
        new Chunks(new byte[0], 1, true) {
          @Override
          public int read() throws IOException {
            singleCalls++;
            throw failure;
          }
        };
    try {
      WebProcTextReader.read(input);
      fail("单字节回退失败不得重试或返回空证据");
    } catch (IOException expected) {
      assertSame(failure, expected);
    }
    assertEquals(1, input.bulkCalls);
    assertEquals(1, input.singleCalls);
    assertFalse(input.closed);
  }

  @Test
  public void callerKeepsOwnershipOfOrdinaryStream() throws Exception {
    ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {'a'});
    assertEquals("a", WebProcTextReader.read(input));
    input.reset();
    assertEquals('a', input.read());
  }

  @Test
  public void nullStreamFailsExplicitly() throws Exception {
    try {
      WebProcTextReader.read(null);
      fail("null 不是空证据");
    } catch (NullPointerException expected) {
      assertEquals("input", expected.getMessage());
    }
  }
}
