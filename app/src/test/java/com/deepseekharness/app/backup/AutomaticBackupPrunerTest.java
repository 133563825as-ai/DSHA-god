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

  @Test
  public void changedDigestBlocksRemovalOfAnotherHealthyOldCopy() throws Exception {
    File operations = temp.newFolder(HostOperationArchive.ROOT);
    List<VerifiedBackupCopy> copies = automaticCopies(operations, 5);
    Collections.reverse(copies);
    VerifiedBackupCopy healthyOld = copies.get(3), badOld = copies.get(4);
    Path artifact = badOld.artifact.toPath();
    byte[] original = Files.readAllBytes(artifact), changed = original.clone();
    changed[changed.length - 1] ^= 1;
    Files.write(artifact, changed);

    IOException failure =
        assertThrows(
            IOException.class,
            () -> AutomaticBackupPruner.prune(fs, copies, new BackupControl(null)));
    assertEquals("VERIFIED_COPY_CHANGED", failure.getMessage());
    assertArrayEquals(changed, Files.readAllBytes(artifact));
    for (VerifiedBackupCopy copy : copies)
      if (copy != badOld) copy.verify(fs, new BackupControl(null));

    // 仅恢复原字节，确认另一份本来就满足轮换条件。
    Files.write(artifact, original);
    AutomaticBackupPruner.prune(fs, copies, new BackupControl(null));
    assertEquals("MISSING", fs.stat(healthyOld.artifact.getParentFile()).type);
    assertEquals("MISSING", fs.stat(badOld.artifact.getParentFile()).type);
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
