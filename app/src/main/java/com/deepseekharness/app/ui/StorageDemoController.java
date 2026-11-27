package com.deepseekharness.app.ui;

import com.deepseekharness.app.storage.model.StorageModel;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Synthetic-only main-thread owner. No context, filesystem, permission or provider capability. */
final class StorageDemoController {
  interface Delay {
    boolean later(Runnable work);
  }

  enum Sample {
    PARTIAL,
    EMPTY,
    UNKNOWN
  }

  enum Code {
    IDLE,
    PARTIAL,
    EMPTY,
    UNKNOWN,
    NOT_STARTED,
    LOADING,
    WAITING,
    CANCELLED
  }

  static final class State {
    final Code code;
    final String status;
    final StorageModel.Report report;

    State(Code code, String status, StorageModel.Report report) {
      this.code = code;
      this.status = status;
      this.report = report;
    }
  }

  private final StorageModel.Slot slot = new StorageModel.Slot();
  private final Delay delay;
  private int reads;

  StorageDemoController(Delay delay) {
    this.delay = delay;
  }

  int reads() {
    return reads;
  }

  StorageModel.Phase phase() {
    return slot.phase();
  }

  Page open(Consumer<State> render) {
    return new Page(render);
  }

  final class Page {
    private Consumer<State> render;
    private StorageModel.Token token;
    private State state = new State(Code.IDLE, "未请求 · 零业务读取", null);

    Page(Consumer<State> render) {
      this.render = render;
      publish(state);
    }

    private void publish(State value) {
      state = value;
      if (render != null) render.accept(value);
    }

    boolean request(Sample sample) {
      if (render == null) return false;
      var accepted =
          slot.request(
              true,
              t -> {
                token = t;
                // This runnable is the entire fake worker. Retiring the lease does not remove its
                // exit ack.
                boolean queued =
                    delay.later(
                        () -> {
                          try {
                            if (slot.progress(t)) {
                              reads++;
                              StorageModel.Report report = fixture(sample);
                              if (slot.result(t))
                                publish(
                                    new State(
                                        sample == Sample.EMPTY
                                            ? Code.EMPTY
                                            : sample == Sample.UNKNOWN
                                                ? Code.UNKNOWN
                                                : Code.PARTIAL,
                                        sample == Sample.EMPTY
                                            ? "空结果 · 合成样例"
                                            : sample == Sample.UNKNOWN
                                                ? "未知 · 合成样例"
                                                : "部分结果 · 合成样例",
                                        report));
                            }
                          } finally {
                            slot.workerExited(t);
                          }
                        });
                if (!queued) {
                  // Handler rejected dispatch: no worker exists, so this is a trusted no-start
                  // exit.
                  slot.workerExited(t);
                  publish(new State(Code.NOT_STARTED, "合成任务未启动 · 请重试", null));
                } else publish(new State(Code.LOADING, "加载中 · 仅合成数据", null));
              });
      if (accepted.isEmpty()) publish(new State(Code.WAITING, "等待旧合成任务可信退出 · 请稍后重试", null));
      return accepted.isPresent();
    }

    void cancel() {
      if (slot.retire(token, () -> {})) publish(new State(Code.CANCELLED, "已取消 · 等待合成任务退出", null));
    }

    void close() {
      render = null;
      slot.retire(token, () -> {});
    }
  }

  private static StorageModel.Report fixture(Sample sample) {
    StorageModel.Scope scope =
        new StorageModel.Scope(
            Set.of("demo"), Set.of("synthetic"), Set.of("synthetic-volume"), 1, 16);
    List<StorageModel.Observation> values =
        sample == Sample.EMPTY
            ? List.of()
            : List.of(
                new StorageModel.PrivateNode(
                    "demo",
                    1,
                    new StorageModel.Inode(1, 1, StorageModel.Type.FILE),
                    sample == Sample.UNKNOWN ? null : 1536L,
                    sample == Sample.UNKNOWN ? null : 8L),
                new StorageModel.SafNode("synthetic", "example", null),
                new StorageModel.Volume("synthetic-volume", null),
                new StorageModel.Gap(StorageModel.Problem.BUDGET));
    return StorageModel.inspect(scope, values);
  }
}
