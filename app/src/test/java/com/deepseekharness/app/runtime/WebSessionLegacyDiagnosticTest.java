package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.*;
import java.util.List;
import org.junit.Test;

/** 合成 metadata；所有正文、目录遍历和写端均禁止，不访问真实会话文件。 */
public final class WebSessionLegacyDiagnosticTest {
  private static final File DATA = new File("/synthetic-diagnostic-data");
  private static final File HOME = new File(DATA, "no_backup");
  private static final File RECORD = new File(HOME, "web-session.enc");

  private static BackupFileSystem.Node node(String type, long size) {
    return new BackupFileSystem.Node(type, "synthetic-key", size, 2, 1, 0600);
  }

  private static final class Fs implements BackupFileSystem {
    Node before = node("FILE", 32769), after = before;
    String homeType = "DIRECTORY";
    int recordStats, homeStats, changeHomeAfter = Integer.MAX_VALUE, failRecordAt;
    boolean failHome, runtimeFailure;

    @Override
    public Node stat(File file) throws IOException {
      if (file.equals(DATA)) return node("DIRECTORY", 0);
      if (file.equals(HOME)) {
        if (failHome) throw new IOException("sensitive-path-token-ciphertext");
        homeStats++;
        return new Node(
            homeType, homeStats > changeHomeAfter ? "replaced-home" : "home", 0, 0, 1, 0700);
      }
      if (!file.equals(RECORD)) throw new AssertionError("unexpected metadata target");
      recordStats++;
      if (recordStats == failRecordAt) {
        if (runtimeFailure) throw new SecurityException("sensitive-path-token-ciphertext");
        throw new IOException("sensitive-path-token-ciphertext");
      }
      return recordStats == 1 ? before : after;
    }

    @Override
    public String readLink(File f) {
      throw new AssertionError();
    }

    @Override
    public List<String> list(File f) {
      throw new AssertionError();
    }

    @Override
    public InputStream read(File f, Node n) {
      throw new AssertionError();
    }

    @Override
    public byte[] small(File f, int n) {
      throw new AssertionError();
    }

