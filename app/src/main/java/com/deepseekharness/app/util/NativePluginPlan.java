package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 原生运行环境 web profile 的内置插件登记计划（纯逻辑）。
 *
 * <p>与容器 register-builtin-plugins.py 同一语义：
 *
 * <ul>
 *   <li>{@code node_modules/<名>.disabled} 存在即禁用：不进 bundles。空文件（原生页禁用）摘掉链接；内容为 Web 禁用标记时保留链接，
 *       Web 插件页"开启"需要读它；
 *   <li>只追加，不删除用户自己加的 bundle，也不改它们的顺序；
 *   <li>登记过的名字之后被用户从 bundles 里移走，视为用户关闭，不再自动加回；只有第一次出现的名字自动启用。
 * </ul>
 */
public final class NativePluginPlan {
  /** 官方基础 bundle，按此顺序排在最前。 */
  public static final List<String> OFFICIAL =
      List.of("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app");

  public static final String WEB_DISABLED_MARKER = "DSHA_WEB_BUILTIN_DISABLED_V1\n";

  /** 某个内置插件的禁用标记状态。 */
  public enum Marker {
    NONE,
    /** 空文件或其他内容：原生页禁用，摘掉链接。 */
    NATIVE_DISABLED,
    /** Web 页禁用：保留链接，不进 bundles。 */
    WEB_DISABLED
  }

  public final List<String> bundles;

  /** 需要指向本 APK 插件目录的名字。 */
  public final Set<String> link;

  /** 需要摘掉链接的名字。 */
  public final Set<String> unlink;

  /** 写回"已登记过"的名字集合。 */
  public final Set<String> registered;

  private NativePluginPlan(
      List<String> bundles, Set<String> link, Set<String> unlink, Set<String> registered) {
    this.bundles = Collections.unmodifiableList(bundles);
    this.link = Collections.unmodifiableSet(link);
    this.unlink = Collections.unmodifiableSet(unlink);
    this.registered = Collections.unmodifiableSet(registered);
  }

  /**
   * @param current profile 现有的 bundles（新 profile 传空列表）
   * @param builtins 本 APK 带的内置插件名（顺序即追加顺序）
   * @param markers 各内置插件的禁用标记；缺省视为 NONE
   * @param previouslyRegistered 之前登记过的名字
   */
  public static NativePluginPlan of(
      List<String> current,
      List<String> builtins,
      Map<String, Marker> markers,
      Set<String> previouslyRegistered) {
    Set<String> builtinSet = new LinkedHashSet<>(builtins);
    Set<String> enabled = new LinkedHashSet<>();
    Set<String> link = new LinkedHashSet<>();
    Set<String> unlink = new LinkedHashSet<>();
    for (String name : builtins) {
      Marker marker = markers.getOrDefault(name, Marker.NONE);
      boolean fresh = !previouslyRegistered.contains(name);
      if (marker == Marker.NONE && (fresh || current.contains(name))) enabled.add(name);
      if (marker == Marker.NATIVE_DISABLED) unlink.add(name);
      else link.add(name);
    }
    List<String> bundles = new ArrayList<>(OFFICIAL);
    for (String bundle : current) {
      if (OFFICIAL.contains(bundle) || bundles.contains(bundle)) continue;
      // 内置插件只按 enabled 决定是否保留；用户自己的 bundle 原样保留。
      if (builtinSet.contains(bundle) && !enabled.contains(bundle)) continue;
      bundles.add(bundle);
    }
    for (String name : enabled) if (!bundles.contains(name)) bundles.add(name);
    Set<String> registered = new LinkedHashSet<>(previouslyRegistered);
    registered.addAll(builtins);
    return new NativePluginPlan(bundles, link, unlink, registered);
  }

  /** 按文件内容判断标记类型（容器同一规则：只有完全等于 Web 标记才算 Web 禁用）。 */
  public static Marker marker(boolean exists, String content) {
    if (!exists) return Marker.NONE;
    return WEB_DISABLED_MARKER.equals(content) ? Marker.WEB_DISABLED : Marker.NATIVE_DISABLED;
  }
}
