package com.deepseekharness.app.runtime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 原生运行环境：把本 APK 的内置插件登记进 DSH 的 web profile（替代容器里的 register-builtin-plugins.py）。
 *
 * <p>只做 DSH 加载所需的最小文件效果：{@code profiles/web/package.json} 的 bundles 与 link: 依赖，以及
 * {@code profiles/web/node_modules/<名>} 指向本 APK 插件目录的相对软链接。禁用标记与"只追加、尊重用户移除"的规则见
 * {@link com.deepseekharness.app.util.NativePluginPlan}。
 *
 * <p>与 DSH 自己的写入互斥：先锁 {@code <HOME>/.dsha-data.lock}，再独占创建 {@code package.json.lock}（DSH 同一协议）。
 * 只在启动原生 Web 之前调用，此时原生 Web 已确认退出。
 */
public final class NativePlugins {
  private static final String REGISTERED = ".dsha-builtin-registered.json";

  private NativePlugins() {}

  /**
   * @param home 原生 HOME（DSH_HOME 是其下的 .dsh）
   * @param install 原生运行时安装目录
   * @param plugins {名字, 相对安装目录的插件目录}
   * @return 本次启用的内置插件名（诊断用）
   */
  public static List<String> register(File home, File install, List<String[]> plugins)
      throws IOException {
    File dshHome = new File(home, ".dsh");
    File profile = new File(dshHome, "profiles/web");
    File modules = new File(profile, "node_modules");
    if (!modules.isDirectory() && !modules.mkdirs())
      throw new IOException("NATIVE_PROFILE_DIRECTORY");
    try (RandomAccessFile data = new RandomAccessFile(new File(home, ".dsha-data.lock"), "rw");
        FileLock ignored = data.getChannel().lock()) {
      File lock = new File(profile, "package.json.lock");
      claim(lock);
      try {
        return registerLocked(dshHome, profile, modules, install, plugins);
      } finally {
        lock.delete();
      }
    }
  }