    @Override
    public byte[] currentSnapshot(File f, int n) {
      throw new AssertionError();
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
    public void move(File a, File b) {
      throw new AssertionError();
    }

    @Override
    public void delete(File f) {
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

    @Override
    public void prepareOwnedRemoval(File f) {
      throw new AssertionError();
    }

    @Override
    public void atomic(File d, String n, byte[] b) {
      throw new AssertionError();
    }

    @Override
    public void parents(File d, String n) {
      throw new AssertionError();
    }

    @Override
    public void removeOwned(File d, String n) {
      throw new AssertionError();
    }
  }

  private static WebSessionLegacyDiagnostic.DiagnosticReport inspect(Fs fs) {
    var report = WebSessionLegacyDiagnostic.inspect(fs, DATA);
    assertEquals(WebSessionLegacyDiagnostic.Verification.UNVERIFIED, report.verification);
    assertEquals(32768, report.normalLimitBytes);
    return report;
  }

  @Test
  public void allLeafTypesAreDistinctAndNeverReadBody() {
    for (String type :
        List.of("FILE", "MISSING", "LINK", "DIRECTORY", "SPECIAL", "OTHER", "UNREADABLE")) {
      Fs fs = new Fs();
      fs.before = fs.after = node(type, Long.MAX_VALUE);
      var report = inspect(fs);
      assertEquals(
          type.equals("OTHER") || type.equals("UNREADABLE") ? "UNKNOWN" : type, report.type.name());
      assertEquals(type.equals("FILE"), report.knownSize);
      assertEquals(WebSessionLegacyDiagnostic.Code.METADATA_ONLY, report.code);
      assertEquals(WebSessionLegacyDiagnostic.Stability.UNCHANGED_METADATA, report.stability);
      assertEquals(2, fs.recordStats);
      if (!type.equals("FILE")) {
        assertEquals(-1, report.sizeBytes);
        assertEquals(WebSessionLegacyDiagnostic.Comparison.UNKNOWN, report.comparison);
      }
    }
  }

  @Test
  public void sizeBoundariesAndLongExtremesDoNotOverflow() {
    for (long size : new long[] {Long.MIN_VALUE, -1, 0, 32767, 32768, 32769, Long.MAX_VALUE}) {
      Fs fs = new Fs();
      fs.before = fs.after = node("FILE", size);
      var report = inspect(fs);
      assertEquals(size >= 0, report.knownSize);
      assertEquals(size >= 0 ? size : -1, report.sizeBytes);
      assertEquals(
          size < 0
              ? WebSessionLegacyDiagnostic.Comparison.UNKNOWN
              : size > 32768
                  ? WebSessionLegacyDiagnostic.Comparison.ABOVE_LIMIT
                  : WebSessionLegacyDiagnostic.Comparison.WITHIN_LIMIT,
          report.comparison);
    }
  }

  @Test
  public void changedLeafNeverPublishesStaleTypeOrSize() {
    for (BackupFileSystem.Node replacement :
        List.of(
            new BackupFileSystem.Node("FILE", "new-inode", 32769, 2, 1, 0600),
            new BackupFileSystem.Node("FILE", "synthetic-key", 32769, 2, 9, 0600),
            node("FILE", 32768),
            node("MISSING", 0),
            node("LINK", 0),
            new BackupFileSystem.Node("FILE", "synthetic-key", 32769, 3, 1, 0600),
            new BackupFileSystem.Node("FILE", "synthetic-key", 32769, 2, 1, 0644))) {
      Fs fs = new Fs();
      fs.after = replacement;
      var report = inspect(fs);
      assertEquals(WebSessionLegacyDiagnostic.Stability.CHANGED, report.stability);
      assertEquals(WebSessionLegacyDiagnostic.Type.UNKNOWN, report.type);
      assertFalse(report.knownSize);
      assertEquals(WebSessionLegacyDiagnostic.Comparison.UNKNOWN, report.comparison);
    }
  }

  @Test
  public void changedHomeAtEitherPostCheckDiscardsMetadata() {
    // bind 的 child/identity/verify共读取home两次；其后两个verify分别检查变化。
    for (int threshold : new int[] {2, 3}) {
      Fs fs = new Fs();
      fs.changeHomeAfter = threshold;
      var report = inspect(fs);
      assertEquals(WebSessionLegacyDiagnostic.Code.HOME_CHANGED, report.code);
      assertEquals(WebSessionLegacyDiagnostic.Type.UNKNOWN, report.type);
      assertFalse(report.knownSize);
    }
  }

  @Test
  public void homeFailureAndNonDirectoryNeverInspectLeaf() {
    for (String type : List.of("MISSING", "LINK", "FILE", "SPECIAL", "OTHER")) {
      Fs fs = new Fs();
      fs.homeType = type;
      assertEquals(WebSessionLegacyDiagnostic.Code.HOME_UNAVAILABLE, inspect(fs).code);
      assertEquals(0, fs.recordStats);
    }
    Fs fs = new Fs();
    fs.failHome = true;
    assertEquals(WebSessionLegacyDiagnostic.Code.HOME_UNAVAILABLE, inspect(fs).code);
    assertEquals(0, fs.recordStats);
  }

  @Test
  public void exceptionsAtBothLeafObservationsAreOnlyAllowlistedCodes() {
    for (int at : new int[] {1, 2}) {
      for (boolean runtime : new boolean[] {false, true}) {
        Fs fs = new Fs();
        fs.failRecordAt = at;
        fs.runtimeFailure = runtime;
        var report = inspect(fs);
        assertEquals(WebSessionLegacyDiagnostic.Code.METADATA_UNAVAILABLE, report.code);
        assertEquals(WebSessionLegacyDiagnostic.Stability.UNKNOWN, report.stability);
        assertFalse(report.knownSize);
        // 报告没有异常、path、节点或任意字符串字段。
        for (var field : report.getClass().getFields()) {
          assertFalse(Throwable.class.isAssignableFrom(field.getType()));
          assertNotEquals(String.class, field.getType());
          assertNotEquals(File.class, field.getType());
          assertNotEquals(BackupFileSystem.Node.class, field.getType());
        }
      }
    }
  }

  @Test
  public void nullOrUnavailableContextCannotProbeAnything() {
    assertEquals(
        WebSessionLegacyDiagnostic.Code.HOME_UNAVAILABLE,
        WebSessionLegacyDiagnostic.inspect(null).code);
  }

  @Test
  public void nullMetadataRemainsUnknownAndAssertionErrorsAreNotSwallowed() {
    Fs fs = new Fs();
    for (boolean first : new boolean[] {false, true}) {
      fs = new Fs();
      if (first) fs.before = null;
      else fs.after = null;
      var report = inspect(fs);
      assertEquals(WebSessionLegacyDiagnostic.Type.UNKNOWN, report.type);
      assertEquals(WebSessionLegacyDiagnostic.Stability.UNKNOWN, report.stability);
      assertFalse(report.knownSize);
    }
    Fs finalFs = fs;
    assertThrows(
        AssertionError.class,
        () -> WebSessionLegacyDiagnostic.inspect(finalFs, new File("/foreign")));
  }
}
