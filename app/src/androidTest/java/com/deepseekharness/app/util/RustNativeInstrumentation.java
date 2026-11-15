package com.deepseekharness.app.util;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/**
 * 显式选择的 Android Rust 加载候选：真实 JNI、真实私有文件 FD，不改变生产后端开关。
 *
 * <p>仅由 -Pdsha.testRunner=com.deepseekharness.app.util.RustNativeInstrumentation 构建；
 * 运行时必须传入 commit/runId/runAttempt/repository，并与目标 APK 内的 Rust 回执逐项绑定。
 * 不输出参数全集、用户文件、私有路径、异常正文或堆栈。静态编译本类不构成 Android 执行证据。
 */
public final class RustNativeInstrumentation extends Instrumentation {
  private static final String LIBRARY = "libdsha_rs.so";
  private static final String APK_LIBRARY = "lib/arm64-v8a/" + LIBRARY;
  private static final String RECEIPT = "assets/rust-native-receipt.json";
  private static final int MAX_RECEIPT_BYTES = 2 * 1024 * 1024;
  private static final long MAX_LIBRARY_BYTES = 16L * 1024 * 1024;
  private static final int SHA256 = 1;
  private static final String ABC_SHA256 =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
  private Bundle arguments;

  private enum Failure {
    NONE,
    ARGUMENT_BINDING_INVALID,
    ANDROID_PLATFORM_UNSUPPORTED,
    PAGE_SIZE_UNAVAILABLE,
    BACKENDS_NOT_DEFAULT_OFF,
    RECEIPT_MISSING_OR_DUPLICATE,
    RECEIPT_INVALID,
    RECEIPT_BINDING_MISMATCH,
    APK_LIBRARY_MISSING_OR_DUPLICATE,
    APK_LIBRARY_MISMATCH,
    INSTALLED_LIBRARY_MISSING,
    INSTALLED_LIBRARY_MISMATCH,
    MAPPED_LIBRARY_UNVERIFIED,
    NATIVE_LOAD_FAILED,
    NATIVE_STATUS_MISMATCH,
    NATIVE_ABI_MISMATCH,
    TEMP_FILE_INVALID,
    NATIVE_DIGEST_ERROR,
    NATIVE_DIGEST_LENGTH_MISMATCH,
    NATIVE_DIGEST_MISMATCH,
    OWNED_TEMP_CLEANUP_FAILED,
    NATIVE_LINK_ERROR,
    IO_OR_RUNTIME_ERROR
  }

  private static final class ProbeFailure extends Exception {
    final Failure code;

