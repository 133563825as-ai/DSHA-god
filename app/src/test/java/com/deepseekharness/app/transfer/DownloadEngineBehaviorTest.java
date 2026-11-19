package com.deepseekharness.app.transfer;

import static com.deepseekharness.app.transfer.TransferBehaviorTestSupport.*;
import static org.junit.Assert.*;

import android.app.Application;
import android.net.Uri;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.core.PluginRepositoryQueryLifecycleTest;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

/** 真实持久化队列与发布流程，只控制调度、Android 存储和宿主初始化边界。 */
@RunWith(PluginRepositoryQueryLifecycleTest.NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    shadows = {
      TransferBehaviorTestSupport.Controller.class,
      DownloadEngineBehaviorTest.CompletionFiles.class,
      TransferBehaviorTestSupport.Documents.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class DownloadEngineBehaviorTest {
  /** 保留真实 atomic 算法，仅在 COMPLETE 日志的最终 rename 前/后注入 I/O 故障。 */
  @Implements(value = AndroidBackupFileSystem.class, isInAndroidSdk = false)
  public static final class CompletionFiles {
    static int failures, attempts;
    static boolean afterPublish;
    private final HostFiles delegate = new HostFiles();
    private boolean armed;

    @Implementation
    protected BackupFileSystem.Node stat(File file) throws IOException {
      return delegate.stat(file);
    }

    @Implementation
    protected InputStream read(File file, BackupFileSystem.Node expected) throws IOException {
      return delegate.read(file, expected);
    }

    @Implementation
    protected OutputStream create(File file) throws IOException {
      OutputStream output = delegate.create(file);
      if (!file.getName().startsWith("task.json.tmp-")) return output;
      return new FilterOutputStream(output) {
        private final java.io.ByteArrayOutputStream written = new java.io.ByteArrayOutputStream();

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
          out.write(bytes, offset, length);
          written.write(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
          super.close();
          if (written.toString(StandardCharsets.UTF_8).contains("\"status\":\"COMPLETE\"")) {
            armed = failures != 0;
          }
        }
      };
    }

    @Implementation
    protected void directory(File file) throws IOException {
      delegate.directory(file);
    }

    @Implementation
    protected void move(File from, File to) throws IOException {
      if (to.getName().equals("task.json.previous") && armed && failures != 0 && !afterPublish)
        fail();
      delegate.move(from, to);
    }

    @Implementation
    protected void delete(File file) throws IOException {
      delegate.delete(file);
    }

    @Implementation
    protected void syncDirectory(File directory) throws IOException {
      if (armed && failures != 0 && afterPublish && new File(directory, "task.json").isFile())
        fail();
    }

    private void fail() throws IOException {
      attempts++;
      if (failures > 0) failures--;
      armed = failures != 0;
      throw new IOException("FILE_TEST_COMPLETION_SAVE");
    }
  }

  private static final byte[] BYTES = "真实队列回归内容".getBytes(StandardCharsets.UTF_8);
  private TransferBehaviorTestSupport fixture;
  private DownloadEngine engine;

  @Before
  public void prepare() throws Exception {
    fixture = new TransferBehaviorTestSupport();
    CompletionFiles.failures = 0;
    CompletionFiles.attempts = 0;
    CompletionFiles.afterPublish = false;
    engine = fixture.engine();
  }

  @After
  public void release() throws Exception {
    fixture.close();
  }

  private String enqueue(String name, String destination) throws Exception {
    String id =
        engine.enqueue(artifact("artifact-" + name.replace('.', '-'), name, BYTES), destination);
    fixture.partial(id, BYTES);
    return id;
  }

  private static String status(DownloadEngine engine, String id) throws Exception {
    return engine.snapshot(id).getString("status");
  }

  @Test
  public void oneDrainPublishesQueuedTasksOnceInAdmissionOrder() throws Exception {
    String first = enqueue("first.bin", "downloads");
    String second = enqueue("second.bin", "downloads");
    String third = enqueue("third.bin", "downloads");
    long ticket = engine.runQueued();
    assertEquals(ticket, engine.runQueued());
    assertEquals("重复 Service 唤起不能重复调度 drain", 1, queue(engine).size());
    assertTrue(engine.isBusy());
    queue(engine).runAll();
    idle();
    assertEquals(List.of("first.bin", "second.bin", "third.bin"), fixture.media.insertedNames);
    for (String id : List.of(first, second, third)) {
      assertEquals("COMPLETE", status(engine, id));
      assertFalse(engine.snapshot(id).getString("uri").isEmpty());
      assertFalse(fixture.partial(id).exists());
      assertEquals("COMPLETE", fixture.journal(id).getString("status"));
    }
    assertFalse(engine.isBusy());
    assertEquals(
        List.of(third, second, first),
        engine.state().getValue().tasks.stream().map(t -> t.id).toList());
    assertEquals("", engine.state().getValue().activeId);
    assertEquals("已有完整 partial 不得去请求运行环境", 0, Controller.readyReads);
  }

  @Test
  public void releaseRuntimeFailureCannotRollBackDurableCompletionOrDeletePublishedTarget()
      throws Exception {
    String id = enqueue("release-unavailable.bin", "downloads");
    engine.runQueued();
    queue(engine).runAll();
    Uri published = Uri.parse(engine.snapshot(id).getString("uri"));
    ((ManualExecutor) field(engine, "releaseWorker")).runAll();
    assertEquals("COMPLETE", status(engine, id));
    assertEquals("COMPLETE", fixture.journal(id).getString("status"));
    assertFalse(fixture.partial(id).exists());
    assertFalse(((java.util.Set<?>) field(engine, "pendingReleases")).isEmpty());
    assertArrayEquals(BYTES, fixture.media.require(published).bytes);
    engine.remove(id);
    assertArrayEquals(BYTES, fixture.media.require(published).bytes);
    assertTrue(fixture.media.deleted.isEmpty());
  }

  @Test
  public void restartPausesEveryInterruptedStageAndUsesActualPartialLengthWithoutStartingService()
      throws Exception {
    List<String> interrupted = List.of("DOWNLOADING", "VERIFYING", "PUBLISHING");
    for (int index = 0; index < interrupted.size(); index++) {
      String id = enqueue("restored-" + index + ".bin", "downloads");
      fixture.partial(id, new byte[index + 1]);
      JSONObject journal = fixture.journal(id);
      journal.put("status", interrupted.get(index)).put("downloaded", 999_999);
      Files.writeString(
          new File(fixture.partial(id).getParentFile(), "task.json").toPath(),
          journal.toString(),
          StandardCharsets.UTF_8);
    }
    assertEquals(3, fixture.startedServices().size());
    DownloadEngine restored = fixture.engine();
    idle();
    assertTrue("恢复不能自行重启下载服务", fixture.startedServices().isEmpty());
    assertEquals(0, queue(restored).size());
    assertFalse(restored.isBusy());
    assertEquals(3, restored.state().getValue().tasks.size());
    for (DownloadTask task : restored.state().getValue().tasks) {
      assertEquals(DownloadTask.Status.PAUSED, task.status);
      int original =
          Integer.parseInt(
              task.displayName.substring("restored-".length(), "restored-".length() + 1));
      assertEquals(original + 1, task.downloaded);
      assertTrue(task.message.contains("手动继续"));
      assertTrue(fixture.partial(task.id).exists());
    }
  }

  @Test
  public void pausedTasksRequireResumeAndCancelledTasksCannotBeResumed() throws Exception {
    String paused = enqueue("paused.bin", "downloads");
    String cancelled = enqueue("cancelled.bin", "downloads");
    engine.pause(paused);
    engine.cancel(cancelled);
    engine.runQueued();
    queue(engine).runAll();
    assertEquals("PAUSED", status(engine, paused));
    assertEquals("CANCELLED", status(engine, cancelled));
    assertTrue(fixture.media.insertedNames.isEmpty());
    assertThrows(IOException.class, () -> engine.resume(cancelled));
    engine.resume(paused);
    assertEquals("QUEUED", status(engine, paused));
    engine.runQueued();
    queue(engine).runAll();
    assertEquals("COMPLETE", status(engine, paused));
    assertEquals("CANCELLED", status(engine, cancelled));
    assertEquals(List.of("paused.bin"), fixture.media.insertedNames);
    assertTrue(fixture.partial(cancelled).exists());
  }

  @Test
  public void cancellationAfterReadbackDeletesOnlyPendingRowAndKeepsPrivateBytes()
      throws Exception {
    String id = enqueue("cancel-readback.bin", "downloads");
    Uri unrelated = fixture.media.document("unrelated.bin", BYTES);
    Documents.afterInputClose = uri -> engine.cancel(id);
    engine.runQueued();
    queue(engine).runAll();
    assertEquals("CANCELLED", status(engine, id));
    assertEquals("", engine.snapshot(id).getString("uri"));
    assertEquals(List.of(fixture.media.last), fixture.media.deleted);
    assertTrue(fixture.media.rows.containsKey(unrelated));
    assertArrayEquals(BYTES, Files.readAllBytes(fixture.partial(id).toPath()));
    assertFalse(fixture.media.events.contains("visible"));
    assertEquals("CANCELLED", fixture.journal(id).getString("status"));
    assertFalse(engine.isBusy());
  }

  @Test
  public void cancellationArrivingInsideCommitCannotUndoCompletedPublication() throws Exception {
    String id = enqueue("late-cancel.bin", "downloads");
    fixture.media.requiredGate = gate(engine);
    AtomicReference<Worker> cancellation = new AtomicReference<>();
    fixture.media.afterUpdate =
        () -> {
          CountDownLatch attempted = new CountDownLatch(1);
          cancellation.set(
              fixture.start(
                  () -> {
                    attempted.countDown();
                    engine.cancel(id);
                  }));
          try {
            await(attempted);
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
          }
        };
    engine.runQueued();
    queue(engine).runAll();
    cancellation.get().await();
    assertEquals("COMPLETE", status(engine, id));
    assertEquals("COMPLETE", fixture.journal(id).getString("status"));
    assertEquals(0, fixture.media.require(fixture.media.last).pending());
    assertTrue(fixture.media.deleted.isEmpty());
    assertFalse(fixture.partial(id).exists());
    assertFalse(engine.snapshot(id).getString("uri").isEmpty());
  }

  @Test
  public void staleServiceGenerationCannotPauseNewerAdmissionButCurrentOwnerCan() throws Exception {
    String first = enqueue("generation-one.bin", "downloads");
    long retired = engine.runQueued();
    String second = enqueue("generation-two.bin", "downloads");
    engine.serviceGone(retired, "旧 Service 已退休");
    engine.serviceGone(0, "无所有权不能暂停");
    assertEquals("QUEUED", status(engine, first));
    assertEquals("QUEUED", status(engine, second));
    long current = engine.runQueued();
    assertTrue(current > retired);
    assertEquals(1, queue(engine).size());
    engine.serviceGone(current, "当前 Service 已中断");
    assertEquals("PAUSED", status(engine, first));
    assertEquals("PAUSED", status(engine, second));
    assertEquals("当前 Service 已中断", engine.snapshot(second).getString("message"));
    queue(engine).runAll();
    assertTrue(fixture.media.insertedNames.isEmpty());
    assertFalse(engine.isBusy());
  }

  @Test
  public void saveAsWaitsForExplicitContentTargetAndRejectsTargetReplay() throws Exception {
    String id = enqueue("choose-target.bin", "saveas");
    engine.runQueued();
    queue(engine).runAll();
    fixture.startedServices();
    assertEquals("WAITING_DESTINATION", status(engine, id));
    assertEquals(BYTES.length, engine.snapshot(id).getLong("downloaded"));
    assertFalse(engine.isBusy());
    engine.resume(id);
    assertEquals("WAITING_DESTINATION", status(engine, id));
    assertTrue(fixture.startedServices().isEmpty());
    assertThrows(
        IOException.class, () -> engine.provideSaveTarget(id, Uri.parse("file:///tmp/unsafe")));
    Uri target = fixture.media.document("chosen-name.bin", new byte[0]);
    engine.provideSaveTarget(id, target);
    assertEquals("QUEUED", status(engine, id));
    assertThrows(IOException.class, () -> engine.provideSaveTarget(id, target));
    engine.runQueued();
    queue(engine).runAll();
    assertEquals("COMPLETE", status(engine, id));
    assertEquals(target.toString(), engine.snapshot(id).getString("uri"));
    assertArrayEquals(BYTES, fixture.media.require(target).bytes);
    assertTrue(fixture.media.insertedNames.isEmpty());
    assertFalse(fixture.media.events.contains("visible"));
  }

  @Test
  public void digestFailureRetainsPartialAndRunsAgainOnlyAfterExplicitResume() throws Exception {
    String id = enqueue("retry-digest.bin", "downloads");
    byte[] changed = BYTES.clone();
    changed[0] ^= 1;
    fixture.partial(id, changed);
    engine.runQueued();
    queue(engine).runAll();
    assertEquals("FAILED", status(engine, id));
    assertEquals("FILE_SHA256_MISMATCH", engine.snapshot(id).getString("message"));
    assertArrayEquals(changed, Files.readAllBytes(fixture.partial(id).toPath()));
    engine.runQueued();
    queue(engine).runAll();
    assertEquals("FAILED", status(engine, id));
    assertTrue(fixture.media.insertedNames.isEmpty());
    fixture.partial(id, BYTES);
    engine.resume(id);
    engine.runQueued();
    queue(engine).runAll();
    assertEquals("COMPLETE", status(engine, id));
    assertEquals(List.of("retry-digest.bin"), fixture.media.insertedNames);
  }

  @Test
  public void persistentCompletionSaveFailureKeepsMediaRecoveryEvidenceWithoutBusyRetry()
      throws Exception {
    completionSaveFailure("downloads", false, true);
  }

  @Test
  public void singleCompletionSaveFailureRecoversSameMediaAfterRestart() throws Exception {
    completionSaveFailure("downloads", false, false);
  }

  @Test
  public void completionSaveThrowingAfterRenameDoesNotAssumeItWasNotCommitted() throws Exception {
    completionSaveFailure("downloads", true, true);
  }

  @Test
  public void singleFailureAfterRenameRetainsRecoveryEvidence() throws Exception {
    completionSaveFailure("downloads", true, false);
  }

  @Test
  public void persistentCompletionSaveFailureRecoversSameSaveAsWithoutReopeningOutput()
      throws Exception {
    completionSaveFailure("saveas", false, true);
  }

  @Test
  public void singleCompletionSaveFailureRecoversSameSaveAsWithoutReopeningOutput()
      throws Exception {
    completionSaveFailure("saveas", false, false);
  }

  @Test
  public void unavailablePublishedUriAfterSaveFailureNeverFallsThroughToNewPublication()
      throws Exception {
    String id = enqueue("temporarily-unavailable.bin", "downloads");
    CompletionFiles.failures = -1;
    engine.runQueued();
    queue(engine).runAll();
    Uri published = Uri.parse(engine.snapshot(id).getString("uri"));
    Row original = fixture.media.rows.remove(published);
    CompletionFiles.failures = 0;
    DownloadEngine restored = fixture.engine();
    for (int attempt = 0; attempt < 2; attempt++) {
      restored.resume(id);
      restored.runQueued();
      queue(restored).runAll();
      assertEquals("FAILED", status(restored, id));
      assertEquals("FILE_PUBLICATION_UNKNOWN", restored.snapshot(id).getString("message"));
      assertEquals(published.toString(), fixture.journal(id).getString("pendingUri"));
    }
    fixture.media.rows.put(published, original);
    restored.resume(id);
    restored.runQueued();
    queue(restored).runAll();
    assertEquals("COMPLETE", status(restored, id));
    assertEquals(published.toString(), restored.snapshot(id).getString("uri"));
    assertEquals(List.of("temporarily-unavailable.bin"), fixture.media.insertedNames);
    assertEquals(1, java.util.Collections.frequency(fixture.media.events, "output-open"));
    assertTrue(fixture.media.deleted.isEmpty());
    assertArrayEquals(BYTES, original.bytes);
  }

  private void completionSaveFailure(String destination, boolean afterRename, boolean persistent)
      throws Exception {
    String id = enqueue("completion-fault.bin", destination);
    if (destination.equals("saveas")) {
      engine.runQueued();
      queue(engine).runAll();
      engine.provideSaveTarget(id, fixture.media.document("chosen.bin", new byte[0]));
    }
    CompletionFiles.failures = persistent ? -1 : 1;
    CompletionFiles.afterPublish = afterRename;
    engine.runQueued();
    queue(engine).runAll();
    idle();
    Uri published = Uri.parse(engine.snapshot(id).getString("uri"));
    assertEquals("FAILED", status(engine, id));
    assertEquals(DownloadTask.Status.FAILED, engine.state().getValue().tasks.get(0).status);
    assertFalse(engine.isBusy());
    assertTrue(CompletionFiles.attempts >= 1);
    assertArrayEquals(BYTES, fixture.media.require(published).bytes);
    assertEquals(0, fixture.media.require(published).pending());
    assertArrayEquals(BYTES, Files.readAllBytes(fixture.partial(id).toPath()));
    assertTrue(fixture.media.deleted.isEmpty());
    Object record = ((java.util.Map<?, ?>) field(engine, "records")).get(id);
    assertEquals(published.toString(), field(record, "pendingUri"));
    assertEquals(destination.equals("saveas") ? "saf" : "media", field(record, "pendingKind"));
    assertEquals(
        destination.equals("saveas") ? published.toString() : "", field(record, "targetUri"));
    assertTrue(((java.util.Set<?>) field(engine, "pendingReleases")).isEmpty());
    JSONObject saved = fixture.journal(id);
    assertEquals(
        persistent ? (afterRename ? "COMPLETE" : "PUBLISHING") : "FAILED",
        saved.getString("status"));
    if (!saved.getString("status").equals("COMPLETE")) {
      assertEquals(published.toString(), saved.getString("pendingUri"));
    }
    int outputs = java.util.Collections.frequency(fixture.media.events, "output-open");
    int inserts = fixture.media.insertedNames.size();
    int attempts = CompletionFiles.attempts;
    engine.runQueued();
    queue(engine).runAll();
    assertEquals("没有 QUEUED 就不得忙重试保存", attempts, CompletionFiles.attempts);
    CompletionFiles.failures = 0;
    CompletionFiles.afterPublish = false;
    fixture.startedServices();
    DownloadEngine restored = fixture.engine();
    assertTrue("重启不能自动重发", fixture.startedServices().isEmpty());
    assertEquals(0, queue(restored).size());
    restored.resume(id);
    restored.runQueued();
    queue(restored).runAll();
    assertEquals("COMPLETE", status(restored, id));
    assertEquals(published.toString(), restored.snapshot(id).getString("uri"));
    assertEquals("COMPLETE", fixture.journal(id).getString("status"));
    assertEquals(inserts, fixture.media.insertedNames.size());
    assertEquals(outputs, java.util.Collections.frequency(fixture.media.events, "output-open"));
    assertArrayEquals(BYTES, fixture.media.require(published).bytes);
    assertTrue(fixture.media.deleted.isEmpty());
    assertTrue(
        ((java.util.Set<?>) field(restored, "pendingReleases"))
            .contains(restored.snapshot(id).getJSONObject("artifact").getString("id")));
    assertFalse(restored.isBusy());
    // 已提交后崩溃的 COMPLETE 日志可能保留 partial；remove 只清私有字节与日志。
    restored.remove(id);
    assertFalse(fixture.partial(id).exists());
    assertArrayEquals(BYTES, fixture.media.require(published).bytes);
    assertTrue(fixture.media.deleted.isEmpty());
  }

  private void interruptedPublication(String id, Uri target, String kind) throws Exception {
    JSONObject journal = fixture.journal(id);
    journal
        .put("status", "PUBLISHING")
        .put("pendingUri", target.toString())
        .put("pendingKind", kind)
        .put("targetUri", kind.equals("saf") ? target.toString() : "");
    Files.writeString(
        new File(fixture.partial(id).getParentFile(), "task.json").toPath(),
        journal.toString(),
        StandardCharsets.UTF_8);
  }

  @Test
  public void restartVerifiesAlreadyVisibleMediaBeforeCompletingWithoutPublishingAgain()
      throws Exception {
    String id = enqueue("visible-before-crash.bin", "downloads");
    Uri visible = fixture.media.document("saved-before-crash.bin", BYTES);
    fixture
        .media
        .require(visible)
        .values
        .put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
    interruptedPublication(id, visible, "media");
    DownloadEngine restored = fixture.engine();
    assertEquals("PAUSED", status(restored, id));
    assertTrue(fixture.media.events.isEmpty());
    restored.resume(id);
    restored.runQueued();
    queue(restored).runAll();
    assertEquals("COMPLETE", status(restored, id));
    assertEquals(visible.toString(), restored.snapshot(id).getString("uri"));
    assertEquals("saved-before-crash.bin", restored.snapshot(id).getString("displayName"));
    assertTrue(fixture.media.insertedNames.isEmpty());
    assertTrue(fixture.media.deleted.isEmpty());
    assertFalse(fixture.partial(id).exists());
    assertEquals("COMPLETE", fixture.journal(id).getString("status"));
  }

  @Test
  public void corruptAlreadyVisibleMediaRemainsUnknownAndNeverDeletesUserVisibleBytes()
      throws Exception {
    String id = enqueue("corrupt-visible.bin", "downloads");
    Uri visible = fixture.media.document("corrupt-destination.bin", BYTES);
    Row row = fixture.media.require(visible);
    row.values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
    row.readback = BYTES.clone();
    row.readback[0] ^= 1;
    interruptedPublication(id, visible, "media");
    DownloadEngine restored = fixture.engine();
    restored.resume(id);
    restored.runQueued();
    queue(restored).runAll();
    assertEquals("FAILED", status(restored, id));
    assertEquals("FILE_SHA256_MISMATCH", restored.snapshot(id).getString("message"));
    assertTrue(fixture.media.rows.containsKey(visible));
    assertTrue(fixture.media.deleted.isEmpty());
    assertTrue(fixture.media.insertedNames.isEmpty());
    assertArrayEquals(BYTES, Files.readAllBytes(fixture.partial(id).toPath()));
    assertEquals(visible.toString(), fixture.journal(id).getString("pendingUri"));
    restored.resume(id);
    restored.runQueued();
    queue(restored).runAll();
    assertEquals("FAILED", status(restored, id));
    assertTrue("再次手动继续仍不能重发未知的已公开目标", fixture.media.insertedNames.isEmpty());
    assertEquals(visible.toString(), fixture.journal(id).getString("pendingUri"));
  }

  @Test
  public void uncertainSaveAsAfterRestartRequiresNewTargetWithoutReplayingTheOldDocument()
      throws Exception {
    String id = enqueue("uncertain-saveas.bin", "saveas");
    byte[] uncertain = new byte[] {4, 2};
    Uri target = fixture.media.document("possibly-partial.bin", uncertain);
    interruptedPublication(id, target, "saf");
    DownloadEngine restored = fixture.engine();
    assertEquals("PAUSED", status(restored, id));
    restored.resume(id);
    restored.runQueued();
    queue(restored).runAll();
    assertEquals("WAITING_DESTINATION", status(restored, id));
    assertArrayEquals(uncertain, fixture.media.require(target).bytes);
    assertTrue(fixture.media.deleted.isEmpty());
    assertFalse(fixture.media.events.contains("output-open"));
    assertTrue(fixture.partial(id).exists());
  }

  @Test
  public void missingPrimaryJournalUsesPreviousWhileMalformedJournalsAndBytesRemainUntouched()
      throws Exception {
    String id = enqueue("previous-journal.bin", "downloads");
    File journal = new File(fixture.partial(id).getParentFile(), "task.json");
    Files.move(journal.toPath(), new File(journal.getParentFile(), "task.json.previous").toPath());
    File unknown =
        new File(journal.getParentFile().getParentFile(), "99999999-9999-9999-9999-999999999999");
    assertTrue(unknown.mkdir());
    File bad = new File(unknown, "task.json");
    Files.writeString(bad.toPath(), "{invalid", StandardCharsets.UTF_8);
    File preserved = new File(unknown, "partial");
    Files.write(preserved.toPath(), new byte[] {9});
    DownloadEngine restored = fixture.engine();
    assertEquals("PAUSED", status(restored, id));
    assertEquals(1, restored.snapshot(null).getJSONArray("tasks").length());
    assertEquals("{invalid", Files.readString(bad.toPath()));
    assertArrayEquals(new byte[] {9}, Files.readAllBytes(preserved.toPath()));
    assertTrue(fixture.partial(id).exists());
  }

  @Test
  public void singletonGetStillUsesTheDownloadEngineClassMonitorAndIsReentrant() throws Exception {
    fixture.singleton(engine);
    CountDownLatch entered = new CountDownLatch(1), completed = new CountDownLatch(1);
    AtomicReference<DownloadEngine> result = new AtomicReference<>();
    Worker caller;
    synchronized (DownloadEngine.class) {
      assertSame(engine, DownloadEngine.get(null));
      caller =
          fixture.start(
              () -> {
                entered.countDown();
                result.set(DownloadEngine.get(null));
                completed.countDown();
              });
      await(entered);
      assertFalse(completed.await(100, java.util.concurrent.TimeUnit.MILLISECONDS));
    }
    caller.await();
    assertSame(engine, result.get());
  }
}
