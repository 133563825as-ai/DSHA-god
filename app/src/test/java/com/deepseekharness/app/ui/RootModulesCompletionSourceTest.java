package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Test;

/**
 * UI-1 的离线源码投影：执行生产方法原文，不启动 Android、真实线程、RootShell 或授权。
 * render 只记录是否被调用；不验证 JSON/文案/真实生命周期调度，这些不属于本矩阵的证据。
 */
public final class RootModulesCompletionSourceTest {
  private static final List<String> METHODS =
      List.of(
          "completeRead",
          "applyReadResult",
          "acceptsResult",
          "onResume",
          "onPause",
          "onDestroyView",
          "showAuthorizationChanged");

  @Test
  public void productionCompletionMatrixRejectsBothRegressions() throws Exception {
    Path source = sourcePath();
    Map<String, String> methods = extractMethods(source);
    String complete = methods.get("completeRead");
    String configuredOutput = System.getProperty("root.completion.output");
    Path output =
        configuredOutput == null
            ? Files.createTempDirectory("next-root-completion-")
            : Path.of(configuredOutput).toAbsolutePath().normalize();
    Files.createDirectories(output);
    assertFalse("投影输出必须在仓库外", output.toRealPath().startsWith(repositoryRoot().toRealPath()));

    String baseline = runProjection(output, "baseline", methods);
    assertEquals("全部完成/生命周期/授权场景", "PASS:13", baseline);
    System.out.println("baseline " + baseline);

    Map<String, String> noRestore = new LinkedHashMap<>(methods);
    noRestore.put("completeRead", removeOnce(complete, "refresh.setEnabled(isResumed());"));
    String restoreFailure = runProjection(output, "no-restore", noRestore);
    assertTrue(
        "缺恢复必须行为失败而非编译失败: " + restoreFailure,
        restoreFailure.startsWith("FAIL:resume-before-completion:"));
    System.out.println("no-restore " + restoreFailure);

    Map<String, String> noOwner = new LinkedHashMap<>(methods);
    noOwner.put("completeRead", removeOnce(complete, "if (worker != completed) return;"));
    String ownerFailure = runProjection(output, "no-owner", noOwner);
    assertTrue(
        "缺owner必须行为失败而非编译失败: " + ownerFailure, ownerFailure.startsWith("FAIL:replacement-worker:"));
    System.out.println("no-owner " + ownerFailure);
  }

  private static Path sourcePath() {
    return repositoryRoot()
        .resolve("app/src/main/java/com/deepseekharness/app/ui/RootModulesFragment.java");
  }

  private static Path repositoryRoot() {
    Path root = Path.of(System.getProperty("root.completion.repo", ".")).toAbsolutePath();
    if (!Files.isDirectory(root.resolve("app")) && Files.isDirectory(root.resolve("src"))) {
      root = root.getParent();
    }
    return root;
  }

