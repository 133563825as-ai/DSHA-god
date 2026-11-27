package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import org.junit.Test;

/** 所有目录和字节均在内存；没有可工作的写、移动、删除或外部来源入口。 */
public class BackupCopyPreflightTest {
  private static final class Memory extends JvmBackupFileSystem {
    final File files = new File("synthetic-private").getAbsoluteFile();
    final File operations = new File(files, HostOperationArchive.ROOT);
    final File history = new File(operations, HostOperationArchive.COMPLETED);
    final Map<File, Node> nodes = new HashMap<>();
    final Map<File, byte[]> bytes = new HashMap<>();
    final List<File> copies = new ArrayList<>();
    final Set<File> hashed = new HashSet<>();
    final BackupControl control = new BackupControl(null);
    Runnable onArtifact = () -> {};
    int next, artifactReadBytes;
    File grows, closeFailure;

    Memory() throws Exception {
      dir(files);
      dir(operations);
      dir(history);
      json(
          new File(files, "host-backup-completed-v1.json"),
          Map.of("version", 1L, "filesKey", stat(files).key, "historyKey", stat(history).key));
      for (int i = 0; i < 4; i++) {
        String id = String.format(Locale.ROOT, "00000000-0000-0000-0000-%012d", i);
        File d = new File(i % 2 == 0 ? operations : history, id);
        copies.add(d);
        dir(d);
        byte[] payload = Arrays.copyOf(PortableBackupCrypto.MAGIC, 128);
        payload[127] = (byte) i;
        put(new File(d, "portable.dshbak"), payload);
        Map<String, Object> proof =
            new HashMap<>(
                Map.of(
                    "encryptedSha256",
                    BackupArchive.hex(BackupArchive.sha().digest(payload)),
                    "entries",
                    1L,
                    "encryptedBytes",
                    128L,
                    "integrity",
                    "QUIESCENT",
                    "automatic",
                    true,
                    "operation",
                    "EXPORT",
                    "sensitivePolicy",
                    "PASSWORD_ENCRYPTED",
                    "requestedScope",
                    "application",
                    "roots",
                    List.of(Map.of("id", "projects", "scope", "projects", "external", false))));
        proof.put("plugins", Map.of());
        proof.put("formatVersion", 5L);
        proof.put("createdAt", 1L);
        json(new File(d, "verified.json"), proof);
        json(
            new File(d, "operation.json"),
            Map.of(
                "id",
                id,
                "version",
                1L,
                "busy",
                false,
                "stage",
                "FINISHED",
                "artifact",
                "portable.dshbak",
                "bytes",
                128L,
                "entries",
                1L,
                "error",
                "",
                "result",
                "COMPLETE",
                "updatedAt",
                1L));
      }
    }

    void dir(File f) {
      nodes.put(f, new Node("DIRECTORY", "key" + ++next, 0, 1, 1, 0700));
    }

    void put(File f, byte[] b) {
      nodes.put(f, new Node("FILE", "key" + ++next, b.length, 1, 1, 0600));
      bytes.put(f, b);
    }

    void json(File f, Map<String, ?> m) throws IOException {
      put(f, BackupJson.write(m, 16384));
    }

    File member(int i, String name) {
      return new File(copies.get(i), name);
    }

    VerifiedBackupCopy selected() throws IOException {
      return VerifiedBackupCopy.inspect(this, operations, copies.get(0).getName());
    }

    BackupCopyPreflight.Result inspect() throws IOException {
      return inspect(selected());
    }

    BackupCopyPreflight.Result inspect(VerifiedBackupCopy s) throws IOException {
      return BackupCopyPreflight.inspect(this, operations, s, control);
    }

    void field(File f, String key, Object value) throws IOException {
      var record = BackupJson.read(bytes.get(f), 16384);
      record.put(key, value);
      json(f, record);
    }

    @Override
    public Node stat(File f) {
      return nodes.getOrDefault(f, new Node("MISSING", "", 0, 0, 0, 0));
    }

    @Override
    public List<String> listBounded(File d, int limit, BackupControl control) throws IOException {
      List<String> names = new ArrayList<>();
      for (File f : nodes.keySet())
        if (d.equals(f.getParentFile())) {
          control.check();
          if (names.size() == limit) throw new IOException("ENTRY_LIMIT");
          names.add(f.getName());
        }
      return names;
    }

