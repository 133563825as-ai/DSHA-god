package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 运行环境能做什么：每项能力只有"支持 / 不支持 / 未知"三态，不用布尔。
 *
 * <p>"未知"表示还没有证据（例如原生运行环境尚未实测），不等于不支持，也绝不当成支持。插件、模块或
 * 切换前的对照表按 {@link #check} 的结论处理：有一项不支持即阻止；没有不支持但有未知时由调用方明确
 * 告知用户，不能静默放行。
 *
 * <p>纯逻辑，不 import Android API。
 */
public final class EngineCapabilities {
  /** 已定义的能力。{@link #id} 是写进清单与诊断的稳定标识，发布后不得改名。 */
  public enum Capability {
    /** 完整 Linux 用户空间：apt、glibc 二进制、Linux 路径视图。 */
    LINUX_USERSPACE("linux.userspace"),
    /** 依赖 glibc 原生扩展（.node 等）的插件。 */
    PLUGINS_GLIBC_NATIVE("plugins.glibc-native"),
    /** 只含 JavaScript、不带原生扩展的插件。 */
    PLUGINS_PURE_JS("plugins.pure-js"),
    /** 终端里的 Linux shell。 */
    TERMINAL_LINUX("terminal.linux"),
    /** ADB 无线配对（随包 ADB wheels 只有 glibc 版）。 */
    ADB_PAIRING("adb.pairing"),
    /** 宿主侧设备桥（读屏、点按、通知、虚拟屏等）可被该运行环境里的 DSH 访问。 */
    DEVICE_BRIDGE("device.bridge");

    public final String id;

    Capability(String id) {
      this.id = id;
    }

    /** 精确匹配，不做大小写或空白宽松处理。 */
    public static Optional<Capability> fromId(String id) {
      if (id == null) return Optional.empty();
      for (Capability capability : values())
        if (capability.id.equals(id)) return Optional.of(capability);
      return Optional.empty();
    }
  }

  public enum Support {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN
  }

  /** 需求判定的结论。 */
  public enum Verdict {
    /** 全部需求都支持。 */
    ALLOWED,
    /** 至少一项明确不支持。 */
    BLOCKED,
    /** 没有不支持，但有未知或不认识的需求；调用方必须明确告知，不能当成允许。 */
    UNDETERMINED
  }

  /** 一次需求判定的结果；列表按需求出现顺序去重。 */
  public static final class Check {
    public final Verdict verdict;
    public final List<String> unsupported;
    public final List<String> unknown;

    private Check(Verdict verdict, List<String> unsupported, List<String> unknown) {
      this.verdict = verdict;
      this.unsupported = Collections.unmodifiableList(unsupported);
      this.unknown = Collections.unmodifiableList(unknown);
    }
  }

  private final Map<Capability, Support> values;

  private EngineCapabilities(Map<Capability, Support> values) {
    this.values = values;
  }

  /** 未列出的能力一律为 {@link Support#UNKNOWN}。 */
  public static EngineCapabilities of(Map<Capability, Support> values) {
    Map<Capability, Support> copy = new EnumMap<>(Capability.class);
    for (Map.Entry<Capability, Support> entry : values.entrySet())
      if (entry.getKey() != null && entry.getValue() != null)
        copy.put(entry.getKey(), entry.getValue());
    return new EngineCapabilities(Collections.unmodifiableMap(copy));
  }

  /** 容器运行环境：现有 DSHA 的全部能力都在这里实现过。 */
  public static EngineCapabilities container() {
    Map<Capability, Support> values = new EnumMap<>(Capability.class);
    for (Capability capability : Capability.values()) values.put(capability, Support.SUPPORTED);
    return of(values);
  }

  /**
   * 原生运行环境的暂定能力：只写定义上就成立的两项（没有 Linux 用户空间，因此跑不了 glibc 原生扩展）；
   * 其余等实测后再填，现在一律未知。
   */
  public static EngineCapabilities nativeProvisional() {
    Map<Capability, Support> values = new EnumMap<>(Capability.class);
    values.put(Capability.LINUX_USERSPACE, Support.UNSUPPORTED);
    values.put(Capability.PLUGINS_GLIBC_NATIVE, Support.UNSUPPORTED);
    return of(values);
  }

  public Support support(Capability capability) {
    Support value = values.get(capability);
    return value == null ? Support.UNKNOWN : value;
  }

  /** 按能力标识判定一组需求；不认识的标识计入未知。 */
  public Check check(Collection<String> requirements) {
    Set<String> unsupported = new LinkedHashSet<>();
    Set<String> unknown = new LinkedHashSet<>();
    for (String requirement : requirements) {
      Optional<Capability> capability = Capability.fromId(requirement);
      Support value = capability.map(this::support).orElse(Support.UNKNOWN);
      if (value == Support.UNSUPPORTED) unsupported.add(requirement);
      else if (value == Support.UNKNOWN) unknown.add(String.valueOf(requirement));
    }
    Verdict verdict =
        !unsupported.isEmpty()
            ? Verdict.BLOCKED
            : !unknown.isEmpty() ? Verdict.UNDETERMINED : Verdict.ALLOWED;
    return new Check(verdict, new ArrayList<>(unsupported), new ArrayList<>(unknown));
  }

  /** 从 {@code from} 切到 {@code to} 后不再确定可用的能力：原来支持、之后不支持或未知。 */
  public static List<Capability> lostWhenSwitching(EngineCapabilities from, EngineCapabilities to) {
    List<Capability> lost = new ArrayList<>();
    for (Capability capability : Capability.values())
      if (from.support(capability) == Support.SUPPORTED
          && to.support(capability) != Support.SUPPORTED) lost.add(capability);
    return lost;
  }
}
