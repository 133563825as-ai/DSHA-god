package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Test;

/** UI-2/3 离线投影：执行生产方法和其回调原文；fake 不实现归属/退休/提交算法。 */
public final class SafBrowseOwnershipSourceTest {
  private static final List<String> METHODS =
      List.of(
          "load",
          "child",
          "open",
          "prepareTransfer",
          "transferForm",
          "ownsOperation",
          "retireOperation",
          "onPause",
          "onDestroyView");

  @Test
  public void productionCallbacksAndMutationControls() throws Exception {
    Path root = Path.of(System.getProperty("saf.ownership.repo", ".")).toAbsolutePath();
    if (!Files.isDirectory(root.resolve("app"))) root = root.getParent();
    Path source =
        root.resolve("app/src/main/java/com/deepseekharness/app/ui/SafBrowseFragment.java");
    Map<String, String> methods = extract(source);
    Path output =
        System.getProperty("saf.ownership.output") == null
            ? Files.createTempDirectory("next-saf-ui-projection-")
            : Path.of(System.getProperty("saf.ownership.output")).toAbsolutePath();
    Files.createDirectories(output);
    assertFalse("输出必须在仓外", output.toRealPath().startsWith(root.toRealPath()));
    assertEquals("PASS:29", run(output, "baseline", methods));
    mutate(output, methods, "load", "retireOperation();", "", "no-load-retire", "refresh");
    mutate(
        output,
        methods,
        "ownsOperation",
        "&& owner == getView()",
        "",
        "no-view-owner",
        "null-view");
    mutate(
        output,
        methods,
        "ownsOperation",
        "&& selectedTree.equals(treeId)",
        "",
        "no-tree-owner",
        "tree-owner");
    mutate(
        output,
        methods,
        "ownsOperation",
        "&& directory.equals(current)",
        "",
        "no-path-owner",
        "path-owner");
    mutate(output, methods, "onPause", "retireOperation();", "", "no-pause-retire", "pause-return");
    mutate(output, methods, "ownsOperation", "&& isResumed()", "", "no-resumed", "paused-owner");
    mutate(
        output,
        methods,
        "prepareTransfer",
        "workbench.cancel(ticket);",
        "",
        "no-stale-cancel",
        "navigate-old/child");
    mutate(
        output,
        methods,
        "prepareTransfer",
        "workbench.cancel(ticket);",
        "model.workbench.cancel(ticket);",
        "cancel-wrong-workbench",
        "captured-workbench");
    mutate(
        output,
        methods,
        "ownsOperation",
        "generation == operationGeneration",
        "true",
        "no-generation",
        "refresh");
    mutate(
        output,
        methods,
        "transferForm",
        "button.setEnabled(false);",
        "button.setEnabled(false); dialog.dismiss();",
        "premature-dismiss",
        "submission-unknown");
    mutate(
        output,
        methods,
        "transferForm",
        "workbench.cancel(ticket);\n                local.setError(",
        "local.setError(",
        "no-unknown-retire",
        "submission-unknown");
    mutate(
        output,
        methods,
        "transferForm",
        "dialog.setCanceledOnTouchOutside(false);",
        "",
        "outside-dismiss",
        "outside");
    mutate(
        output,
        methods,
        "transferForm",
        "targetName.requestFocus();",
        "targetName.requestFocus(); dialog.dismiss();",
        "bad-name-dismiss",
        "bad-name");
    mutate(
        output,
        methods,
        "transferForm",
        "if (released[0]) return;",
        "",
        "no-submit-once",
        "submit-once");
    mutate(
        output,
        methods,
        "transferForm",
        "if (!released[0]) {",
        "if (true) {",
        "cancel-after-handoff",
        "submit-once");
    mutate(
        output,
        methods,
        "open",
        "if (!ownsOperation(generation, selectedTree, directory, owner)) return;",
        "",
        "stale-menu",
        "stale-menu");
  }