    @Override
    public InputStream read(File f, Node n) throws IOException {
      if (!n.same(stat(f)) || !"FILE".equals(n.type)) throw new IOException("bad read");
      byte[] data = bytes.get(f);
      if (!f.getName().equals("portable.dshbak")) return new ByteArrayInputStream(data);
      hashed.add(f);
      onArtifact.run();
      return new ByteArrayInputStream(f.equals(grows) ? Arrays.copyOf(data, 65536) : data) {
        @Override
        public int read(byte[] b, int off, int len) {
          int count = super.read(b, off, len);
          if (count > 0) artifactReadBytes += count;
          return count;
        }

        @Override
        public void close() throws IOException {
          if (f.equals(closeFailure)) throw new IOException("injected close failure");
        }
      };
    }

    @Override
    public List<String> list(File f) {
      throw new AssertionError("unbounded list");
    }

    @Override
    public OutputStream create(File f) {
      throw new AssertionError();
    }

    @Override
    public void directory(File f) {
      throw new AssertionError();
    }

    @Override
    public void delete(File f) {
      throw new AssertionError();
    }

    @Override
    public void move(File f, File t) {
      throw new AssertionError();
    }

    @Override
    public void syncDirectory(File f) {
      throw new AssertionError();
    }

    @Override
    public void mode(File f, int mode) {
      throw new AssertionError();
    }

    @Override
    public void symlink(String t, File f) {
      throw new AssertionError();
    }
  }

  @Test
  public void verifiesSelectedAndThreeDistinctAcrossBothRoots() throws Exception {
    Memory fs = new Memory();
    assertEquals(new BackupCopyPreflight.Result("OBSERVED", 3), fs.inspect());
    assertEquals(4, fs.hashed.size());
    assertEquals(512, fs.artifactReadBytes);
  }

  @Test
  public void changedBytesUnknownBreadthOrManualCopyCannotSupplyReserve() throws Exception {
    for (String reason : List.of("digest", "manual", "breadth", "older", "partial")) {
      Memory fs = new Memory();
      File p = fs.member(3, "verified.json");
      switch (reason) {
        case "digest" -> fs.bytes.get(fs.member(3, "portable.dshbak"))[127] ^= 1;
        case "manual" -> fs.field(p, "automatic", false);
        case "breadth" ->
            fs.field(
                p, "roots", List.of(Map.of("id", "less", "scope", "projects", "external", false)));
        case "older" -> fs.field(p, "roots", List.of());
        case "partial" -> fs.field(p, "integrity", "PARTIAL");
      }
      assertEquals(reason, new BackupCopyPreflight.Result("INSUFFICIENT", 2), fs.inspect());
    }
    Memory fs = new Memory();
    fs.bytes.get(fs.member(0, "portable.dshbak"))[127] ^= 1;
    assertEquals(new BackupCopyPreflight.Result("INELIGIBLE", 0), fs.inspect());
  }

  @Test
  public void rejectsStaleSelectionAndDirectoryReplacementDuringHash() throws Exception {
    for (boolean duringRead : List.of(false, true)) {
      Memory fs = new Memory();
      VerifiedBackupCopy selected = fs.selected();
      Runnable replace = () -> fs.dir(fs.copies.get(0));
      if (duringRead) fs.onArtifact = replace;
      else replace.run();
      assertThrows(IOException.class, () -> fs.inspect(selected));
      assertEquals(duringRead, !fs.hashed.isEmpty());
    }
  }

  @Test
  public void rejectsCatalogueAliasesUnknownMembersAndUnfinishedJournals() throws Exception {
    for (String reason :
        List.of(
            "alias", "split", "dataAlias", "extra", "busy", "stage", "proof", "catalogueExtra")) {
      Memory fs = new Memory();
      VerifiedBackupCopy selected = fs.selected();
      switch (reason) {
        case "alias" -> fs.nodes.put(fs.copies.get(3), fs.stat(fs.copies.get(2)));
        case "dataAlias" ->
            fs.nodes.put(fs.member(3, "portable.dshbak"), fs.stat(fs.member(2, "portable.dshbak")));
        case "split" -> fs.dir(new File(fs.history, selected.id));
        case "extra" -> fs.dir(fs.member(2, "extra"));
        case "busy" -> fs.field(fs.member(2, "operation.json"), "busy", true);
        case "stage" -> fs.field(fs.member(2, "operation.json"), "stage", "VERIFYING");
        case "proof" ->
            fs.field(new File(fs.files, "host-backup-completed-v1.json"), "historyKey", "old");
        case "catalogueExtra" -> {
          File c = new File(fs.files, "host-backup-catalogue");
          fs.dir(c);
          fs.dir(new File(c, "extra"));
        }
      }
      assertThrows(reason, IOException.class, () -> fs.inspect(selected));
    }
  }

