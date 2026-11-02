package com.deepseekharness.app.runtime;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/**
 * Android 原生运行环境的宿主布局与首次安装。
 *
 * <p>APK 里有两样东西：nativeLibraryDir 里的 {@code libnode.so} / {@code librg.so}（Android 只允许执行这里的文件），
 * 以及 {@code assets/native-runtime.zip}（依赖库、证书、打过 Android 补丁的 DSH 树）。首次使用时把 zip 解到
 * {@code files/native-runtime/<zip 摘要>/}：先解到临时目录、逐个核对清单里的摘要，全部成功后写完成标记再改名。
 * 同一摘要的目录已完整就直接复用；不同 APK 的 zip 摘要不同，各自一个目录，互不覆盖。
 *
 * <p>原生运行环境有自己的 HOME / DSH_HOME（{@code files/native-home}），第一版不与容器共享会话与配置；
 * 设备桥凭据仍在容器同一个用户数据目录里（桥只写一处），由 {@code DSHA_BRIDGE_STATE_DIR} 告诉 Node。
 */
public final class NativeRuntime {
  static final String MANIFEST = "native-runtime.json";
  static final String ARCHIVE = "native-runtime.zip";
  private static final String COMPLETE = ".dsha-native-complete";
  private static final String ACTIVE_ENGINE = "dsha-web-engine";
  private static final long MAX_ENTRY = 256L * 1024 * 1024;

  private final Context context;
  private final String archiveSha256;
  private final String entry;

  private NativeRuntime(Context context, String archiveSha256, String entry) {
    this.context = context.getApplicationContext();
    this.archiveSha256 = archiveSha256;
    this.entry = entry;
  }

  /** 读 APK 内清单；APK 没有原生运行时（例如裁剪过的构建）时返回 null。 */
  public static NativeRuntime of(Context context) {
    try (InputStream in = context.getAssets().open(MANIFEST)) {
      JSONObject manifest =
          new JSONObject(new String(readAll(in, 1 << 20), StandardCharsets.UTF_8));
      if (manifest.getInt("schema") != 1) return null;
      String sha = manifest.getJSONObject("archive").getString("sha256");
      String entry = manifest.getString("entry");
      if (!sha.matches("[0-9a-f]{64}") || !entry.matches("node_modules/[A-Za-z0-9@._/-]+\\.js"))
        return null;
      return new NativeRuntime(context, sha, entry);
    } catch (Exception missing) {
      return null;
    }
  }

  /** 原生 node 的完整路径（安装时由系统解压到 nativeLibraryDir）。 */
  public File node() {
    return new File(context.getApplicationInfo().nativeLibraryDir, "libnode.so");
  }

  public File ripgrep() {
    return new File(context.getApplicationInfo().nativeLibraryDir, "librg.so");
  }

  /** 本 APK 对应的安装目录。 */
  public File installDirectory() {
    return new File(
        new File(context.getFilesDir(), "native-runtime"), archiveSha256.substring(0, 32));
  }

  public File entryFile() {
    return new File(installDirectory(), entry);
  }

  /** 原生运行环境的状态目录：停止标记、pid、出生身份。 */
  public static File stateDirectory(Context context) {
    return new File(context.getFilesDir(), "native-home");
  }

  public File home() {
    return stateDirectory(context);
  }

  public boolean installed() {
    return new File(installDirectory(), COMPLETE).isFile() && entryFile().isFile();
  }

