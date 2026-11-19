package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.util.WebSessionStorage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;

/** 仅运行生产发布边界；假文件系统与合成密钥，不访问 Android Keystore 或设备文件。 */
public final class StoredWebSessionTest {
  private static final File DATA = new File("/synthetic-web-session-data");
  private static final File HOME = new File(DATA, "no_backup");
  private static final File RECORD = new File(HOME, "web-session.enc");

  private static final class Fs implements BackupFileSystem {
    byte[] bytes = "old-authenticated-envelope".getBytes(StandardCharsets.UTF_8);
    String recordType = "FILE", homeKey = "home";
    int publications, mutations;

    @Override
    public Node stat(File file) {
      if (file.equals(DATA)) return new Node("DIRECTORY", "data", 0, 0, 1, 0700);
      if (file.equals(HOME)) return new Node("DIRECTORY", homeKey, 0, 0, 1, 0700);
      if (file.equals(RECORD)) return new Node(recordType, "record", bytes.length, 0, 1, 0600);
      throw new AssertionError("unexpected path " + file);
    }

    @Override
    public InputStream read(File file, Node expected) {
      assertEquals(RECORD, file);
      return new ByteArrayInputStream(bytes);
    }

    @Override
    public void atomic(File directory, String name, byte[] candidate) {
      assertEquals(HOME, directory);
      assertEquals("web-session.enc", name);
      publications++;
      mutations++;
      bytes = candidate.clone();
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
      assertEquals(HOME, f);
    }

    @Override
    public void mode(File f, int mode) {
      throw new AssertionError();
    }

    @Override
    public void symlink(String target, File f) {
      throw new AssertionError();
    }
  }

