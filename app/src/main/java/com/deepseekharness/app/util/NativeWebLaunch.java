package com.deepseekharness.app.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Android 原生运行环境的 dsh web 启动参数、环境与进程识别（纯逻辑）。
 *
 * <p>容器里由一段 shell 写 pid、查停止标记再 exec node；原生没有 shell，宿主直接用 ProcessBuilder
 * 启动 nativeLibraryDir 里的 node。所以：
 *
 * <ul>
 *   <li>argv 固定为 {@code <node> --expose-internals <bin.js> web --no-open --host 127.0.0.1 --port N}，
 *       不经 shell，参数里没有任何凭据；
 *   <li>识别"这是我们启动的原生 Web"只接受与上面<b>完全相同</b>的 argv（node 与 bin.js 都比完整绝对路径），
 *       不比文件名、不做包含匹配，避免把 agent 自己在终端里跑的 node 或别的进程当成目标；
 *   <li>环境只放这里列出的键；继承来的 NODE_OPTIONS / LD_PRELOAD 由调用方清掉。
 * </ul>
 *
 * <p>第一版只支持普通 {@code web} profile；救援/试运行 profile 仍只走容器。
 */
public final class NativeWebLaunch {
  private NativeWebLaunch() {}

  /** 原生运行环境的宿主路径，全部是规范化的绝对路径。 */
  public static final class Paths {
    public final String node;
    public final String entry;
    public final String home;
    public final String dshHome;
    public final String bridgeState;
    public final String tmp;
    public final String libraries;
    public final String certificate;
    public final String ripgrep;
    public final String workspace;

    /** App 层接口调用器（容器里用 curl），由原生 node 执行。 */
    public final String appTool;

    public Paths(
        String node,
        String entry,
        String home,
        String dshHome,
        String bridgeState,
        String tmp,
        String libraries,
        String certificate,
        String ripgrep,
        String workspace) {
      this(
          node,
          entry,
          home,
          dshHome,
          bridgeState,
          tmp,
          libraries,
          certificate,
          ripgrep,
          workspace,
          entry);
    }

    public Paths(
        String node,
        String entry,
        String home,
        String dshHome,
        String bridgeState,
        String tmp,
        String libraries,
        String certificate,
        String ripgrep,
        String workspace,
        String appTool) {
      this.appTool = absolute(appTool);
      this.node = absolute(node);
      this.entry = absolute(entry);
      this.home = absolute(home);
      this.dshHome = absolute(dshHome);
      this.bridgeState = absolute(bridgeState);
      this.tmp = absolute(tmp);
      this.libraries = absolute(libraries);
      this.certificate = absolute(certificate);
      this.ripgrep = absolute(ripgrep);
      this.workspace = absolute(workspace);
    }

    private static String absolute(String value) {
      if (value == null
          || !value.startsWith("/")
          || value.length() > 1024
          || value.indexOf('\0') >= 0
          || value.contains("//")
          || value.contains("/./")
          || value.contains("/../")
          || value.endsWith("/.")
          || value.endsWith("/..")
          || (value.length() > 1 && value.endsWith("/")))
        throw new IllegalArgumentException("NATIVE_PATH");
      return value;
    }
  }

  /** 唯一的 Web 启动 argv。 */
  public static List<String> argv(Paths paths, int listenPort) {
    if (listenPort < 0 || listenPort > 65535) throw new IllegalArgumentException("NATIVE_PORT");
    return List.of(
        paths.node,
        "--expose-internals",
        paths.entry,
        "web",
        "--no-open",
        "--host",
        "127.0.0.1",
        "--port",
        Integer.toString(listenPort));
  }