  /**
   * 确保已解压并核验。已完整时立即返回；否则重新解压（几秒，约 330 MB）。
   *
   * @param progress 可为 null；收到已写入的文件数
   */
  public synchronized void ensureInstalled(java.util.function.IntConsumer progress)
      throws IOException {
    if (installed()) return;
    File target = installDirectory();
    File parent = target.getParentFile();
    if (!parent.isDirectory() && !parent.mkdirs())
      throw new IOException("NATIVE_RUNTIME_DIRECTORY");
    File temp = new File(parent, target.getName() + ".tmp-" + android.os.Process.myPid());
    deleteTree(temp);
    if (target.exists()) deleteTree(target);
    Map<String, String> verify = verifiedFiles();
    MessageDigest archive = sha256();
    int count = 0;
    try (InputStream raw = context.getAssets().open(ARCHIVE);
        java.security.DigestInputStream digested =
            new java.security.DigestInputStream(raw, archive);
        ZipInputStream zip =
            new ZipInputStream(new java.io.BufferedInputStream(digested, 1 << 16))) {
      String base = temp.getCanonicalPath() + File.separator;
      byte[] buffer = new byte[1 << 16];
      for (ZipEntry item; (item = zip.getNextEntry()) != null; ) {
        String name = item.getName();
        if (name.isEmpty()
            || name.startsWith("/")
            || name.contains("\\")
            || name.indexOf('\0') >= 0) throw new IOException("NATIVE_RUNTIME_ENTRY");
        File out = new File(temp, name);
        // zip-slip：规范化后必须仍在临时目录内。
        if (!out.getCanonicalPath().startsWith(base)) throw new IOException("NATIVE_RUNTIME_ENTRY");
        if (item.isDirectory()) {
          if (!out.isDirectory() && !out.mkdirs()) throw new IOException("NATIVE_RUNTIME_MKDIR");
          continue;
        }
        File dir = out.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("NATIVE_RUNTIME_MKDIR");
        MessageDigest file = sha256();
        long size = 0;
        try (OutputStream sink = new FileOutputStream(out)) {
          for (int n; (n = zip.read(buffer)) > 0; ) {
            size += n;
            if (size > MAX_ENTRY) throw new IOException("NATIVE_RUNTIME_ENTRY_SIZE");
            sink.write(buffer, 0, n);
            file.update(buffer, 0, n);
          }
        }
        String expected = verify.remove(name);
        if (expected != null && !expected.equals(hex(file.digest())))
          throw new IOException("NATIVE_RUNTIME_FILE_DIGEST: " + name);
        if (++count % 1000 == 0 && progress != null) progress.accept(count);
      }
      // 读完 zip 之后的尾部也要计入整体摘要。
      while (digested.read(buffer) > 0) {}
    } catch (IOException | RuntimeException failure) {
      deleteTree(temp);
      throw failure;
    }
    if (!verify.isEmpty() || !archiveSha256.equals(hex(archive.digest()))) {
      deleteTree(temp);
      throw new IOException("NATIVE_RUNTIME_ARCHIVE_DIGEST");
    }
    // 内置插件靠祖先目录查找 DSH 自带的依赖（cordis 等）；zip 不带软链接，这里补上（与容器同一做法）。
    try {
      for (String[] plugin : plugins()) {
        File dir = new File(temp, plugin[1]);
        if (!new File(dir, "package.json").isFile()) throw new IOException("NATIVE_PLUGIN_MISSING");
        android.system.Os.symlink(
            "../../node_modules/@deepseek-ai/dsh/node_modules",
            new File(dir, "node_modules").getPath());
      }
    } catch (IOException | android.system.ErrnoException failure) {
      deleteTree(temp);
      throw new IOException("NATIVE_PLUGIN_LINK", failure);
    }
    try (OutputStream mark = new FileOutputStream(new File(temp, COMPLETE))) {
      mark.write((archiveSha256 + "\n").getBytes(StandardCharsets.US_ASCII));
    }
    if (!temp.renameTo(target)) {
      deleteTree(temp);
      throw new IOException("NATIVE_RUNTIME_PUBLISH");
    }
    if (progress != null) progress.accept(count);
  }

  /** 本 APK 带的内置插件：{名字, 相对安装目录的目录}，按清单顺序。 */
  public java.util.List<String[]> plugins() throws IOException {
    try (InputStream in = context.getAssets().open(MANIFEST)) {
      org.json.JSONArray rows =
          new JSONObject(new String(readAll(in, 1 << 20), StandardCharsets.UTF_8))
              .optJSONArray("plugins");
      java.util.List<String[]> out = new java.util.ArrayList<>();
      if (rows == null) return out;
      for (int i = 0; i < rows.length(); i++) {
        JSONObject row = rows.getJSONObject(i);
        String name = row.getString("name"), dir = row.getString("directory");
        if (!name.matches("dsh-[a-z0-9-]{1,64}")
            || !dir.matches("dsha-plugins/dsha-[a-z0-9-]{1,64}"))
          throw new IOException("NATIVE_PLUGIN_ENTRY");
        out.add(new String[] {name, dir});
      }
      return out;
    } catch (org.json.JSONException bad) {
      throw new IOException("NATIVE_RUNTIME_MANIFEST", bad);
    }
  }