  private static Map<String, String> extractMethods(Path source) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull("需要完整 JDK，而不是 Android/仅 JRE", compiler);
    String text = Files.readString(source);
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
      JavacTask task =
          (JavacTask)
              compiler.getTask(
                  null,
                  files,
                  diagnostics,
                  List.of("-proc:none"),
                  null,
                  files.getJavaFileObjects(source.toFile()));
      CompilationUnitTree unit = task.parse().iterator().next();
      var positions = Trees.instance(task).getSourcePositions();
      Map<String, String> result = new LinkedHashMap<>();
      for (Tree declaration : unit.getTypeDecls()) {
        if (!(declaration instanceof ClassTree type)
            || !type.getSimpleName().contentEquals("RootModulesFragment")) continue;
        for (Tree member : type.getMembers()) {
          if (!(member instanceof MethodTree method)) continue;
          String name = method.getName().toString();
          if (!METHODS.contains(name)) continue;
          assertFalse("方法必须唯一: " + name, result.containsKey(name));
          int start = Math.toIntExact(positions.getStartPosition(unit, method));
          int end = Math.toIntExact(positions.getEndPosition(unit, method));
          result.put(name, text.substring(start, end));
        }
      }
      assertEquals("所有被测方法都来自真实源码 AST", METHODS.size(), result.size());
      assertTrue(
          diagnostics.getDiagnostics().toString(),
          diagnostics.getDiagnostics().stream()
              .noneMatch(item -> item.getKind() == javax.tools.Diagnostic.Kind.ERROR));
      return result;
    }
  }

  private static String removeOnce(String source, String statement) {
    int at = source.indexOf(statement);
    assertTrue("变异目标必须唯一且存在", at >= 0 && source.lastIndexOf(statement) == at);
    return source.substring(0, at) + source.substring(at + statement.length());
  }

  private static String runProjection(Path output, String variant, Map<String, String> methods)
      throws Exception {
    Path directory = output.resolve(variant);
    Files.createDirectories(directory);
    Path java = directory.resolve("CompletionProjection.java");
    // 生产方法仅拼接原文；fake 只提供依赖与可观察副作用，不复制完成/结果接收算法。
    Files.writeString(java, DEPENDENCIES + String.join("\n", methods.values()) + MATRIX);
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
      boolean compiled =
          compiler
              .getTask(
                  null,
                  files,
                  diagnostics,
                  List.of("-proc:none", "-d", directory.toString()),
                  null,
                  files.getJavaFileObjects(java.toFile()))
              .call();
      assertTrue("投影必须编译成功: " + diagnostics.getDiagnostics(), compiled);
    }
    try (URLClassLoader loader =
        new URLClassLoader(new java.net.URL[] {directory.toUri().toURL()}, null)) {
      return (String) loader.loadClass("CompletionProjection").getMethod("matrix").invoke(null);
    }
  }

  private static final String DEPENDENCIES =
      """
      class Lifecycle {
        boolean resumed = true, added = true;
        boolean isResumed() { return resumed; }
        boolean isAdded() { return added; }
        public void onResume() { resumed = true; }
        public void onPause() { resumed = false; }
        public void onDestroyView() {}
      }
      public final class CompletionProjection extends Lifecycle {
        static final class Thread {
          boolean alive = true, interrupted;
          boolean isAlive() { return alive; }
          void interrupt() { interrupted = true; }
        }
        static final class Widget {
          boolean enabled;
          int writes;
          String text = "";
          void setEnabled(boolean value) { enabled = value; writes++; }
          void setText(String value) { text = value; }
        }
        static final class DeviceAuthorization {
          static final DeviceAuthorization INSTANCE = new DeviceAuthorization();
          static final class Snapshot {}
          Snapshot active;
          static DeviceAuthorization shared() { return INSTANCE; }
          boolean current(Snapshot snapshot) { return snapshot == active; }
        }
        static final class UiText {
          static String choose(String zh, String en) { return en; }
        }
        Widget refresh = new Widget(), status = new Widget(), entries = new Widget();
        Thread worker = new Thread();
        long pageGeneration = 7;
        int renders;
        String rendered;
        void render(String reply) { renders++; rendered = reply; entries.setText(reply); }
      """;

  private static final String MATRIX =
      """
        static void check(boolean condition, String message) {
          if (!condition) throw new AssertionError(message);
        }
        static CompletionProjection page() {
          return new CompletionProjection();
        }
        static void untouched(CompletionProjection p, String message) {
          check(p.renders == 0 && p.entries.text.isEmpty(), message);
        }
        static void scenario(String name, Runnable action) {
          try { action.run(); }
          catch (AssertionError failure) { throw new AssertionError(name + ":" + failure.getMessage()); }
        }
        public static String matrix() {
          try {
            var auth = new DeviceAuthorization.Snapshot();
            DeviceAuthorization.INSTANCE.active = auth;
            scenario("resume-before-completion", () -> {
              var p = page(); var completed = p.worker;
              p.onPause(); check(completed.interrupted, "pause must interrupt");
              p.onResume(); check(!p.refresh.enabled, "alive worker must keep read disabled");
              completed.alive = false;
              p.completeRead(completed, 7, auth, "OLD");
              check(p.worker == null && p.refresh.enabled, "current completion must restore read");
              untouched(p, "retired reply must not render");
            });
            scenario("completion-before-resume", () -> {
              var p = page(); var completed = p.worker;
              p.onPause(); completed.alive = false;
              p.completeRead(completed, 7, auth, "OLD");
              check(p.worker == null && !p.refresh.enabled, "paused completion must release only slot");
              untouched(p, "paused reply must not render");
              p.onResume(); check(p.refresh.enabled, "resume must restore read after released slot");
              untouched(p, "resume must not replay reply");
            });
            scenario("replacement-worker", () -> {
              var p = page(); var old = p.worker; var replacement = new Thread();
              p.worker = replacement;
              p.completeRead(old, 7, auth, "OLD");
              check(p.worker == replacement, "old completion must not clear replacement slot");
              check(!p.refresh.enabled && p.refresh.writes == 0, "old completion must not touch button");
              untouched(p, "old owner must not render even matching generation");
            });
            scenario("destroyed-view", () -> {
              var p = page(); var completed = p.worker;
              var button = p.refresh; var status = p.status; var entries = p.entries;
              p.onDestroyView(); p.completeRead(completed, 7, auth, "OLD");
              check(p.worker == null, "own completion must release destroyed slot");
              check(button.writes == 0 && status.text.isEmpty() && entries.text.isEmpty(),
                  "destroyed widgets must remain untouched");
              check(p.renders == 0, "destroyed reply must not render");
            });
            scenario("destroy-recreate-new-worker", () -> {
              var p = page(); var old = p.worker; p.onDestroyView();
              p.refresh = new Widget(); p.status = new Widget(); p.entries = new Widget();
              var replacement = new Thread(); p.worker = replacement;
              p.completeRead(old, 7, auth, "OLD");
              check(p.worker == replacement && p.refresh.writes == 0,
                  "destroyed old owner must not clear or enable new view worker");
              untouched(p, "destroyed old reply must not render");
            });
            scenario("not-added", () -> {
              var p = page(); var completed = p.worker; p.added = false;
              p.completeRead(completed, 7, auth, "OLD");
              check(p.worker == null && p.refresh.writes == 0, "detached completion releases only slot");
              untouched(p, "detached reply must not render");
            });
            scenario("missing-status", () -> {
              var p = page(); var completed = p.worker; p.status = null;
              p.completeRead(completed, 7, auth, "OLD");
              check(p.worker == null && p.refresh.writes == 0, "missing status must not touch button");
              untouched(p, "missing status reply must not render");
            });
            scenario("missing-refresh", () -> {
              var p = page(); var completed = p.worker; p.refresh = null;
              p.completeRead(completed, 7, auth, "OLD");
              check(p.worker == null, "missing refresh still releases own slot");
              untouched(p, "missing refresh reply must not render");
            });
            scenario("paused-matching-generation", () -> {
              var p = page(); var completed = p.worker; p.resumed = false;
              p.completeRead(completed, 7, auth, "OLD");
              check(p.worker == null && !p.refresh.enabled, "paused button must remain disabled");
              untouched(p, "resumed guard must reject even matching generation");
            });
            scenario("current-success", () -> {
              var p = page(); var completed = p.worker;
              p.completeRead(completed, 7, auth, "CURRENT");
              check(p.worker == null && p.refresh.enabled && p.renders == 1
                  && "CURRENT".equals(p.rendered), "current authorized reply must reach renderer once");
              int writes = p.refresh.writes;
              p.completeRead(completed, 7, auth, "DUPLICATE");
              check(p.renders == 1 && p.refresh.writes == writes,
                  "duplicate completion must be inert after slot release");
            });
            scenario("authorization-changed", () -> {
              var p = page(); var completed = p.worker; p.entries.setText("PRIVATE");
              p.completeRead(completed, 7, new DeviceAuthorization.Snapshot(), "OLD");
              check(p.worker == null && p.refresh.enabled, "revoked result still restores explicit read");
              check(p.renders == 0 && p.entries.text.isEmpty()
                  && "Authorization changed. Check it again.".equals(p.status.text),
                  "authorization guard must erase body rather than render");
            });
            scenario("missing-entries", () -> {
              var p = page(); var completed = p.worker; p.entries = null;
              p.completeRead(completed, 7, auth, "OLD");
              check(p.worker == null && p.refresh.enabled && p.renders == 0,
                  "missing entries must not render but explicit read is restored");
            });
            scenario("resume-alive-and-dead", () -> {
              var p = page(); var completed = p.worker;
              p.onResume(); check(!p.refresh.enabled, "alive worker disables read on resume");
              completed.alive = false; p.onResume();
              check(p.refresh.enabled && p.worker == completed, "dead worker allows explicit read");
              check(p.renders == 0 && p.pageGeneration == 7, "resume must not read or render");
            });
            return "PASS:13";
          } catch (AssertionError failure) {
            return "FAIL:" + failure.getMessage();
          }
        }
      }
      """;
}
