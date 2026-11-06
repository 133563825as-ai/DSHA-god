package com.deepseekharness.app.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 固定容量的事件窗口：满了丢最旧。区间的两端都含（{@code [fromTMs, toTMs]}），用于“长帧前后 2 s”这类取窗。
 *
 * <p>线程安全（synchronized）；事件量级远低于采样，默认容量 240。
 */
public final class ProbeEventLog {
  public static final int DEFAULT_CAPACITY = 240;

  private final ProbeEvent[] items;
  private int size;
  private int next;

  public ProbeEventLog() {
    this(DEFAULT_CAPACITY);
  }

  public ProbeEventLog(int capacity) {
    if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
    items = new ProbeEvent[capacity];
  }

  public synchronized void add(ProbeEvent event) {
    if (event == null) throw new NullPointerException("event");
    items[next] = event;
    next = (next + 1) % items.length;
    if (size < items.length) size++;
  }

  public synchronized int size() {
    return size;
  }

  public synchronized int capacity() {
    return items.length;
  }

  /** 从旧到新的全量副本。 */
  public synchronized List<ProbeEvent> snapshot() {
    List<ProbeEvent> result = new ArrayList<>(size);
    int start = (next - size + items.length) % items.length;
    for (int i = 0; i < size; i++) result.add(items[(start + i) % items.length]);
    return result;
  }

  /** 只返回 tMs 落在闭区间内的事件；顺序仍是从旧到新。 */
  public synchronized List<ProbeEvent> window(long fromTMs, long toTMs) {
    List<ProbeEvent> result = new ArrayList<>();
    if (toTMs < fromTMs) return result;
    for (ProbeEvent event : snapshot()) {
      if (event.tMs < fromTMs || event.tMs > toTMs) continue;
      result.add(event);
    }
    return result;
  }

  public synchronized void clear() {
    Arrays.fill(items, null);
    size = 0;
    next = 0;
  }
}