  /** 启动所需的全部宿主路径（规范化，因为 /data/user/0 本身是指向 /data/data 的链接）。 */
  public com.deepseekharness.app.util.NativeWebLaunch.Paths paths(File bridgeState)
      throws IOException {
    File home = home().getCanonicalFile();
    File dshHome = new File(home, ".dsh");
    File tmp = new File(home, "tmp");
    File workspace = new File(home, "Documents");
    for (File dir : new File[] {home, dshHome, tmp, workspace})
      if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("NATIVE_HOME");
    File install = installDirectory().getCanonicalFile();
    return new com.deepseekharness.app.util.NativeWebLaunch.Paths(
        node().getCanonicalPath(),
        entryFile().getCanonicalPath(),
        home.getPath(),
        dshHome.getCanonicalPath(),
        bridgeState.getCanonicalPath(),
        tmp.getCanonicalPath(),
        new File(install, "lib").getCanonicalPath(),
        new File(install, "etc/tls/cert.pem").getCanonicalPath(),
        ripgrep().getCanonicalPath(),
        workspace.getCanonicalPath());
  }

  /** 让停止与扫描认得原生 Web（只登记路径，不解压、不启动）。 */
  public void registerForRecognition() {
    try {
      com.deepseekharness.app.util.WebProcSel.registerNative(
          node().getCanonicalPath(), entryFile().getCanonicalPath());
    } catch (IOException | RuntimeException ignored) {
      // 路径不可规范化时不登记；原生启动本身也会失败，不会产生认不出的进程。
    }
  }

  // ------------------------------------------------------------ 本次 Web 用的运行环境

  /**
   * 记下即将启动的 Web 用哪个运行环境（启动前写入）。停止、恢复据此选状态目录，
   * 不按当前设置推断——设置可能在 Web 运行期间被改过。
   */
  public static void recordActiveEngine(Context context, String engine) throws IOException {
    if (!com.deepseekharness.app.util.DshEngineChoice.NATIVE.equals(engine)
        && !com.deepseekharness.app.util.DshEngineChoice.UBUNTU.equals(engine))
      throw new IOException("ACTIVE_ENGINE");
    File target = new File(context.getFilesDir(), ACTIVE_ENGINE);
    File temp = new File(context.getFilesDir(), ACTIVE_ENGINE + ".tmp");
    try (FileOutputStream out = new FileOutputStream(temp)) {
      out.write(engine.getBytes(StandardCharsets.US_ASCII));
      out.getFD().sync();
    }
    if (!temp.renameTo(target)) throw new IOException("ACTIVE_ENGINE_WRITE");
  }

  /** 上次启动的 Web 是否是原生运行环境；没有记录或内容不认识时按容器处理（与升级前行为一致）。 */
  public static boolean lastWebWasNative(Context context) {
    File file = new File(context.getFilesDir(), ACTIVE_ENGINE);
    try (InputStream in = new java.io.FileInputStream(file)) {
      return com.deepseekharness.app.util.DshEngineChoice.NATIVE.equals(
          new String(readAll(in, 16), StandardCharsets.US_ASCII));
    } catch (IOException missing) {
      return false;
    }
  }

  // ------------------------------------------------------------ 内部

  private Map<String, String> verifiedFiles() throws IOException {
    try (InputStream in = context.getAssets().open(MANIFEST)) {
      JSONObject files =
          new JSONObject(new String(readAll(in, 1 << 20), StandardCharsets.UTF_8))
              .getJSONObject("verifiedFiles");
      Map<String, String> result = new HashMap<>();
      for (java.util.Iterator<String> keys = files.keys(); keys.hasNext(); ) {
        String key = keys.next();
        result.put(key, files.getString(key));
      }
      return result;
    } catch (org.json.JSONException bad) {
      throw new IOException("NATIVE_RUNTIME_MANIFEST", bad);
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
    return out.toString();
  }

  private static byte[] readAll(InputStream in, int limit) throws IOException {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    for (int n; (n = in.read(buffer)) > 0; ) {
      out.write(buffer, 0, n);
      if (out.size() > limit) throw new IOException("NATIVE_RUNTIME_MANIFEST_SIZE");
    }
    return out.toByteArray();
  }

  /** 只删我们自己的目录；不跟随软链接（Files.walk 默认不跟随）。 */
  static void deleteTree(File root) {
    if (root == null || !root.exists()) return;
    try (java.util.stream.Stream<java.nio.file.Path> walk =
        java.nio.file.Files.walk(root.toPath())) {
      walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException ignored) {
    }
  }
}
