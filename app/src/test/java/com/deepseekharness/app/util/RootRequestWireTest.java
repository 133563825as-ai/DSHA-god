package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class RootRequestWireTest {
  private static final byte[] PAYLOAD = "synthetic-root-request".getBytes(StandardCharsets.UTF_8);

  @Test
  public void committedFrameIsAcceptedOnlyAfterEof() throws Exception {
    var bytes = new ByteArrayOutputStream();
    RootRequestWire.stage(bytes, PAYLOAD);
    RootRequestWire.commit(bytes);
    assertArrayEquals(
        PAYLOAD, RootRequestWire.readCommitted(new ByteArrayInputStream(bytes.toByteArray())));
  }

  @Test
  public void stagedRequestWithoutCommitIsRejected() throws Exception {
    var bytes = new ByteArrayOutputStream();
    RootRequestWire.stage(bytes, PAYLOAD);
    try {
      RootRequestWire.readCommitted(new ByteArrayInputStream(bytes.toByteArray()));
      fail("staging alone cannot dispatch");
    } catch (EOFException expected) {
    }
  }

  @Test
  public void trailingBytesAfterCommitAreRejected() throws Exception {
    var bytes = new ByteArrayOutputStream();
    RootRequestWire.stage(bytes, PAYLOAD);
    RootRequestWire.commit(bytes);
    bytes.write(0x7f);
    try {
      RootRequestWire.readCommitted(new ByteArrayInputStream(bytes.toByteArray()));
      fail("extra bytes cannot change the committed request");
    } catch (IOException expected) {
    }
  }

  @Test
  public void oversizedFrameIsRejectedBeforeAllocation() throws Exception {
    var bytes = new ByteArrayOutputStream();
    new java.io.DataOutputStream(bytes).writeInt(65_537);
    try {
      RootRequestWire.readCommitted(new ByteArrayInputStream(bytes.toByteArray()));
      fail("oversized request must be refused");
    } catch (IOException expected) {
    }
  }
}