  @Test
  public void reobservesBothRootsMetadataAndReferencesAfterReadingBytes() throws Exception {
    for (String fault :
        List.of("history", "root", "member", "proof", "journal", "pointer", "latest")) {
      Memory fs = new Memory();
      VerifiedBackupCopy selected = fs.selected();
      fs.onArtifact =
          () -> {
            try {
              switch (fault) {
                case "history" -> fs.dir(fs.history);
                case "root" -> fs.dir(fs.operations);
                case "member" -> fs.put(fs.member(1, "extra"), new byte[1]);
                case "journal", "proof" -> {
                  // 相同 inode、mtime、大小下改动有效 JSON 中的一个字节。
                  File f =
                      fs.member(1, fault.equals("journal") ? "operation.json" : "verified.json");
                  String key = fault.equals("journal") ? "updatedAt" : "createdAt";
                  String before =
                      new String(fs.bytes.get(f), java.nio.charset.StandardCharsets.UTF_8);
                  fs.bytes.put(
                      f,
                      before
                          .replace("\"" + key + "\":1", "\"" + key + "\":2")
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                case "pointer" ->
                    fs.json(
                        new File(fs.files, "host-backup-last-task-v1.json"),
                        Map.of("version", 1L, "id", selected.id));
                case "latest" -> {
                  File c = new File(fs.files, "host-backup-catalogue");
                  fs.dir(c);
                  fs.json(
                      new File(c, "latest.json"),
                      Map.of(
                          "operation",
                          selected.id,
                          "payloadSha256",
                          selected.sha256,
                          "sha256",
                          selected.sha256,
                          "uri",
                          "synthetic",
                          "name",
                          "synthetic"));
                }
              }
            } catch (IOException e) {
              throw new AssertionError(e);
            }
          };
      assertThrows(fault, IOException.class, () -> fs.inspect(selected));
      assertFalse(fs.hashed.isEmpty());
    }
  }

  @Test
  public void repeatedPayloadIsNotAnotherRecoveryPointAndOversizeCatalogueStopsEarly()
      throws Exception {
    Memory repeated = new Memory();
    File other = repeated.member(3, "portable.dshbak");
    byte[] bytes = repeated.bytes.get(repeated.member(2, "portable.dshbak")).clone();
    repeated.put(other, bytes);
    repeated.field(
        repeated.member(3, "verified.json"),
        "encryptedSha256",
        BackupArchive.hex(BackupArchive.sha().digest(bytes)));
    assertEquals(new BackupCopyPreflight.Result("INSUFFICIENT", 2), repeated.inspect());

    Memory overflow = new Memory();
    VerifiedBackupCopy selected = overflow.selected();
    for (int i = 0; i < 2100; i++) overflow.dir(new File(overflow.operations, "fake" + i));
    assertEquals(
        "ENTRY_LIMIT",
        assertThrows(IOException.class, () -> overflow.inspect(selected)).getMessage());
    assertTrue(overflow.hashed.isEmpty());
  }

  @Test
  public void rejectsExcessActualBytesCancelAndCloseErrors() throws Exception {
    Memory growing = new Memory();
    growing.grows = growing.member(0, "portable.dshbak");
    assertThrows(IOException.class, growing::inspect);
    assertEquals(129, growing.artifactReadBytes);
    Memory cancelled = new Memory();
    cancelled.onArtifact = cancelled.control::cancel;
    assertThrows(InterruptedIOException.class, cancelled::inspect);
    assertEquals(0, cancelled.artifactReadBytes);
    Memory failing = new Memory();
    failing.closeFailure = failing.member(0, "portable.dshbak");
    assertEquals(
        "injected close failure", assertThrows(IOException.class, failing::inspect).getMessage());
  }
}
