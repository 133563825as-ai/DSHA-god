package com.deepseekharness.app.util;

/**
 * 用户选择的 DSH 运行环境：容器（Ubuntu，内部再分 proot / proroot）或 Android 原生。
 *
 * <p>只解析偏好里的文本，不判断该环境是否已安装或可用。缺省、空串和不认识的值一律回到容器，
 * 因为容器是当前唯一已实现的环境；不认识的值另外标记，便于诊断而不是静默吞掉。
 *
 * <p>纯逻辑，不 import Android API。
 */
public final class DshEngineChoice {
  /** 偏好里保存的稳定取值；写入后不得改名。 */
  public static final String UBUNTU = "ubuntu";

  public static final String NATIVE = "native";

  /** 两类运行环境。 */
  public enum Kind {
    /** Ubuntu 容器（proot / proroot），由 {@code container_runtime} 决定具体实现。 */
    CONTAINER,
    /** Android 原生运行环境。 */
    NATIVE
  }

  public final Kind kind;

  /** 偏好值不为空且不是已知取值时为 true；此时 {@link #kind} 为安全默认的 {@code CONTAINER}。 */
  public final boolean unrecognized;

  private DshEngineChoice(Kind kind, boolean unrecognized) {
    this.kind = kind;
    this.unrecognized = unrecognized;
  }

  /** 解析偏好值；大小写和首尾空白按原样比较，不做宽松匹配。 */
  public static DshEngineChoice parse(String stored) {
    if (stored == null || stored.isEmpty() || UBUNTU.equals(stored))
      return new DshEngineChoice(Kind.CONTAINER, false);
    if (NATIVE.equals(stored)) return new DshEngineChoice(Kind.NATIVE, false);
    return new DshEngineChoice(Kind.CONTAINER, true);
  }

  /** 写回偏好时使用的稳定取值。 */
  public static String stored(Kind kind) {
    return kind == Kind.NATIVE ? NATIVE : UBUNTU;
  }

  public boolean isContainer() {
    return kind == Kind.CONTAINER;
  }
}
