package com.deepseekharness.app.util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Framed helper request. Staged bytes are inert until commit byte and EOF. */
public final class RootRequestWire {
  private static final int MAX_BYTES = 65_536;
  private static final int COMMIT = 0x43;

  private RootRequestWire() {}

  public static void stage(OutputStream output, byte[] payload) throws IOException {
    if (payload == null || payload.length == 0 || payload.length > MAX_BYTES)
      throw new IOException("ROOT_REQUEST_SIZE_INVALID");
    var framed = new DataOutputStream(output);
    framed.writeInt(payload.length);
    framed.write(payload);
    framed.flush();
  }

  public static void commit(OutputStream output) throws IOException {
    output.write(COMMIT);
    output.flush();
  }

  public static byte[] readCommitted(InputStream input) throws IOException {
    byte[] payload = readStaged(input);
    requireCommit(input);
    return payload;
  }

  public static byte[] readStaged(InputStream input) throws IOException {
    var framed = new DataInputStream(input);
    int length = framed.readInt();
    if (length <= 0 || length > MAX_BYTES) throw new IOException("ROOT_REQUEST_SIZE_INVALID");
    byte[] payload = new byte[length];
    framed.readFully(payload);
    return payload;
  }

  public static void requireCommit(InputStream input) throws IOException {
    int marker = input.read();
    if (marker < 0) throw new java.io.EOFException("ROOT_REQUEST_NOT_COMMITTED");
    if (marker != COMMIT || input.read() != -1) throw new IOException("ROOT_REQUEST_FRAME_INVALID");
  }
}
