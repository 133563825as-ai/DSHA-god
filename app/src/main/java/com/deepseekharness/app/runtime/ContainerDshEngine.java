package com.deepseekharness.app.runtime;

import com.deepseekharness.app.util.DshEngineChoice;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Ubuntu 容器运行环境：原样转交给 {@link ProotBootstrap#execRootfs(String, Map)}。
 *
 * <p>不改变命令、环境变量、运行时选择或兼容重试；proot / proroot 仍由传入的 {@link ProotBootstrap}
 * 决定。只把"进入 rootfs 执行"收到 {@link DshEngine} 接口后面。
 */
public final class ContainerDshEngine implements DshEngine {
  /** 与 {@link ProotBootstrap#execRootfs(String, Map)} 签名相同，便于不依赖 Android 的单测。 */
  public interface RootfsExec {
    Process exec(String command, Map<String, String> environment) throws IOException;
  }

  private final RootfsExec exec;
  private final Supplier<String> runtimeName;

  ContainerDshEngine(RootfsExec exec, Supplier<String> runtimeName) {
    this.exec = Objects.requireNonNull(exec);
    this.runtimeName = Objects.requireNonNull(runtimeName);
  }

  /** 包住本次启动所用的 bootstrap（普通或兼容重试实例）。 */
  public static ContainerDshEngine of(ProotBootstrap boot) {
    Objects.requireNonNull(boot);
    return new ContainerDshEngine(boot::execRootfs, () -> boot.runtime().id());
  }

  @Override
  public String id() {
    return DshEngineChoice.UBUNTU;
  }

  @Override
  public String runtimeName() {
    return runtimeName.get();
  }

  @Override
  public Process launch(String command, Map<String, String> environment) throws IOException {
    return exec.exec(command, environment);
  }
}