  /**
   * 启动环境：与容器 WebLaunchCommand / RuntimeTools 同义的键，路径换成宿主路径。
   *
   * @param launch 调用方已组装的启动事实（DSH_TASK_*、DSHA_WEB_GENERATION、可选 DEEPSEEK_API_KEY）
   */
  public static Map<String, String> environment(
      Paths paths,
      Map<String, String> launch,
      boolean confirmShell,
      String language,
      String dnsMode,
      long generation) {
    if (generation < 1) throw new IllegalArgumentException("NATIVE_GENERATION");
    Map<String, String> env = new LinkedHashMap<>();
    for (Map.Entry<String, String> item : launch.entrySet()) {
      String key = item.getKey();
      // 预载与动态库注入只能由本类决定，调用方传入的同名键一律不收。
      if (key.equals("NODE_OPTIONS") || key.equals("LD_PRELOAD") || key.equals("LD_LIBRARY_PATH"))
        continue;
      env.put(key, item.getValue());
    }
    env.put("HOME", paths.home);
    env.put("TMPDIR", paths.tmp);
    env.put("PATH", "/system/bin:/system/xbin");
    env.put("LD_LIBRARY_PATH", paths.libraries);
    env.put("DSH_HOME", paths.dshHome);
    env.put("DSHA_BRIDGE_STATE_DIR", paths.bridgeState);
    env.put("DSHA_RIPGREP_PATH", paths.ripgrep);
    env.put("DSHA_WORKSPACE_DOCUMENTS", paths.workspace);
    // 设备能力引导里的写法："$DSHA_NODE" "$DSHA_APP" /app/<路径> 键=值
    env.put("DSHA_NODE", paths.node);
    env.put("DSHA_APP", paths.appTool);
    env.put("DSHA_ACTIVITY_FILE", paths.home + "/.dsha-web-activity.json");
    env.put("DSH_PERMISSION_MODE", PermissionMode.DEFAULT);
    env.put("DSH_CONFIRM", confirmShell ? "1" : "0");
    env.put("BROWSER", "true");
    env.put("DSHA_UI_LANGUAGE", language == null ? "" : language);
    env.put("DSHA_STARTUP_PROFILE", "web");
    env.put("DSHA_WEB_GENERATION", Long.toString(generation));
    env.put("DSHA_ANDROID_RUNTIME", "1");
    env.put("DSHA_DSH_ENGINE", DshEngineChoice.NATIVE);
    if (dnsMode != null && !dnsMode.isEmpty()) env.put("DSHA_DNS_MODE", dnsMode);
    for (String key :
        new String[] {
          "SSL_CERT_FILE",
          "NODE_EXTRA_CA_CERTS",
          "REQUESTS_CA_BUNDLE",
          "CURL_CA_BUNDLE",
          "GIT_SSL_CAINFO",
          "npm_config_cafile"
        }) env.put(key, paths.certificate);
    return Collections.unmodifiableMap(env);
  }

  /**
   * /proc/&lt;pid&gt;/cmdline 是否恰好是本运行环境启动的 Web。
   *
   * <p>只认 {@link #argv} 的形状：node、bin.js 必须与登记的完整路径相等，端口为十进制且在范围内。
   */
  public static boolean matches(String cmdline, String node, String entry) {
    if (cmdline == null || cmdline.isEmpty() || node == null || entry == null) return false;
    // /proc cmdline 以 NUL 结尾分隔；只接受 NUL 分隔，空白分隔可能来自 shell 文本。
    if (cmdline.indexOf('\0') < 0) return false;
    String text = cmdline.endsWith("\0") ? cmdline.substring(0, cmdline.length() - 1) : cmdline;
    String[] args = text.split("\0", -1);
    if (args.length != 9) return false;
    if (!args[0].equals(node)
        || !args[1].equals("--expose-internals")
        || !args[2].equals(entry)
        || !args[3].equals("web")
        || !args[4].equals("--no-open")
        || !args[5].equals("--host")
        || !args[6].equals("127.0.0.1")
        || !args[7].equals("--port")) return false;
    String port = args[8];
    if (!port.matches("0|[1-9][0-9]{0,4}")) return false;
    return Integer.parseInt(port) <= 65535;
  }
}