  private static List<String> registerLocked(
      File dshHome, File profile, File modules, File install, List<String[]> plugins)
      throws IOException {
    writeIfMissing(new File(profile, "cordis.yml"), "[]\n");
    writeIfMissing(
        new File(profile, "cordis.patch.yml"),
        "# Your patch layer for this dsh profile, applied after every bundle layer.\n[]\n");
    writeIfMissing(
        new File(profile, "pnpm-workspace.yaml"),
        "packages:\n  - .\n\nnodeLinker: hoisted\nautoInstallPeers: false\n");
    File manifestFile = new File(profile, "package.json");
    JSONObject manifest;
    try {
      manifest =
          manifestFile.isFile()
              ? new JSONObject(
                  new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8))
              : new JSONObject().put("name", "dsh-profile-web").put("private", true);
      List<String> names = new ArrayList<>();
      Map<String, com.deepseekharness.app.util.NativePluginPlan.Marker> markers = new HashMap<>();
      for (String[] plugin : plugins) {
        names.add(plugin[0]);
        File marker = new File(modules, plugin[0] + ".disabled");
        boolean exists = marker.exists() && !Files.isSymbolicLink(marker.toPath());
        String content =
            exists && marker.length() <= 64
                ? new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8)
                : "";
        markers.put(
            plugin[0], com.deepseekharness.app.util.NativePluginPlan.marker(exists, content));
      }
      JSONObject dsh = manifest.optJSONObject("dsh");
      if (dsh == null) manifest.put("dsh", dsh = new JSONObject());
      JSONObject section = dsh.optJSONObject("profile");
      if (section == null) dsh.put("profile", section = new JSONObject());
      List<String> current = new ArrayList<>();
      JSONArray bundles = section.optJSONArray("bundles");
      if (bundles != null)
        for (int i = 0; i < bundles.length(); i++) current.add(bundles.getString(i));
      com.deepseekharness.app.util.NativePluginPlan plan =
          com.deepseekharness.app.util.NativePluginPlan.of(
              current, names, markers, readRegistered(new File(dshHome, REGISTERED)));
      JSONObject dependencies = manifest.optJSONObject("dependencies");
      if (dependencies == null) manifest.put("dependencies", dependencies = new JSONObject());
      List<String> enabled = new ArrayList<>();
      for (String[] plugin : plugins) {
        File target = new File(install, plugin[1]).getCanonicalFile();
        dependencies.put(plugin[0], "link:" + target.getPath());
        File link = new File(modules, plugin[0]);
        if (plan.link.contains(plugin[0])) relink(link, target);
        else if (plan.unlink.contains(plugin[0]) && Files.isSymbolicLink(link.toPath()))
          link.delete();
        if (plan.bundles.contains(plugin[0])) enabled.add(plugin[0]);
      }
      section.put("bundles", new JSONArray(plan.bundles));
      section.put("patchReload", "startup");
      atomicWrite(manifestFile, manifest.toString(2) + "\n");
      atomicWrite(new File(dshHome, REGISTERED), new JSONArray(plan.registered).toString() + "\n");
      return enabled;
    } catch (JSONException bad) {
      // 不覆盖用户可能手改过的 package.json；交给 DSH 自己报格式错误。
      throw new IOException("NATIVE_PROFILE_MANIFEST", bad);
    }
  }

  /** 指向本 APK 的插件目录；实体目录（用户自己放的）不碰，跳过。 */
  private static void relink(File link, File target) throws IOException {
    java.nio.file.Path path = link.toPath();
    if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
      return;
    String relative =
        link.getParentFile().getCanonicalFile().toPath().relativize(target.toPath()).toString();
    if (Files.isSymbolicLink(path) && Files.readSymbolicLink(path).toString().equals(relative))
      return;
    File temp = new File(link.getParentFile(), "." + link.getName() + ".dsha-link");
    temp.delete();
    try {
      android.system.Os.symlink(relative, temp.getPath());
      android.system.Os.rename(temp.getPath(), link.getPath());
    } catch (android.system.ErrnoException failure) {
      temp.delete();
      throw new IOException("NATIVE_PLUGIN_LINK", failure);
    }
  }

  /** DSH 的 package.json.lock：独占创建写 pid；持有者进程已不存在时接管。 */
  private static void claim(File lock) throws IOException {
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        Files.write(
            lock.toPath(),
            (android.os.Process.myPid() + "\n").getBytes(StandardCharsets.US_ASCII),
            java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE);
        return;
      } catch (java.nio.file.FileAlreadyExistsException held) {
        int pid =
            com.deepseekharness.app.util.WebProcSel.parsePid(
                new String(Files.readAllBytes(lock.toPath()), StandardCharsets.US_ASCII));
        if (pid > 0 && new File("/proc/" + pid).exists())
          throw new IOException("NATIVE_PROFILE_LOCKED");
        lock.delete();
      }
    }
    throw new IOException("NATIVE_PROFILE_LOCKED");
  }

  private static Set<String> readRegistered(File file) {
    Set<String> out = new LinkedHashSet<>();
    try {
      JSONArray rows =
          new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
      for (int i = 0; i < rows.length(); i++) out.add(rows.getString(i));
    } catch (IOException | JSONException missing) {
      // 没有记录：视为首次登记。
    }
    return out;
  }

  private static void writeIfMissing(File file, String content) throws IOException {
    if (!file.exists()) atomicWrite(file, content);
  }

  private static void atomicWrite(File file, String content) throws IOException {
    File temp = new File(file.getParentFile(), "." + file.getName() + ".tmp");
    try (FileOutputStream out = new FileOutputStream(temp)) {
      out.write(content.getBytes(StandardCharsets.UTF_8));
      out.getFD().sync();
    }
    if (!temp.renameTo(file)) {
      temp.delete();
      throw new IOException("NATIVE_PROFILE_WRITE");
    }
  }
}
