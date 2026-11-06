package com.deepseekharness.app.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 固定容量的采样环形缓冲：满了丢最旧，按时间（旧→新）导出。
 *
 * <p>采样线程写、导出线程读，全部经 synchronized；单次写入只覆盖一个槽位，不扩容、不复制历史。默认容量 60（1 Hz 下约 60 s）。
 */
public final class ProbeRingBuffer {
  public static final int DEFAULT_CAPACITY = 60;

  private final ProbeSample[] items;
  private int size;
  private int next;

  public ProbeRingBuffer() {
    this(DEFAULT_CAPACITY);
  }

  public ProbeRingBuffer(int capacity) {
    if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
    items = new ProbeSample[capacity];
  }

  public synchronized void add(ProbeSample sample) {
    if (sample == null) throw new NullPointerException("sample");
    items[next] = sample;
    next = (next + 1) % items.length;
    if (size < items.length) size++;
  }

  public synchronized int size() {
    return size;
  }

  public synchronized int capacity() {
    return items.length;
  }

  /** 返回从旧到新的副本，调用方可安全遍历。 */
  public synchronized List<ProbeSample> snapshot() {
    List<ProbeSample> result = new ArrayList<>(size);
    int start = (next - size + items.length) % items.length;
    for (int i = 0; i < size; i++) result.add(items[(start + i) % items.length]);
    return result;
  }

  public synchronized void clear() {
    Arrays.fill(items, null);
    size = 0;
    next = 0;
  }
}
