package com.deepseekharness.app.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 有界、完整且严格的 UTF-8 取证；流的打开与关闭归调用方所有。 */
public final class WebProcTextReader {
  private static final int LIMIT = 16384;

  private WebProcTextReader() {}

  /** 达到旧满缓冲边界即拒绝，不再探读边界后的字节。 */
  public static final class LimitException extends IOException {
    private LimitException() {
      super("PROC_INFO_LIMIT");
    }
  }

  public static String read(InputStream input) throws IOException {
    Objects.requireNonNull(input, "input");
    byte[] bytes = new byte[LIMIT];
    int count = 0;
    while (count < LIMIT) {
      int read = input.read(bytes, count, LIMIT - count);
      if (read < 0) break;
      if (read == 0) {
        // 非进展短读只回退一个字节；每轮必然推进或结束，避免空转。
        int single = input.read();
        if (single < 0) break;
        bytes[count++] = (byte) single;
      } else {
        count += read;
      }
    }
    if (count == LIMIT) throw new LimitException();
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes, 0, count))
          .toString();
    } catch (CharacterCodingException invalid) {
      throw new IOException("PROC_INFO_UTF8", invalid);
    }
  }
}
