package com.deepseekharness.app.runtime;

import java.io.IOException;
import java.util.Map;

/**
 * 一个能拉起 DSH Web 主进程的运行环境。
 *
 * <p>这是 Web 启动链与具体运行环境之间唯一的执行接缝：调用方只交出已组装好的启动命令和环境变量，
 * 不关心命令是进入 Ubuntu 容器执行，还是由 Android 原生运行环境执行。端口选择、鉴权链接解析、
 * 停止证据与诊断仍由调用方负责。
 *
 * <p>实现不得在失败时改用另一个运行环境；容器内 proroot → proot 的兼容重试仍只由
 * {@link com.deepseekharness.app.util.WebRuntimeFallback} 在容器内部决定。
 */
public interface DshEngine {
  /** 稳定标识，写进诊断；与 {@link com.deepseekharness.app.util.DshEngineChoice} 的取值一致。 */
  String id();

  /** 本次实际使用的具体实现名，例如 {@code proot} / {@code proroot}，用于"本次实际运行方式"。 */
  String runtimeName();

  /** 启动 Web 主进程；返回的进程由调用方读取输出并负责回收。 */
  Process launch(String command, Map<String, String> environment) throws IOException;
}
