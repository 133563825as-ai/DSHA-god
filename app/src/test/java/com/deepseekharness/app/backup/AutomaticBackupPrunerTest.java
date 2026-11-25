package com.deepseekharness.app.backup;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class AutomaticBackupPrunerTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();

  private File copy(File parent, boolean automatic, String quality, long created, boolean extra)
      throws Exception {
    String id = UUID.randomUUID().toString();
    File directory = new File(parent, id);
    fs.directory(directory);
    File artifact = new File(directory, "portable.dshbak");
    try (OutputStream out = fs.create(artifact)) {
      PortableBackupCrypto.encrypt(
          new ByteArrayInputStream((id + " contents").getBytes()),
          out,
          "synthetic-password".toCharArray(),
          new BackupControl(null));
    }
    String sha;
    try (InputStream in = fs.read(artifact, fs.stat(artifact))) {
      sha = BackupArchive.digest(in, new BackupControl(null));
    }
    fs.atomic(
        directory,
        "verified.json",
        BackupJson.write(
            Map.of(
                "encryptedSha256",
                sha,
                "encryptedBytes",
                artifact.length(),
                "entries",
                1L,
                "createdAt",
                created,
                "integrity",
                quality,
                "requestedScope",
                "application",
                "automatic",
                automatic),
            BackupLimits.MANIFEST));
    fs.atomic(
        directory,
        "operation.json",
        BackupJson.write(
            Map.of(
                "version",
                1L,
                "id",
                id,
                "stage",
                "FINISHED",
                "busy",
                false,
                "updatedAt",
                created,
                "result",
                "COMPLETE",
                "error",
                "",
                "artifact",
                "portable.dshbak"),
            16384));
    if (extra) fs.atomic(directory, "manual-note", "retain me".getBytes());
    return directory;
  }

  @Test
  public void onlyOlderCompleteAutomaticCopiesAreRotated() throws Exception {
    File files = temp.newFolder(), operations = HostOperationArchive.root(files);
    fs.directory(operations);
    File history = new File(operations, HostOperationArchive.COMPLETED);
    fs.directory(history);
    List<VerifiedBackupCopy> copies = new ArrayList<>();
    File[] automatic = new File[5];
    for (int i = 0; i < 5; i++) {
      automatic[i] = copy(history, true, "QUIESCENT", i + 1, i == 0);
      copies.add(VerifiedBackupCopy.inspect(fs, operations, automatic[i].getName()));
    }
    File manual = copy(history, false, "QUIESCENT", 0, false),
        partial = copy(history, true, "PARTIAL", -1, false);
    copies.add(VerifiedBackupCopy.inspect(fs, operations, manual.getName()));
    copies.add(VerifiedBackupCopy.inspect(fs, operations, partial.getName()));
    AutomaticBackupPruner.prune(fs, copies, new BackupControl(null));
    assertTrue(
        "unrecognized extra payload remains retained",
        fs.stat(automatic[0]).type.equals("DIRECTORY"));
    assertEquals("older ordinary automatic copy rotates", "MISSING", fs.stat(automatic[1]).type);
    for (int i = 2; i < 5; i++)
      assertEquals("keep newest three", "DIRECTORY", fs.stat(automatic[i]).type);
    assertEquals("manual copy remains", "DIRECTORY", fs.stat(manual).type);
    assertEquals("partial automatic copy remains", "DIRECTORY", fs.stat(partial).type);
  }

  private List<VerifiedBackupCopy> automaticCopies(File operations, int count) throws Exception {
    List<VerifiedBackupCopy> copies = new ArrayList<>();
    for (int created = 1; created <= count; created++) {
      File directory = copy(operations, true, "QUIESCENT", created, false);
      copies.add(VerifiedBackupCopy.inspect(fs, operations, directory.getName()));
    }
    return copies;
  }

  private byte[] changedBytes(VerifiedBackupCopy copy) throws IOException {
    byte[] changed = Files.readAllBytes(copy.artifact.toPath());
    changed[changed.length - 1] ^= 1;
    return changed;
  }

  private void assertHealthy(File operations, VerifiedBackupCopy copy) throws IOException {
    VerifiedBackupCopy current = VerifiedBackupCopy.inspect(fs, operations, copy.id);
    assertEquals(copy.sha256, current.sha256);
    current.verify(fs, new BackupControl(null));
    assertEquals(
        "FILE", fs.stat(new File(current.artifact.getParentFile(), "operation.json")).type);
  }

  @Test
  public void changedDigestRetainsDamagedCopyAndRotatesHealthyOldCopy() throws Exception {
    File operations = temp.newFolder(HostOperationArchive.ROOT);
    List<VerifiedBackupCopy> copies = automaticCopies(operations, 5);
    Collections.reverse(copies);
    VerifiedBackupCopy healthyOld = copies.get(3), badOld = copies.get(4);
    Path artifact = badOld.artifact.toPath();
    Path journal = artifact.resolveSibling("operation.json"),
        proof = artifact.resolveSibling("verified.json");
    byte[] original = Files.readAllBytes(artifact), changed = changedBytes(badOld);
    byte[] journalBytes = Files.readAllBytes(journal), proofBytes = Files.readAllBytes(proof);
    Files.write(artifact, changed);

    assertEquals(1, AutomaticBackupPruner.prune(fs, copies, new BackupControl(null)));
    assertArrayEquals(changed, Files.readAllBytes(artifact));
    assertArrayEquals(journalBytes, Files.readAllBytes(journal));
    assertArrayEquals(proofBytes, Files.readAllBytes(proof));
    assertEquals("MISSING", fs.stat(healthyOld.artifact.getParentFile()).type);
    for (int i = 0; i < 3; i++) assertHealthy(operations, copies.get(i));

    // 只修复原字节；同一份原本太旧的副本现在能正常轮换。
    Files.write(artifact, original);
    copies.remove(healthyOld);
    assertEquals(0, AutomaticBackupPruner.prune(fs, copies, new BackupControl(null)));
    assertEquals("MISSING", fs.stat(badOld.artifact.getParentFile()).type);
    for (int i = 0; i < 3; i++) assertHealthy(operations, copies.get(i));
  }

  @Test
  public void damagedNewestDoesNotDisplaceThirdHealthyCopy() throws Exception {
    File operations = temp.newFolder(HostOperationArchive.ROOT);
    List<VerifiedBackupCopy> copies = automaticCopies(operations, 5);
    VerifiedBackupCopy newest = copies.get(4);
    byte[] changed = changedBytes(newest);
    Files.write(newest.artifact.toPath(), changed);

    assertEquals(1, AutomaticBackupPruner.prune(fs, copies, new BackupControl(null)));
    assertArrayEquals(changed, Files.readAllBytes(newest.artifact.toPath()));
    assertEquals("MISSING", fs.stat(copies.get(0).artifact.getParentFile()).type);
    for (int i = 1; i <= 3; i++) assertHealthy(operations, copies.get(i));
  }

  @Test
  public void insufficientHealthyCopiesRemainIntact() throws Exception {
    File operations = temp.newFolder(HostOperationArchive.ROOT);
    List<VerifiedBackupCopy> copies = automaticCopies(operations, 4);
    byte[] badThird = changedBytes(copies.get(2)), badFourth = changedBytes(copies.get(3));
    Files.write(copies.get(2).artifact.toPath(), badThird);
    Files.write(copies.get(3).artifact.toPath(), badFourth);

    assertEquals(2, AutomaticBackupPruner.prune(fs, copies, new BackupControl(null)));
    assertHealthy(operations, copies.get(0));
    assertHealthy(operations, copies.get(1));
    assertArrayEquals(badThird, Files.readAllBytes(copies.get(2).artifact.toPath()));
    assertArrayEquals(badFourth, Files.readAllBytes(copies.get(3).artifact.toPath()));
  }

  @Test
  public void unconfirmedJournalDoesNotCountTowardReserve() throws Exception {
    File operations = temp.newFolder(HostOperationArchive.ROOT);
    List<VerifiedBackupCopy> copies = automaticCopies(operations, 4);
    File newest = copies.get(3).artifact.getParentFile();
    for (Object busy : new Object[] {true, null, "false"}) {
      changeRecord(newest, "operation.json", "busy", busy);
      assertEquals(0, AutomaticBackupPruner.prune(fs, copies, new BackupControl(null)));
      for (VerifiedBackupCopy copy : copies) assertHealthy(operations, copy);
    }
    changeRecord(newest, "operation.json", "busy", false);
    for (String stage : new String[] {"VERIFYING", null}) {
      changeRecord(newest, "operation.json", "stage", stage);
      assertEquals(0, AutomaticBackupPruner.prune(fs, copies, new BackupControl(null)));
      for (VerifiedBackupCopy copy : copies) assertHealthy(operations, copy);
    }
  }

  @Test
  public void discoveryIoWithMismatchMessageAbortsBeforeDeletion() throws Exception {
    File operations = temp.newFolder(HostOperationArchive.ROOT);
    List<VerifiedBackupCopy> copies = automaticCopies(operations, 5);
    IOException fault = new IOException("VERIFIED_COPY_CHANGED");
    JvmBackupFileSystem failing =
        new JvmBackupFileSystem() {
          @Override
          public InputStream read(File file, Node expected) throws IOException {
            if (file.equals(copies.get(4).artifact)) throw fault;
            return super.read(file, expected);
          }
        };
    assertSame(
        fault,
        assertThrows(
            IOException.class,
            () -> AutomaticBackupPruner.prune(failing, copies, new BackupControl(null))));
    for (VerifiedBackupCopy copy : copies) assertHealthy(operations, copy);
  }

  private interface OnClose {
    void run() throws IOException;
  }

  // 在读到 EOF 且底层流成功关闭后注入故障，不依赖文件头与摘要分别打开几次。
  private static InputStream afterFullRead(InputStream input, OnClose action) {
    return new FilterInputStream(input) {
      boolean eof;

      @Override
      public int read() throws IOException {
        int n = super.read();
        if (n == -1) eof = true;
        return n;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n == -1) eof = true;
        return n;
      }

      @Override
      public void close() throws IOException {
        super.close();
        if (eof) action.run();
      }
    };
  }

  @Test
  public void closeFailureOnMismatchedDigestAbortsBeforeDeletion() throws Exception {
    File operations = temp.newFolder(HostOperationArchive.ROOT);
    List<VerifiedBackupCopy> copies = automaticCopies(operations, 5);
    VerifiedBackupCopy damaged = copies.get(4);
    byte[] changed = changedBytes(damaged);
    Files.write(damaged.artifact.toPath(), changed);
    IOException fault = new IOException("VERIFIED_COPY_CHANGED");
    JvmBackupFileSystem failing =
        new JvmBackupFileSystem() {
          @Override
          public InputStream read(File file, Node expected) throws IOException {
            InputStream in = super.read(file, expected);
            if (!file.equals(damaged.artifact)) return in;
            return afterFullRead(
                in,
                () -> {
                  throw fault;
                });
          }
        };
    assertSame(
        fault,
        assertThrows(
            IOException.class,
            () -> AutomaticBackupPruner.prune(failing, copies, new BackupControl(null))));
    assertArrayEquals(changed, Files.readAllBytes(damaged.artifact.toPath()));
    for (VerifiedBackupCopy copy : copies) if (copy != damaged) assertHealthy(operations, copy);
  }

  private enum RecheckFault {
    DIGEST,
    IO,
    CANCEL
  }

  @Test
  public void preDeleteRecheckFailurePreservesCandidateAndRemainingCopies() throws Exception {
    File operations = temp.newFolder(HostOperationArchive.ROOT);
    List<VerifiedBackupCopy> copies = automaticCopies(operations, 5);
    Set<File> old = Set.of(copies.get(0).artifact, copies.get(1).artifact);
    for (RecheckFault mode : RecheckFault.values()) {
      BackupControl control = new BackupControl(null);
      IOException fault = new IOException("VERIFIED_COPY_CHANGED");
      Set<File> discovered = new HashSet<>();
      var failing =
          new JvmBackupFileSystem() {
            boolean injected;

            @Override
            public InputStream read(File file, Node expected) throws IOException {
              if (!old.contains(file)) return super.read(file, expected);
              if (!discovered.contains(file))
                return afterFullRead(super.read(file, expected), () -> discovered.add(file));
              injected = true;
              if (mode == RecheckFault.IO) throw fault;
              InputStream in = super.read(file, expected);
              if (mode == RecheckFault.CANCEL) return afterFullRead(in, control::cancel);
              try (in) {
                byte[] changed = in.readAllBytes();
                changed[changed.length - 1] ^= 1;
                return new ByteArrayInputStream(changed);
              }
            }
          };
      IOException failure =
          assertThrows(
              IOException.class, () -> AutomaticBackupPruner.prune(failing, copies, control));
      assertTrue("must reach final recheck: " + mode, failing.injected);
      if (mode == RecheckFault.IO) assertSame(fault, failure);
      else if (mode == RecheckFault.CANCEL) assertTrue(failure instanceof InterruptedIOException);
      else assertEquals("VERIFIED_COPY_CHANGED", failure.getMessage());
      for (VerifiedBackupCopy copy : copies) assertHealthy(operations, copy);
    }
  }

  @Test
  public void cancellationAfterOneRemovalLeavesAllOtherCopiesIntact() throws Exception {
    File operations = temp.newFolder(HostOperationArchive.ROOT);
    List<VerifiedBackupCopy> copies = automaticCopies(operations, 5);
    BackupControl control = new BackupControl(null);
    JvmBackupFileSystem cancelAfterRemoval =
        new JvmBackupFileSystem() {
          @Override
          public void delete(File file) throws IOException {
            super.delete(file);
            // 在一份副本目录真正删除后取消，而非在删除其中某个文件时取消。
            if (operations.equals(file.getParentFile())) control.cancel();
          }
        };

    assertThrows(
        InterruptedIOException.class,
        () -> AutomaticBackupPruner.prune(cancelAfterRemoval, copies, control));
    assertTrue(control.isCancelled());
    assertEquals(4, fs.list(operations).size());
    for (VerifiedBackupCopy copy : copies) {
      if (fs.stat(copy.artifact.getParentFile()).type.equals("MISSING")) {
        assertTrue("only an old copy was removed", copy.created <= 2);
      } else {
        VerifiedBackupCopy.inspect(fs, operations, copy.id).verify(fs, new BackupControl(null));
        assertEquals(
            "FILE", fs.stat(new File(copy.artifact.getParentFile(), "operation.json")).type);
      }
    }
  }

  private void changeRecord(File directory, String name, String field, Object value)
      throws IOException {
    Map<String, Object> record = BackupJson.read(fs.small(fs.child(directory, name), 16384), 16384);
    if (value == null) record.remove(field);
    else record.put(field, value);
    fs.atomic(directory, name, BackupJson.write(record, 16384));
  }

  @Test
  public void busyUnfinishedAndUnknownOriginCopiesSurviveOrdinaryRotation() throws Exception {
    File operations = temp.newFolder(HostOperationArchive.ROOT);
    List<VerifiedBackupCopy> copies = automaticCopies(operations, 6);
    File busy = copies.get(0).artifact.getParentFile(),
        unfinished = copies.get(1).artifact.getParentFile();
    changeRecord(busy, "operation.json", "busy", true);
    changeRecord(unfinished, "operation.json", "stage", "VERIFYING");
    File unknown = copy(operations, true, "QUIESCENT", 0, false);
    changeRecord(unknown, "verified.json", "automatic", null);
    copies.add(VerifiedBackupCopy.inspect(fs, operations, unknown.getName()));

    AutomaticBackupPruner.prune(fs, copies, new BackupControl(null));
    VerifiedBackupCopy ordinaryOld = copies.get(2);
    assertEquals("MISSING", fs.stat(ordinaryOld.artifact.getParentFile()).type);
    for (VerifiedBackupCopy copy : copies)
      if (copy != ordinaryOld)
        VerifiedBackupCopy.inspect(fs, operations, copy.id).verify(fs, new BackupControl(null));
  }
}