  private static void mutate(
      Path output,
      Map<String, String> methods,
      String method,
      String before,
      String after,
      String variant,
      String scenario)
      throws Exception {
    Map<String, String> changed = new LinkedHashMap<>(methods);
    String body = changed.get(method);
    int at = body.indexOf(before);
    assertTrue("唯一变异目标: " + variant, at >= 0 && body.lastIndexOf(before) == at);
    changed.put(method, body.substring(0, at) + after + body.substring(at + before.length()));
    String result = run(output, variant, changed);
    assertTrue(
        "变异必须行为失败而非编译失败: " + variant + " " + result, result.startsWith("FAIL:" + scenario + ":"));
  }

  private static Map<String, String> extract(Path source) throws Exception {
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull("需要完整 JDK", compiler);
    String text = Files.readString(source);
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    Map<String, String> result = new LinkedHashMap<>();
    try (var files =
        compiler.getStandardFileManager(
            diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
      var task =
          (JavacTask)
              compiler.getTask(
                  null,
                  files,
                  diagnostics,
                  List.of("-proc:none"),
                  null,
                  files.getJavaFileObjects(source.toFile()));
      var unit = task.parse().iterator().next();
      var positions = Trees.instance(task).getSourcePositions();
      for (var declaration : unit.getTypeDecls()) {
        if (!(declaration instanceof ClassTree type)) continue;
        for (var member : type.getMembers()) {
          if (!(member instanceof MethodTree method)) continue;
          String name = method.getName().toString();
          if (!METHODS.contains(name)
              || (name.equals("load") && method.getParameters().size() != 2)) continue;
          assertFalse("方法唯一: " + name, result.containsKey(name));
          result.put(
              name,
              text.substring(
                  (int) positions.getStartPosition(unit, method),
                  (int) positions.getEndPosition(unit, method)));
        }
      }
      assertTrue(
          diagnostics.getDiagnostics().toString(),
          diagnostics.getDiagnostics().stream()
              .noneMatch(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR));
    }
    assertEquals(METHODS.size(), result.size());
    return result;
  }

  private static String run(Path output, String variant, Map<String, String> methods)
      throws Exception {
    Path directory = output.resolve(variant);
    Files.createDirectories(directory);
    Path source = directory.resolve("OwnershipProjection.java");
    Files.writeString(source, DEPENDENCIES + String.join("\n", methods.values()) + MATRIX);
    var compiler = ToolProvider.getSystemJavaCompiler();
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    try (var files =
        compiler.getStandardFileManager(
            diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
      boolean compiled =
          compiler
              .getTask(
                  null,
                  files,
                  diagnostics,
                  List.of("-proc:none", "-d", directory.toString()),
                  null,
                  files.getJavaFileObjects(source.toFile()))
              .call();
      assertTrue("投影必须编译: " + diagnostics.getDiagnostics(), compiled);
    }
    try (var loader = new URLClassLoader(new java.net.URL[] {directory.toUri().toURL()}, null)) {
      String result =
          (String) loader.loadClass("OwnershipProjection").getMethod("matrix").invoke(null);
      System.out.println(variant + " " + result);
      return result;
    }
  }

  private static final String DEPENDENCIES =
      """
      import java.util.*;
      import java.util.concurrent.*;
      import java.util.concurrent.atomic.AtomicBoolean;
      @interface Nullable {}
      class Lifecycle {
        boolean resumed = true;
        public void onPause() { resumed = false; }
        public void onDestroyView() {}
      }
      public final class OwnershipProjection extends Lifecycle {
        interface Click { void call(View view); }
        interface Choice { void call(AlertDialog dialog, int which); }
        interface Dismiss { void call(AlertDialog dialog); }
        static class Context { Context getApplicationContext() { return this; } }
        static class View {
          boolean enabled = true;
          void setEnabled(boolean value) { enabled = value; }
        }
        static class TextView extends View {
          String text = "", error;
          TextView(Context context) {}
          void setText(String value) { text = value; }
        }
        static class EditText extends TextView {
          boolean focused;
          EditText() { super(null); }
          void setHint(String hint) {}
          String getText() { return text; }
          void setError(String value) { error = value; }
          void requestFocus() { focused = true; }
        }
        static class Button extends View {
          Click click;
          void setOnClickListener(Click value) { click = value; }
          void press() { if (enabled && click != null) click.call(this); }
        }
        static class LinearLayout extends View {
          static final int VERTICAL = 1;
          LinearLayout(Context context) {}
          void setOrientation(int value) {}
          void setPadding(int a,int b,int c,int d) {}
          void addView(View value) {}
        }
        static class ScrollView extends View {
          ScrollView(Context context) {}
          void addView(View value) {}
        }
        static class AlertDialog {
          static final int BUTTON_POSITIVE = -1;
          boolean showing = true, outside = true;
          Button positive = new Button();
          Choice builderPositive, choice;
          Dismiss dismiss;
          void setCanceledOnTouchOutside(boolean value) { outside = value; }
          void setOnDismissListener(Dismiss value) { dismiss = value; }
          Button getButton(int which) { return positive; }
          void dismiss() { if (showing) { showing = false; if (dismiss != null) dismiss.call(this); } }
          void outside() { if (outside) dismiss(); }
          void positive() {
            if (!showing) return;
            if (positive.click != null) positive.press();
            else { if (builderPositive != null) builderPositive.call(this, -1); dismiss(); }
          }
        }
        static class DshaDialogBuilder {
          static AlertDialog last;
          AlertDialog dialog = new AlertDialog();
          DshaDialogBuilder(Context context) {}
          DshaDialogBuilder setTitle(String value) { return this; }
          DshaDialogBuilder setView(View value) { return this; }
          DshaDialogBuilder setItems(String[] items, Choice value) { dialog.choice = value; return this; }
          DshaDialogBuilder setPositiveButton(String title, Choice value) { dialog.builderPositive = value; return this; }
          DshaDialogBuilder setNegativeButton(String title, Choice value) { return this; }
          AlertDialog show() { last = dialog; return dialog; }
        }
        static class UiText {
          static String choose(String zh, String en) { return en; }
          static String text(String value) { return value; }
          static String format(String pattern, Object... args) { return String.format(pattern,args); }
        }
        static class SafWorkbenchUi {
          static boolean enabled(Context context) { return true; }
          static String problem(String code) { return code; }
        }
        static class ConfigStore {
          ConfigStore(Context context) {}
          String getWorkdir() { return "/root"; }
        }
        // 这里只指定校验依赖的true/false，不复制Kotlin safeName算法。
        static class TransferArtifact {
          static boolean valid;
          static boolean safeName(String name) { return valid; }
        }
        static class SafTree {
          static class Failure extends Exception { String code; Failure(String code) { this.code = code; } }
          static class Entry { boolean directory; String name; Entry(String name) { this.name = name; } }
          static class Page { boolean truncated; List<Entry> entries = List.of(); }
        }
        static class SafGrants {
          static class Grant { boolean valid = true, readable = true, writable = true; }
          static class Lookup { String failure; Grant grant = new Grant(); }
          static Lookup lookup(Context context, String id) { return new Lookup(); }
        }
        static class SafWorkbench {
          static class Ticket {
            final String treeId, path;
            int cancels, handoffs;
            Ticket(String treeId, String path) { this.treeId = treeId; this.path = path; }
          }
          List<Ticket> issued = new ArrayList<>(), cancelled = new ArrayList<>(); String failure;
          Ticket prepare(String tree, String path, boolean importing) throws SafTree.Failure {
            if (failure != null) throw new SafTree.Failure(failure);
            var ticket = new Ticket(tree, path); issued.add(ticket); return ticket;
          }
          void cancel(Ticket ticket) { cancelled.add(ticket); ticket.cancels++; }
          SafTree.Page list(String tree, String path) throws SafTree.Failure { return new SafTree.Page(); }
        }
        static class Model {
          AtomicBoolean working = new AtomicBoolean(); SafWorkbench workbench = new SafWorkbench();
          int calls; String remote, local; boolean throwing;
          void transfer(SafWorkbench.Ticket ticket, String remote, String local, boolean importing) {
            calls++; ticket.handoffs++; this.remote = remote; this.local = local;
            if (throwing) throw new RejectedExecutionException();
          }
        }
        static class QueueExecutor extends AbstractExecutorService {
          List<Runnable> tasks = new ArrayList<>(); boolean shutdown;
          public void execute(Runnable run) { tasks.add(run); }
          void next() { tasks.remove(0).run(); }
          public void shutdown() { shutdown = true; }
          public List<Runnable> shutdownNow() { shutdown = true; var old = tasks; tasks = new ArrayList<>(); return old; }
          public boolean isShutdown() { return shutdown; }
          public boolean isTerminated() { return shutdown; }
          public boolean awaitTermination(long n, TimeUnit unit) { return shutdown; }
        }
        static class Main {
          List<Runnable> tasks = new ArrayList<>();
          void post(Runnable run) { tasks.add(run); }
          void next() { tasks.remove(0).run(); }
        }
        static class Adapter { void notifyDataSetChanged() {} }
        static class Resources {
          Resources getDisplayMetrics() { return this; }
          float density = 1;
        }
        static class Args { String getString(String key, String fallback) { return "fixture"; } }
        static final String ARG_TITLE = "title";
        String treeId = "tree-A", current = "old";
        View view = new View(); Context context = new Context();
        TextView pathView = new TextView(null), statusView = new TextView(null);
        Button upButton = new Button(), exportButton = new Button(); View list = new View();
        Adapter adapter = new Adapter(); List<SafTree.Entry> entries = new ArrayList<>();
        long operationGeneration, loadGeneration, previewGeneration;
        boolean writeAvailable = true;
        String statusSummary = "", statusDetail = "", problem;
        Model model = new Model(); Main main = new Main();
        ExecutorService executor = new QueueExecutor(); AlertDialog operationDialog;
        List<EditText> fields = new ArrayList<>();
        Context requireContext() { return context; }
        Resources getResources() { return new Resources(); }
        Args requireArguments() { return new Args(); }
        View getView() { return view; }
        boolean isResumed() { return resumed; }
        void controls() {}
        void renderStatus(String summary, String detail) {}
        void renderProblem(String code) { problem = code; }
        void preview(SafTree.Entry entry) {}
        void load(String path) { load(path, false); }
        EditText input(LinearLayout form, String title) { var field = new EditText(); fields.add(field); return field; }
      """;

  private static final String MATRIX =
      """
        static int count;
        static void check(boolean yes, String message) { if (!yes) throw new AssertionError(message); }
        static void scenario(String name, Runnable action) {
          try { action.run(); count++; }
          catch (AssertionError error) { throw new AssertionError(name + ":" + error.getMessage()); }
        }
        void finishPrepare() { ((QueueExecutor) executor).next(); main.next(); }
        SafWorkbench.Ticket ticket() { return model.workbench.issued.get(0); }
        AlertDialog form() { prepareTransfer(null); finishPrepare(); return operationDialog; }
        void fill() { fields.get(0).setText("/root/input.md"); fields.get(1).setText("output.md"); }
        void kept(AlertDialog dialog) {
          check(dialog.showing, "form must remain open");
          check(fields.get(0).text.equals("/root/input.md") && fields.get(1).text.equals("output.md"), "both inputs retained");
        }
        public static String matrix() {
          try {
            count = 0; TransferArtifact.valid = true;
            for (String path : List.of("old/child", "", "old")) scenario(path.equals("old") ? "refresh" : "navigate-"+path, () -> {
              var p = new OwnershipProjection(); p.prepareTransfer(null); p.load(path, false); p.finishPrepare();
              check(p.operationDialog == null && p.ticket().cancels == 1, "old reply only cancels original ticket");
              check(p.ticket().path.equals("old"), "captured path must not migrate");
            });
            scenario("pause-return", () -> {
              var p = new OwnershipProjection(); p.prepareTransfer(null); p.onPause(); p.resumed = true; p.finishPrepare();
              check(p.operationDialog == null && p.ticket().cancels == 1, "pause retires even after return");
            });
            scenario("paused-owner", () -> {
              var p = new OwnershipProjection(); p.prepareTransfer(null); p.resumed = false; p.finishPrepare();
              check(p.operationDialog == null && p.ticket().cancels == 1, "paused owner cannot accept matching generation");
            });
            scenario("captured-workbench", () -> {
              var p = new OwnershipProjection(); p.prepareTransfer(null); var old = p.model.workbench;
              p.model.workbench = new SafWorkbench(); p.onPause(); p.finishPrepare();
              check(old.issued.size() == 1 && old.cancelled.size() == 1
                  && old.issued.get(0).cancels == 1 && p.model.workbench.issued.isEmpty()
                  && p.model.workbench.cancelled.isEmpty(), "prepare and cancel must stay with captured workbench");
            });
            scenario("null-view", () -> {
              var p = new OwnershipProjection(); p.prepareTransfer(null); p.view = null; p.finishPrepare();
              check(p.operationDialog == null && p.ticket().cancels == 1, "missing view cannot accept prepare");
            });
            scenario("view-owner", () -> {
              var p = new OwnershipProjection(); p.prepareTransfer(null); p.view = new View(); p.finishPrepare();
              check(p.operationDialog == null && p.ticket().cancels == 1, "new view cannot receive old prepare");
            });
            scenario("tree-owner", () -> {
              var p = new OwnershipProjection(); p.prepareTransfer(null); p.treeId = "tree-B"; p.finishPrepare();
              check(p.operationDialog == null && p.ticket().treeId.equals("tree-A") && p.ticket().cancels == 1, "tree is captured and checked");
            });
            scenario("path-owner", () -> {
              var p = new OwnershipProjection(); p.prepareTransfer(null); p.current = "other"; p.finishPrepare();
              check(p.operationDialog == null && p.ticket().cancels == 1, "path guard independent of generation");
            });
            scenario("old-new-replies", () -> {
              var p = new OwnershipProjection(); p.prepareTransfer(null); p.prepareTransfer(null);
              p.finishPrepare(); check(p.operationDialog == null && p.ticket().cancels == 1, "old cannot open");
              p.finishPrepare(); var fresh = p.model.workbench.issued.get(1);
              check(p.operationDialog != null && fresh.cancels == 0, "old must not cancel fresh ticket");
            });
            scenario("new-before-old-reply", () -> {
              var p = new OwnershipProjection(); p.prepareTransfer(null); ((QueueExecutor)p.executor).next();
              p.prepareTransfer(null); ((QueueExecutor)p.executor).next();
              p.main.tasks.remove(1).run(); var freshDialog = p.operationDialog; p.main.next();
              check(p.operationDialog == freshDialog && freshDialog.showing && p.ticket().cancels == 1
                  && p.model.workbench.issued.get(1).cancels == 0, "late old reply cannot close new form");
            });
            scenario("destroy-recreate", () -> {
              var p = new OwnershipProjection(); p.prepareTransfer(null); ((QueueExecutor)p.executor).next();
              p.onDestroyView(); p.view = new View(); p.executor = new QueueExecutor(); p.main.next();
              check(p.operationDialog == null && p.ticket().cancels == 1, "destroyed prepare cannot reattach");
            });
            scenario("outside", () -> {
              var p = new OwnershipProjection(); var d = p.form(); p.fill(); d.outside(); p.kept(d);
              check(p.ticket().cancels == 0, "outside must not cancel ticket");
            });
            scenario("bad-name", () -> {
              var p = new OwnershipProjection(); var d = p.form(); p.fill(); TransferArtifact.valid = false;
              d.positive(); p.kept(d); check(p.fields.get(1).focused && "BAD_NAME".equals(p.fields.get(1).error)
                  && p.model.calls == 0 && p.ticket().cancels == 0, "invalid name stays editable without dispatch");
              TransferArtifact.valid = true; d.positive(); check(p.model.calls == 1 && !d.showing, "explicit correction submits once");
            });
            scenario("explicit-cancel", () -> {
              var p = new OwnershipProjection(); var d = p.form(); p.fill(); d.dismiss(); d.dismiss();
              check(p.ticket().cancels == 1 && p.model.calls == 0, "cancel once without consume");
            });
            scenario("submit-once", () -> {
              var p = new OwnershipProjection(); var d = p.form(); p.fill(); var callback = d.positive.click;
              d.positive(); callback.call(d.positive);
              check(p.model.calls == 1 && p.ticket().handoffs == 1 && p.ticket().cancels == 0 && !d.showing,
                  "one handoff; dismiss cannot cancel transferred ownership");
              check(p.model.remote.equals("old/output.md") && p.model.local.equals("/root/input.md"), "exact selected destination");
            });
            scenario("busy", () -> {
              var p = new OwnershipProjection(); var d = p.form(); p.fill(); p.model.working.set(true); d.positive();
              p.kept(d); check(p.model.calls == 0 && p.ticket().cancels == 0, "busy keeps owned input without dispatch");
            });
            scenario("submission-unknown", () -> {
              var p = new OwnershipProjection(); var d = p.form(); p.fill(); p.model.throwing = true;
              d.positive(); p.kept(d); d.positive(); d.positive.click.call(d.positive); d.dismiss();
              check(p.model.calls == 1 && p.ticket().cancels == 1 && !d.positive.enabled
                  && p.fields.get(0).error.contains("not confirmed"), "unknown retires once and cannot replay");
            });
            scenario("pause-open-form", () -> {
              var p = new OwnershipProjection(); var d = p.form(); p.onPause();
              check(!d.showing && p.ticket().cancels == 1 && p.operationDialog == null, "leave cancels undispatched form");
            });
            scenario("refresh-open-form", () -> {
              var p = new OwnershipProjection(); var d = p.form(); p.fill(); p.load("old", false);
              check(!d.showing && p.ticket().cancels == 1 && p.model.calls == 0, "refresh retires owned form once");
            });
            scenario("async-failure-no-replay", () -> {
              var p = new OwnershipProjection(); var d = p.form(); p.fill(); d.positive();
              p.problem = "HANDLE_EXPIRED"; p.load("old", true);
              check(p.model.calls == 1 && p.model.workbench.issued.size() == 1 && p.ticket().cancels == 0,
                  "terminal refresh cannot prepare or submit another transfer");
            });
            scenario("stale-positive", () -> {
              var p = new OwnershipProjection(); var d = p.form(); p.fill(); p.current = "other"; d.positive();
              check(!d.showing && p.ticket().cancels == 1 && p.model.calls == 0, "stale form cannot submit");
            });
            scenario("stale-menu", () -> {
              var p = new OwnershipProjection(); p.open(new SafTree.Entry("source.md"));
              var menu = DshaDialogBuilder.last; p.load("other", false); menu.choice.call(menu, 1);
              check(((QueueExecutor)p.executor).tasks.size() == 1, "stale menu cannot prepare in new path");
            });
            scenario("import-current", () -> {
              var p = new OwnershipProjection(); p.prepareTransfer(new SafTree.Entry("source.md")); p.finishPrepare();
              var d = p.operationDialog; d.positive();
              check(p.model.calls == 1 && p.model.remote.equals("old/source.md")
                  && p.model.local.equals("/root/source.md") && p.ticket().cancels == 0, "import preserves selected source");
            });
            for (String code : List.of("HANDLE_EXPIRED", "HANDLE_REVOKED", "PERMISSION_DENIED")) scenario("prepare-"+code, () -> {
              var p = new OwnershipProjection(); p.model.workbench.failure = code; p.prepareTransfer(null); p.finishPrepare();
              check(code.equals(p.problem) && p.operationDialog == null && p.model.calls == 0
                  && ((QueueExecutor)p.executor).tasks.isEmpty(), "prepare failure must not reissue or transfer");
            });
            scenario("old-failure", () -> {
              var p = new OwnershipProjection(); p.model.workbench.failure = "HANDLE_EXPIRED";
              p.prepareTransfer(null); p.load("old", false); p.finishPrepare();
              check(p.problem == null && p.operationDialog == null, "retired failure cannot change current UI");
            });
            return "PASS:" + count;
          } catch (AssertionError error) { return "FAIL:" + error.getMessage(); }
        }
      }
      """;
}
