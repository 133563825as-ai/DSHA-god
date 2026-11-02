package com.deepseekharness.app.runtime;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Android 原生运行环境：宿主直接执行 nativeLibraryDir 里的 node，不经 proot、不经 shell。
 *
 * <p>容器里由启动 shell 完成的三件事在这里由宿主完成：启动前查停止标记、删旧出生身份；启动后把 node 的 pid
 * 写进状态目录。之后的出生身份、停止、扫描沿用容器同一套 {@link WebProcessManager} 规则（原生 argv 由
 * {@link com.deepseekharness.app.util.NativeWebLaunch#matches} 精确识别）。
 *
 * <p>失败就报错，绝不改用容器。
 */
public final class NativeDshEngine implements DshEngine {
  private final Context context;
  private final NativeRuntime runtime;

  public NativeDshEngine(Context context, NativeRuntime runtime) {
    this.context = context.getApplicationContext();
    this.runtime = java.util.Objects.requireNonNull(runtime);
  }

  @Override
  public String id() {
    return com.deepseekharness.app.util.DshEngineChoice.NATIVE;
  }

  /** 不能叫 proroot：{@link com.deepseekharness.app.util.WebRuntimeFallback} 只对 proroot 做兼容重试。 */
  @Override
  public String runtimeName() {
    return "native";
  }

  @Override
  public com.deepseekharness.app.util.EngineCapabilities capabilities() {
    return com.deepseekharness.app.util.EngineCapabilities.nativeProvisional();
  }

  @Override
  public File stateDirectory() {
    return NativeRuntime.stateDirectory(context);
  }

  /** 原生运行环境不执行 shell 命令文本；请用 {@link #launchWeb}。 */
  @Override
  public Process launch(String command, Map<String, String> environment) throws IOException {
    throw new IOException("NATIVE_ENGINE_SHELL_COMMAND_UNSUPPORTED");
  }

  /**
   * 启动原生 dsh web。
   *
   * @param argv {@link com.deepseekharness.app.util.NativeWebLaunch#argv} 的结果
   * @param environment {@link com.deepseekharness.app.util.NativeWebLaunch#environment} 的结果
   */
  public Process launchWeb(
      List<String> argv, Map<String, String> environment, File workingDirectory)
      throws IOException {
    File state = stateDirectory();
    if (!state.isDirectory() && !state.mkdirs()) throw new IOException("NATIVE_STATE_DIRECTORY");
    // 与容器启动 shell 的 `[ ! -e sentinel ] || exit 0` 同义：用户已停止时不启动。
    if (com.deepseekharness.app.util.EngineStateFiles.stopSentinel(state).exists())
      throw new IOException("NATIVE_STOP_REQUESTED");
    File identity = com.deepseekharness.app.util.EngineStateFiles.identity(state);
    if (identity.exists() && !identity.delete()) throw new IOException("NATIVE_IDENTITY_STALE");
    ProcessBuilder builder = new ProcessBuilder(argv).directory(workingDirectory);
    builder.redirectErrorStream(true);
    Map<String, String> env = builder.environment();
    // 不继承宿主进程的环境（例如 App 进程里可能存在的 NODE_OPTIONS / LD_PRELOAD）。
    env.clear();
    env.putAll(environment);
    for (String key :
        new String[] {
          "ANDROID_DATA",
          "ANDROID_ROOT",
          "ANDROID_ART_ROOT",
          "ANDROID_I18N_ROOT",
          "ANDROID_TZDATA_ROOT"
        }) {
      String value = System.getenv(key);
      if (value != null) env.put(key, value);
    }
    Process process = builder.start();
    int pid =
        com.deepseekharness.app.util.ProcessIdentity.androidPid(
            process.getClass().getName(), process.toString());
    if (pid <= 1) {
      process.destroy();
      throw new IOException("NATIVE_PID_UNKNOWN");
    }
    File pidFile = com.deepseekharness.app.util.EngineStateFiles.pid(state);
    File temp = new File(state, pidFile.getName() + ".tmp");
    try (OutputStream out = new FileOutputStream(temp)) {
      out.write((pid + "\n").getBytes(StandardCharsets.US_ASCII));
    }
    if (!temp.renameTo(pidFile)) {
      process.destroy();
      throw new IOException("NATIVE_PID_WRITE");
    }
    return process;
  }
}
