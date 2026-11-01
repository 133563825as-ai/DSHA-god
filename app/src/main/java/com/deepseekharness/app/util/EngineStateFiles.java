package com.deepseekharness.app.util;

import java.io.File;

/**
 * Web 停止证据文件（停止标记、pid、出生身份）在宿主上的位置规则。
 *
 * <p>这些文件由运行环境里的启动命令写入（见 {@link WebLaunchCommand}），由宿主读取、核验并删除。 每个运行环境提供一个"状态目录"，三份文件都在它下面，文件名固定。容器运行环境的状态目录就是
 * guest 的 {@code /root} 在宿主上的位置，即 {@code <rootfs>/root}，与 {@link WebProcSel} 的容器内路径
 * 一一对应。
 *
 * <p>纯逻辑，不 import Android API。
 */
public final class EngineStateFiles {
  /** 用户已停止标记的文件名。 */
  public static final String STOP_SENTINEL = ".dsha-stopped";

  /** Web 进程 pid 文件名。 */
  public static final String PID = ".dsha-web.pid";

  /** Web 进程出生身份文件名。 */
  public static final String IDENTITY = ".dsha-web.identity";

  /** 容器运行环境里状态目录相对 rootfs 根的路径（guest 的 {@code /root}）。 */
  static final String CONTAINER_STATE_RELATIVE = "root";

  private EngineStateFiles() {}

  /** 容器运行环境的状态目录：{@code <rootfs>/root}。 */
  public static File containerStateDirectory(File rootfs) {
    return new File(rootfs, CONTAINER_STATE_RELATIVE);
  }

  public static File stopSentinel(File stateDirectory) {
    return new File(stateDirectory, STOP_SENTINEL);
  }

  public static File pid(File stateDirectory) {
    return new File(stateDirectory, PID);
  }

  public static File identity(File stateDirectory) {
    return new File(stateDirectory, IDENTITY);
  }
}
