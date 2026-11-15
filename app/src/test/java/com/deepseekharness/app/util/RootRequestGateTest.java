package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** 只写内存流，不申请 su，也不在设备执行测试命令。 */
public final class RootRequestGateTest {
  private final RootRequestGate gate = new RootRequestGate();
  private final AtomicBoolean enabled = new AtomicBoolean(true);
  private static final byte[] PAYLOAD = "synthetic-request".getBytes(StandardCharsets.UTF_8);

  @Test
  public void enabledRequestWritesExactlyOnce() throws Exception {
    var permit = gate.capture(enabled::get);
    var output = new ByteArrayOutputStream();
    assertTrue(gate.write(output, PAYLOAD, permit, enabled::get));
    assertArrayEquals(
        PAYLOAD,
        RootRequestWire.readCommitted(new java.io.ByteArrayInputStream(output.toByteArray())));
    assertFalse(gate.write(output, PAYLOAD, permit, enabled::get));
    assertArrayEquals(
        PAYLOAD,
        RootRequestWire.readCommitted(new java.io.ByteArrayInputStream(output.toByteArray())));
  }

  @Test
  public void disabledRequestCannotObtainPermit() {
    enabled.set(false);
    assertNull(gate.capture(enabled::get));
  }

  @Test
  public void disabledBeforeHandoverWritesNoBytes() throws Exception {
    var permit = gate.capture(enabled::get);
    gate.update(() -> enabled.set(false));
    var output = new ByteArrayOutputStream();
    assertFalse(gate.write(output, PAYLOAD, permit, enabled::get));
    assertEquals(0, output.size());
  }

  @Test
  public void offThenOnWhileHelperStartsNeverRevivesOldRequest() throws Exception {
    var permit = gate.capture(enabled::get);
    var helperStarted = new CountDownLatch(1);
    var releaseHelper = new CountDownLatch(1);
    var worker = Executors.newSingleThreadExecutor();
    var output = new ByteArrayOutputStream();
    try {
      var sent =
          worker.submit(
              () -> {
                helperStarted.countDown();
                if (!releaseHelper.await(5, TimeUnit.SECONDS))
                  throw new AssertionError("helper barrier");
                return gate.write(output, PAYLOAD, permit, enabled::get);
              });
      assertTrue(helperStarted.await(5, TimeUnit.SECONDS));
      gate.update(() -> enabled.set(false));
      gate.update(() -> enabled.set(true));
      releaseHelper.countDown();
      assertFalse("旧代次不能借用重新开启后的权限", sent.get(5, TimeUnit.SECONDS));
      assertEquals(0, output.size());
      assertTrue(gate.write(output, PAYLOAD, gate.capture(enabled::get), enabled::get));
      assertArrayEquals(
          PAYLOAD,
          RootRequestWire.readCommitted(new java.io.ByteArrayInputStream(output.toByteArray())));
    } finally {
      releaseHelper.countDown();
      worker.shutdownNow();
    }
  }

  @Test
  public void policyChangeInvalidatesAlreadyCheckedRequest() throws Exception {
    var permit = gate.capture(enabled::get);
    gate.update(() -> {}); // 策略更新不必关闭 Root 开关。
    var output = new ByteArrayOutputStream();
    assertFalse(gate.write(output, PAYLOAD, permit, enabled::get));
    assertEquals(0, output.size());
  }

  @Test
  public void failedSettingsWriteInvalidatesOldAndBlocksNewUntilSuccessfulUpdate() {
    var permit = gate.capture(enabled::get);
    try {
      gate.update(
          () -> {
            throw new IllegalStateException("synthetic settings failure");
          });
      fail("expected settings failure");
    } catch (IllegalStateException expected) {
      assertFalse(gate.current(permit, enabled::get));
      assertNull(gate.capture(enabled::get));
    }
    gate.update(() -> enabled.set(true));
    assertFalse(gate.current(permit, enabled::get));
    assertNotNull(gate.capture(enabled::get));
  }