    ProbeFailure(Failure code) {
      this.code = code;
    }
  }

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    arguments = args == null ? new Bundle() : args;
    start();
  }

  @Override
  public void onStart() {
    Bundle output = new Bundle();
    output.putString("probe", "dsha-rust-android-jni-v1");
    output.putString("result", "FAIL");
    output.putString("failure", Failure.IO_OR_RUNTIME_ERROR.name());
    output.putString("stage", "platform");
    output.putString("androidAbis", Arrays.toString(Build.SUPPORTED_ABIS));
    output.putInt("androidSdk", Build.VERSION.SDK_INT);
    output.putString("androidRelease", Build.VERSION.RELEASE);
    output.putString("buildManufacturer", Build.MANUFACTURER);
    output.putString("buildModel", Build.MODEL);
    output.putString("buildDevice", Build.DEVICE);
    output.putString("buildId", Build.ID);
    output.putString("buildFingerprint", Build.FINGERPRINT);
    output.putBoolean("process64Bit", Process.is64Bit());
    output.putBoolean("rustBackendsEnabledBefore", RustBackends.enabled());
    File ownedTemporary = null;
    boolean success = false;
    try {
      require(
          Build.VERSION.SDK_INT >= 30
              && Process.is64Bit()
              && Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"),
          Failure.ANDROID_PLATFORM_UNSUPPORTED);
      long pageSize = Os.sysconf(OsConstants._SC_PAGESIZE);
      output.putLong("pageSize", pageSize);
      require(pageSize > 0, Failure.PAGE_SIZE_UNAVAILABLE);
      require(!RustBackends.enabled(), Failure.BACKENDS_NOT_DEFAULT_OFF);

      output.putString("stage", "receipt");
      String commit = binding("commit", "[0-9a-f]{40}");
      String runId = binding("runId", "[1-9][0-9]{0,19}");
      String attempt = binding("runAttempt", "[1-9][0-9]{0,9}");
      String repository = binding("repository", "[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}");
      output.putString("commit", commit);
      output.putString("runId", runId);
      output.putString("runAttempt", attempt);
      output.putString("repository", repository);
      ApplicationInfo application = getTargetContext().getApplicationInfo();
      String expectedHash;
      long expectedSize;
      try (ZipFile apk = new ZipFile(application.sourceDir)) {
        ZipEntry entry = uniqueEntry(apk, RECEIPT, Failure.RECEIPT_MISSING_OR_DUPLICATE);
        byte[] receiptBytes;
        try (InputStream input = apk.getInputStream(entry)) {
          receiptBytes = boundedBytes(input, MAX_RECEIPT_BYTES);
        }
        output.putString(
            "embeddedReceiptSha256",
            hex(MessageDigest.getInstance("SHA-256").digest(receiptBytes)));
        JSONObject receipt = new JSONObject(new String(receiptBytes, StandardCharsets.UTF_8));
        require(
            receipt.getInt("schema") == 2
                && "dsha-rust-native".equals(receipt.getString("kind"))
                && "github-actions".equals(receipt.getString("provenance"))
                && "android".equals(receipt.getString("target"))
                && "aarch64-linux-android"
                    .equals(receipt.getJSONObject("toolchain").getString("targetTriple")),
            Failure.RECEIPT_INVALID);
        require(
            commit.equals(receipt.getString("commit"))
                && runId.equals(receipt.getString("runId"))
                && attempt.equals(receipt.getString("runAttempt"))
                && repository.equals(receipt.getString("repository")),
            Failure.RECEIPT_BINDING_MISMATCH);
        JSONObject artifact = receipt.getJSONObject("artifact");
        expectedHash = artifact.getString("sha256");
        expectedSize = artifact.getLong("size");
        require(
            LIBRARY.equals(artifact.getString("artifactFile"))
                && expectedHash.matches("[0-9a-f]{64}")
                && expectedSize > 0
                && expectedSize <= MAX_LIBRARY_BYTES,
            Failure.RECEIPT_INVALID);
        output.putString("librarySha256", expectedHash);
        output.putLong("librarySize", expectedSize);
        output.putString("libraryAbi", "arm64-v8a");
        output.putString("stage", "apk-library");
        ZipEntry library = uniqueEntry(apk, APK_LIBRARY, Failure.APK_LIBRARY_MISSING_OR_DUPLICATE);
        require(library.getSize() == expectedSize, Failure.APK_LIBRARY_MISMATCH);
        try (InputStream input = apk.getInputStream(library)) {
          require(expectedHash.equals(hash(input, expectedSize)), Failure.APK_LIBRARY_MISMATCH);
        }
        output.putBoolean("apkLibraryVerified", true);
      }

      output.putString("stage", "installed-library");
      // 当前 Standard 使用 useLegacyPackaging=true，Android 从这个只读安装目录加载库。
      // 若以后改为 APK 直接映射，本探针明确失败，不能把未确认的来源记成成功。
      require(application.nativeLibraryDir != null, Failure.INSTALLED_LIBRARY_MISSING);
      File installedLibrary = new File(application.nativeLibraryDir, LIBRARY).getCanonicalFile();
      require(
          installedLibrary.isFile() && installedLibrary.length() == expectedSize,
          Failure.INSTALLED_LIBRARY_MISSING);
      try (InputStream input = new FileInputStream(installedLibrary)) {
        require(expectedHash.equals(hash(input, expectedSize)), Failure.INSTALLED_LIBRARY_MISMATCH);
      }
      output.putString("librarySource", "nativeLibraryDir");
      output.putBoolean("installedLibraryVerified", true);

      output.putString("stage", "native-load");
      output.putString("nativeStatusBefore", RustNative.status().name());
      boolean loaded = RustNative.ensureLoaded();
      output.putBoolean("ensureLoaded", loaded);
      output.putString("nativeStatus", RustNative.status().name());
      output.putString("nativeStatusCode", RustNative.statusCode());
      output.putBoolean("nativeAvailable", RustNative.isAvailable());
      require(loaded, Failure.NATIVE_LOAD_FAILED);
      require(
          RustNative.status() == RustNative.Status.LOADED
              && "LOADED".equals(RustNative.statusCode())
              && RustNative.isAvailable(),
          Failure.NATIVE_STATUS_MISMATCH);
      int abiVersion = RustNative.abiVersion();
      output.putInt("nativeAbiVersion", abiVersion);
      require(abiVersion == 1 && RustNative.ABI_VERSION == 1, Failure.NATIVE_ABI_MISMATCH);
      output.putString("stage", "mapped-library");
      requireMappedLibrary(installedLibrary);
      output.putBoolean("mappedLibraryVerified", true);

      output.putString("stage", "fd-digest");
      File cache = getTargetContext().getCacheDir().getCanonicalFile();
      ownedTemporary = File.createTempFile("rust-native-owned-", ".bin", cache);
      require(
          cache.equals(ownedTemporary.getCanonicalFile().getParentFile()),
          Failure.TEMP_FILE_INVALID);
      byte[] knownBytes = "abc".getBytes(StandardCharsets.US_ASCII);
      try (FileOutputStream file = new FileOutputStream(ownedTemporary)) {
        file.write(knownBytes);
        file.getFD().sync();
      }
      ByteBuffer nativeOutput = ByteBuffer.allocateDirect(32);
      try (ParcelFileDescriptor descriptor =
          ParcelFileDescriptor.open(ownedTemporary, ParcelFileDescriptor.MODE_READ_ONLY)) {
        require(
            descriptor.getFd() >= 0 && descriptor.getStatSize() == knownBytes.length,
            Failure.TEMP_FILE_INVALID);
        long bytesRead =
            RustNative.digest(descriptor.getFd(), 0, knownBytes.length, SHA256, nativeOutput);
        output.putLong("nativeDigestReturn", bytesRead);
        require(bytesRead >= 0, Failure.NATIVE_DIGEST_ERROR);
        require(bytesRead == knownBytes.length, Failure.NATIVE_DIGEST_LENGTH_MISMATCH);
        byte[] actual = new byte[32];
        for (int index = 0; index < actual.length; index++) actual[index] = nativeOutput.get(index);
        require(ABC_SHA256.equals(hex(actual)), Failure.NATIVE_DIGEST_MISMATCH);
        output.putString("knownVectorSha256", hex(actual));
      }
      output.putBoolean("nativeFdDigestVerified", true);
      require(!RustBackends.enabled(), Failure.BACKENDS_NOT_DEFAULT_OFF);
      success = true;
      output.putString("failure", Failure.NONE.name());
      output.putString("stage", "complete");
    } catch (ProbeFailure error) {
      output.putString("failure", error.code.name());
    } catch (org.json.JSONException error) {
      output.putString("failure", Failure.RECEIPT_INVALID.name());
    } catch (LinkageError error) {
      output.putString("failure", Failure.NATIVE_LINK_ERROR.name());
    } catch (Exception | AssertionError error) {
      output.putString("failure", Failure.IO_OR_RUNTIME_ERROR.name());
    } finally {
      // 只删除本次 createTempFile 返回的一个文件，绝不遍历或清理现有缓存。
      if (ownedTemporary != null && !ownedTemporary.delete()) {
        success = false;
        output.putString("failure", Failure.OWNED_TEMP_CLEANUP_FAILED.name());
      }
      output.putBoolean(
          "ownedTemporaryRemoved", ownedTemporary == null || !ownedTemporary.exists());
      output.putBoolean("rustBackendsEnabledAfter", RustBackends.enabled());
      if (RustBackends.enabled()) {
        success = false;
        output.putString("failure", Failure.BACKENDS_NOT_DEFAULT_OFF.name());
      }
      output.putString("result", success ? "PASS" : "FAIL");
      finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, output);
    }
  }

  private String binding(String name, String expression) throws ProbeFailure {
    String value = arguments.getString(name);
    require(value != null && value.matches(expression), Failure.ARGUMENT_BINDING_INVALID);
    return value;
  }

  private static void require(boolean condition, Failure failure) throws ProbeFailure {
    if (!condition) throw new ProbeFailure(failure);
  }

  private static ZipEntry uniqueEntry(ZipFile apk, String name, Failure failure)
      throws ProbeFailure {
    ZipEntry result = null;
    Enumeration<? extends ZipEntry> entries = apk.entries();
    while (entries.hasMoreElements()) {
      ZipEntry next = entries.nextElement();
      if (!name.equals(next.getName())) continue;
      require(result == null && !next.isDirectory(), failure);
      result = next;
    }
    require(result != null, failure);
    return result;
  }

  private static byte[] boundedBytes(InputStream input, int maximum) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int count;
    while ((count = input.read(buffer)) != -1) {
      require(output.size() + count <= maximum, Failure.RECEIPT_INVALID);
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }

  private static String hash(InputStream input, long expectedSize) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[65536];
    long total = 0;
    int count;
    while ((count = input.read(buffer)) != -1) {
      total += count;
      require(total <= expectedSize, Failure.INSTALLED_LIBRARY_MISMATCH);
      digest.update(buffer, 0, count);
    }
    require(total == expectedSize, Failure.INSTALLED_LIBRARY_MISMATCH);
    return hex(digest.digest());
  }

  private static void requireMappedLibrary(File expected) throws Exception {
    boolean executableMapping = false;
    try (BufferedReader maps = new BufferedReader(new FileReader("/proc/self/maps"))) {
      String line;
      while ((line = maps.readLine()) != null) {
        String[] fields = line.trim().split("\\s+", 6);
        if (fields.length != 6 || !fields[5].contains(LIBRARY)) continue;
        require(expected.getPath().equals(fields[5]), Failure.MAPPED_LIBRARY_UNVERIFIED);
        if (fields[1].contains("x")) executableMapping = true;
      }
    }
    require(executableMapping, Failure.MAPPED_LIBRARY_UNVERIFIED);
  }

  private static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      result.append(Character.forDigit((value >>> 4) & 15, 16));
      result.append(Character.forDigit(value & 15, 16));
    }
    return result.toString();
  }
}