  private static String encrypt(String plain) throws IOException {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(new byte[32], "AES"),
          new GCMParameterSpec(128, new byte[12]));
      byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      byte[] envelope = new byte[12 + encrypted.length];
      System.arraycopy(encrypted, 0, envelope, 12, encrypted.length);
      return Base64.getEncoder().encodeToString(envelope);
    } catch (Exception failure) {
      throw new IOException("SYNTHETIC_ENCRYPTION", failure);
    }
  }

  private static String decrypt(String envelope) throws IOException {
    try {
      byte[] bytes = Base64.getDecoder().decode(envelope);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(new byte[32], "AES"),
          new GCMParameterSpec(128, bytes, 0, 12));
      return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
    } catch (Exception failure) {
      throw new IOException("SYNTHETIC_AUTHENTICATION", failure);
    }
  }

  @Test
  public void authenticatedEnvelopePublishesAndFitsActualReaderBudget() throws Exception {
    Fs fs = new Fs();
    String plain = "{\"schema\":1,\"synthetic\":\"中文\\u0000\"}";
    StoredWebSession.publishEnvelope(
        fs,
        WebSessionStorage.bind(fs, DATA, HOME),
        plain,
        StoredWebSessionTest::encrypt,
        StoredWebSessionTest::decrypt);
    assertEquals(1, fs.publications);
    assertEquals(
        plain,
        decrypt(
            new String(fs.small(RECORD, StoredWebSession.ENVELOPE_BYTES), StandardCharsets.UTF_8)));
  }

  @Test
  public void exactFinalBudgetAllowedButOneMoreBytePreservesOldRecord() throws Exception {
    Fs fs = new Fs();
    var home = WebSessionStorage.bind(fs, DATA, HOME);
    String exact = "x".repeat(StoredWebSession.ENVELOPE_BYTES);
    StoredWebSession.publishEnvelope(fs, home, "plain", value -> exact, value -> "plain");
    byte[] old = fs.bytes.clone();
    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                StoredWebSession.publishEnvelope(
                    fs, home, "plain", value -> exact + "x", value -> "plain"));
    assertEquals("WEB_SESSION_ENVELOPE_LIMIT", failure.getMessage());
    assertArrayEquals(old, fs.bytes);
    assertEquals(1, fs.publications);
    assertEquals(1, fs.mutations);
    assertEquals(exact.length(), fs.small(RECORD, StoredWebSession.ENVELOPE_BYTES).length);
  }

  @Test
  public void byteBudgetIsNotCharacterBudget() throws Exception {
    Fs fs = new Fs();
    var home = WebSessionStorage.bind(fs, DATA, HOME);
    byte[] old = fs.bytes.clone();
    String multibyte = "中".repeat(StoredWebSession.ENVELOPE_BYTES / 3 + 1);
    assertTrue(multibyte.length() < StoredWebSession.ENVELOPE_BYTES);
    assertThrows(
        IOException.class,
        () ->
            StoredWebSession.publishEnvelope(
                fs, home, "plain", value -> multibyte, value -> "plain"));
    assertArrayEquals(old, fs.bytes);
    assertEquals(0, fs.mutations);
  }

  @Test
  public void individuallyBoundedCommandsCanExceedEnvelopeButCannotReplaceRecord()
      throws Exception {
    Fs fs = new Fs();
    var home = WebSessionStorage.bind(fs, DATA, HOME);
    StoredWebSession.publishEnvelope(
        fs, home, "old-valid-record", StoredWebSessionTest::encrypt, StoredWebSessionTest::decrypt);
    byte[] old = fs.bytes.clone();
    String command = "node /usr/local/bin/dsh web --synthetic=" + "a".repeat(12500);
    assertTrue(command.getBytes(StandardCharsets.UTF_8).length < 16384);
    String plain = "{\"webCommand\":\"" + command + "\",\"launcherCommand\":\"" + command + "\"}";
    assertTrue(
        encrypt(plain).getBytes(StandardCharsets.UTF_8).length > StoredWebSession.ENVELOPE_BYTES);
    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                StoredWebSession.publishEnvelope(
                    fs, home, plain, StoredWebSessionTest::encrypt, StoredWebSessionTest::decrypt));
    assertEquals("WEB_SESSION_ENVELOPE_LIMIT", failure.getMessage());
    assertArrayEquals(old, fs.bytes);
    assertEquals("old-valid-record", decrypt(new String(fs.bytes, StandardCharsets.UTF_8)));
    assertEquals(1, fs.publications);
  }

  @Test
  public void failedEncryptionOrAuthenticationNeverTouchesAtomicRecoverySlots() throws Exception {
    for (int mode = 0; mode < 4; mode++) {
      Fs fs = new Fs();
      var home = WebSessionStorage.bind(fs, DATA, HOME);
      byte[] old = fs.bytes.clone();
      final int selected = mode;
      assertThrows(
          IOException.class,
          () ->
              StoredWebSession.publishEnvelope(
                  fs,
                  home,
                  "plain",
                  value -> selected == 0 ? "" : "ciphertext",
                  value -> {
                    if (selected == 1) throw new IOException("AUTH_FAILED");
                    if (selected == 2) fs.homeKey = "replaced-home";
                    return selected == 3 ? "wrong-plaintext" : "plain";
                  }));
      assertArrayEquals(old, fs.bytes);
      assertEquals(0, fs.mutations);
    }
  }

  @Test
  public void historicalOversizeAndNonRegularRecordsRemainBoundedAndUntouched() throws Exception {
    for (String type : List.of("FILE", "LINK", "DIRECTORY", "SPECIAL")) {
      Fs fs = new Fs();
      fs.recordType = type;
      String plain = "{\"synthetic\":\"" + "x".repeat(type.equals("FILE") ? 25000 : 10) + "\"}";
      fs.bytes = encrypt(plain).getBytes(StandardCharsets.UTF_8);
      assertEquals(plain, decrypt(new String(fs.bytes, StandardCharsets.UTF_8)));
      byte[] old = fs.bytes.clone();
      assertThrows(IOException.class, () -> fs.small(RECORD, StoredWebSession.ENVELOPE_BYTES));
      assertArrayEquals(old, fs.bytes);
      assertEquals(0, fs.mutations);
    }
  }
}