  @Test
  public void unreadablePermissionFailsClosed() {
    assertNull(
        gate.capture(
            () -> {
              throw new ClassCastException("synthetic bad preference");
            }));
    var permit = gate.capture(enabled::get);
    assertFalse(
        gate.current(
            permit,
            () -> {
              throw new IllegalStateException("unreadable");
            }));
  }

  @Test
  public void anotherGateCannotUsePermit() throws Exception {
    var permit = gate.capture(enabled::get);
    var output = new ByteArrayOutputStream();
    assertFalse(new RootRequestGate().write(output, PAYLOAD, permit, enabled::get));
    assertEquals(0, output.size());
  }

  @Test
  public void concurrentWritersCanConsumeOnePermitOnlyOnce() throws Exception {
    var permit = gate.capture(enabled::get);
    var output = new ByteArrayOutputStream();
    var ready = new CountDownLatch(12);
    var start = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(12);
    var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
    try {
      for (int i = 0; i < 12; i++) {
        results.add(
            workers.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("claim barrier");
                  return gate.write(output, PAYLOAD, permit, enabled::get);
                }));
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      int sent = 0;
      for (var result : results) if (result.get(5, TimeUnit.SECONDS)) sent++;
      assertEquals(1, sent);
      assertArrayEquals(
          PAYLOAD,
          RootRequestWire.readCommitted(new java.io.ByteArrayInputStream(output.toByteArray())));
    } finally {
      start.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  public void writeFailureDoesNotMakeClaimReusable() throws Exception {
    var permit = gate.capture(enabled::get);
    try {
      gate.write(
          new OutputStream() {
            @Override
            public void write(int value) throws IOException {
              throw new IOException("synthetic pipe failure");
            }
          },
          PAYLOAD,
          permit,
          enabled::get);
      fail("expected pipe failure");
    } catch (IOException expected) {
      assertFalse(gate.current(permit, enabled::get));
      assertFalse(gate.write(new ByteArrayOutputStream(), PAYLOAD, permit, enabled::get));
    }
  }

  @Test
  public void revocationDuringStagingPreventsCommitAndHelperExecution() throws Exception {
    var permit = gate.capture(enabled::get);
    var writing = new CountDownLatch(1);
    var releaseWrite = new CountDownLatch(1);
    var worker = Executors.newFixedThreadPool(2);
    var output = new ByteArrayOutputStream();
    var closed = new AtomicBoolean();
    try {
      var sent =
          worker.submit(
              () ->
                  gate.write(
                      new OutputStream() {
                        @Override
                        public void write(int value) throws IOException {
                          output.write(value);
                        }

                        @Override
                        public void write(byte[] bytes, int offset, int length) throws IOException {
                          output.write(bytes, offset, length);
                          writing.countDown();
                          try {
                            if (!releaseWrite.await(5, TimeUnit.SECONDS))
                              throw new IOException("write barrier");
                          } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IOException(error);
                          }
                        }

                        @Override
                        public void close() {
                          closed.set(true);
                        }
                      },
                      PAYLOAD,
                      permit,
                      enabled::get));
      assertTrue(writing.await(5, TimeUnit.SECONDS));
      assertTrue("请求已预写，但还不能执行", output.size() > 0);
      var revoked =
          worker.submit(
              () -> {
                gate.update(() -> enabled.set(false));
                return true;
              });
      assertTrue("撤销不应等待预写流", revoked.get(1, TimeUnit.SECONDS));
      releaseWrite.countDown();
      assertFalse("旧请求不能发送最终确认", sent.get(5, TimeUnit.SECONDS));
      assertTrue(closed.get());
      try {
        RootRequestWire.readCommitted(new java.io.ByteArrayInputStream(output.toByteArray()));
        fail("没有最终确认的旧请求不能被 helper 接受");
      } catch (java.io.EOFException expected) {
        // 预写字节不是业务派发。
      }
    } finally {
      releaseWrite.countDown();
      worker.shutdownNow();
    }
  }
}
