package com.deepseekharness.app.ui;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.Test;

/**
 * 停止失败信息框的真实源码窄投影；不是 Android/Robolectric 验收。
 *
 * <p>原样提取完整 showStopFailureDialog 与真实 builder 的构造、positive、show；
 * 仅替代 Android 显示/点击、可控延时队列、策略存储和业务端口，不复制生产决策算法。
 */
public final class StopFailureDialogConsentSourceTest {
  @Test
  public void realFailureMethodRequiresExplicitCurrentClickAndCurrentDelayedExecution()
      throws Exception {
    verify(sourceRoot(), false, null);
  }

  /** 旧 true 只变异内存中的生产构造，分别验证两种 retryable 的运行期失败。 */
  public static void main(String[] args) throws Exception {
    boolean oldTrue = args.length > 1 && args[1].equals("--old-true");
    Boolean retryable = args.length > 2 ? Boolean.valueOf(args[2]) : null;
    verify(Path.of(args[0]), oldTrue, retryable);
    System.out.println(
        "PASS: global on/off x retryable on/off; show/cancel/click/due; six stale guards");
  }

  private static Path sourceRoot() {
    Path path = Path.of("").toAbsolutePath();
    while (path != null && !Files.isRegularFile(path.resolve("app/src/main/AndroidManifest.xml")))
      path = path.getParent();
    if (path == null) throw new AssertionError("Cannot locate DSHA source tree");
    return path;
  }

  private static void verify(Path root, boolean oldTrue, Boolean onlyRetryable) throws Exception {
    var compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null)
      throw new AssertionError("This source-derived test requires JDK 17 javac");
    Path work = Files.createTempDirectory("stop-failure-dialog-source-");
    Path sources = work.resolve("src");
    Path classes = work.resolve("classes");
    Files.createDirectories(classes);
    String ui = "app/src/main/java/com/deepseekharness/app/ui/";
    Parsed builder = new Parsed(root.resolve(ui + "DshaDialogBuilder.java"));
    Parsed launch = new Parsed(root.resolve(ui + "LaunchFragment.java"));
    StringBuilder methods = new StringBuilder();
    for (String name : List.of("<init>", "setPositiveButton", "show"))
      for (MethodTree method : builder.methods(name))
        methods.append(builder.text(method)).append('\n');
    put(
        sources,
        "com/deepseekharness/app/ui/DshaDialogBuilder.java",
        """
        package com.deepseekharness.app.ui;
        import android.content.Context;
        import androidx.appcompat.app.AlertDialog;
        import com.deepseekharness.app.R;
        import com.google.android.material.dialog.MaterialAlertDialogBuilder;
        public final class DshaDialogBuilder extends MaterialAlertDialogBuilder {
          private final boolean safetyConfirmation;
          private final long consentRevision;
          private android.content.DialogInterface.OnClickListener positive;
        """
            + methods
            + "}\n");

    List<MethodTree> failureMethods = launch.methods("showStopFailureDialog");
    if (failureMethods.size() != 1) throw new AssertionError("Expected one real failure method");
    String failureMethod = launch.text(failureMethods.get(0));
    if (oldTrue) {
      String from = "new DshaDialogBuilder(requireContext())";
      if (failureMethod.indexOf(from) < 0
          || failureMethod.indexOf(from) != failureMethod.lastIndexOf(from))
        throw new AssertionError("Expected exactly one current constructor for old-true mutation");
      failureMethod = failureMethod.replace(from, "new DshaDialogBuilder(requireContext(), true)");
    }
    put(
        sources,
        "com/deepseekharness/app/ui/Projection.java",
        projection(failureMethod, onlyRetryable));
    put(
        sources,
        "android/content/Context.java",
        """
        package android.content;
        public class Context { public CharSequence getText(int id) { return "text"; } }
        """);
    put(
        sources,
        "android/content/DialogInterface.java",
        """
        package android.content;
        public interface DialogInterface {
          int BUTTON_POSITIVE = -1;
          interface OnClickListener { void onClick(DialogInterface dialog, int which); }
        }
        """);
    put(
        sources,
        "android/R.java",
        """
        package android;
        public class R { public static class string { public static final int cancel = 1; } }
        """);
    put(
        sources,
        "com/deepseekharness/app/R.java",
        """
package com.deepseekharness.app;
public class R { public static class style { public static final int Dialog_DSHA_Material = 1; } }
""");
    put(
        sources,
        "androidx/appcompat/app/AlertDialog.java",
        """
package androidx.appcompat.app;
import android.content.DialogInterface;
public class AlertDialog implements DialogInterface {
  public boolean showing;
  public OnClickListener positive, negative;
  public void clickPositive() { if (positive != null) positive.onClick(this, -1); showing = false; }
  public void clickCancel() { if (negative != null) negative.onClick(this, -2); showing = false; }
}
""");
    put(
        sources,
        "com/google/android/material/dialog/MaterialAlertDialogBuilder.java",
        """
package com.google.android.material.dialog;
import android.content.Context;
import android.content.DialogInterface.OnClickListener;
import androidx.appcompat.app.AlertDialog;
public class MaterialAlertDialogBuilder {
  public static AlertDialog last;
  private final Context context;
  private OnClickListener positive, negative;
  public MaterialAlertDialogBuilder(Context context, int style) { this.context = context; }
  public Context getContext() { return context; }
  public MaterialAlertDialogBuilder setTitle(CharSequence value) { return this; }
  public MaterialAlertDialogBuilder setMessage(CharSequence value) { return this; }
  public MaterialAlertDialogBuilder setPositiveButton(CharSequence text, OnClickListener listener) {
    positive = listener; return this;
  }
  public MaterialAlertDialogBuilder setPositiveButton(int text, OnClickListener listener) {
    return setPositiveButton(context.getText(text), listener);
  }
  public MaterialAlertDialogBuilder setNegativeButton(int text, OnClickListener listener) {
    negative = listener; return this;
  }
  public AlertDialog create() {
    var dialog = new AlertDialog(); dialog.positive = positive; dialog.negative = negative;
    last = dialog; return dialog;
  }
  public AlertDialog show() { var dialog = create(); dialog.showing = true; return dialog; }
}
""");
    put(
        sources,
        "com/deepseekharness/app/core/DeviceGrants.java",
        """
package com.deepseekharness.app.core;
public class DeviceGrants {
  public static boolean global;
  public static long revision = 7;
  public DeviceGrants(android.content.Context context) {}
  public com.deepseekharness.app.util.NativeConsentPolicy consentPolicy() {
    return new com.deepseekharness.app.util.NativeConsentPolicy(global, revision);
  }
}
""");
    put(
        sources,
        "com/deepseekharness/app/util/UiText.java",
        """
        package com.deepseekharness.app.util;
        public class UiText {
          public static String text(String text) { return text; }
          public static String choose(String zh, String en) { return en; }
        }
        """);
    put(
        sources,
        "com/deepseekharness/app/core/WebForceStopText.java",
        """
package com.deepseekharness.app.core;
public class WebForceStopText {
  public static String retryButton() { return "wait then retry"; }
}
""");
    Parsed budget =
        new Parsed(
            root.resolve("app/src/main/java/com/deepseekharness/app/util/WebStopBudget.java"));
    var budgetType = (ClassTree) budget.unit.getTypeDecls().get(0);
    var delayFields =
        budgetType.getMembers().stream()
            .filter(
                member ->
                    member instanceof com.sun.source.tree.VariableTree field
                        && field.getName().contentEquals("FORCE_RETRY_MILLIS"))
            .toList();
    if (delayFields.size() != 1) throw new AssertionError("Expected one real retry delay");
    put(
        sources,
        "com/deepseekharness/app/util/WebStopBudget.java",
        "package com.deepseekharness.app.util; public class WebStopBudget {"
            + budget.text(delayFields.get(0))
            + "}");
    put(
        sources,
        "com/deepseekharness/app/util/NativeConsentPolicy.java",
        Files.readString(
            root.resolve("app/src/main/java/com/deepseekharness/app/util/NativeConsentPolicy.java"),
            StandardCharsets.UTF_8));
    List<String> arguments = new ArrayList<>(List.of("--release", "17", "-d", classes.toString()));
    try (var files = Files.walk(sources)) {
      files
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(path -> arguments.add(path.toString()));
    }
    var errors = new java.io.ByteArrayOutputStream();
    int result = compiler.run(null, null, errors, arguments.toArray(String[]::new));
    if (result != 0)
      throw new AssertionError(
          "Projection compile failed: " + errors.toString(StandardCharsets.UTF_8));
    try (var loader =
        new URLClassLoader(
            new java.net.URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
      try {
        loader.loadClass("com.deepseekharness.app.ui.Projection").getMethod("run").invoke(null);
      } catch (InvocationTargetException failure) {
        if (failure.getCause() instanceof AssertionError assertion) throw assertion;
        throw failure;
      }
    }
  }

  private static void put(Path sources, String name, String source) throws Exception {
    Path path = sources.resolve(name);
    Files.createDirectories(path.getParent());
    Files.writeString(path, source, StandardCharsets.UTF_8);
  }

  private static String projection(String failureMethod, Boolean onlyRetryable) {
    return """
package com.deepseekharness.app.ui;
import android.content.Context;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.core.DeviceGrants;
import com.deepseekharness.app.util.WebStopBudget;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
public final class Projection {
  static final class Activity extends Context {
    boolean finishing, destroyed;
    boolean isFinishing() { return finishing; }
    boolean isDestroyed() { return destroyed; }
  }
  final class View {
    final java.util.List<Runnable> tasks = new java.util.ArrayList<>();
    long now, due;
    int posts;
    boolean postDelayed(Runnable task, long delay) {
      check(explicitClick, "postDelayed without explicit click");
      check(delay == WebStopBudget.FORCE_RETRY_MILLIS, "preserve production retry budget");
      tasks.add(task); due = now + delay; posts++; return true;
    }
    void advance(long elapsed) {
      now += elapsed;
      if (now < due) return;
      var ready = new java.util.ArrayList<>(tasks); tasks.clear();
      draining = true;
      try { for (Runnable task : ready) task.run(); }
      finally { draining = false; }
    }
  }
  static final class TextView { void setText(String value) {} }
  static final class Controller { long generation = 11; long getWebGeneration() { return generation; } }
  final Activity activity = new Activity();
  final View root = new View();
  final TextView status = new TextView();
  final Controller controller = new Controller();
  final long generation = controller.getWebGeneration();
  Object currentView = root;
  boolean resumed = true, explicitClick, draining;
  int stops, offers;
  Object getView() { return currentView; }
  boolean isResumed() { return resumed; }
  Context getContext() { return activity; }
  Context requireContext() { return activity; }
  String shortLine(String value, String fallback) { return value; }
  void requestStop(Activity activity, View root, TextView status) {
    check(explicitClick, "requestStop without explicit click"); stops++;
  }
  void offerForceStop(Activity activity, View root, TextView status, long generation) {
    check(draining && root.posts == 1, "offerForceStop must follow one explicit scheduled retry");
    offers++;
  }
"""
        + failureMethod
        + """
  void stale(int kind) {
    switch (kind) {
      case 0 -> DeviceGrants.revision++;
      case 1 -> currentView = new Object();
      case 2 -> controller.generation++;
      case 3 -> resumed = false;
      case 4 -> activity.finishing = true;
      case 5 -> activity.destroyed = true;
      default -> throw new AssertionError("unknown stale condition");
    }
  }
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static Projection fresh(boolean global) {
    DeviceGrants.global = global; DeviceGrants.revision = 7;
    MaterialAlertDialogBuilder.last = null; return new Projection();
  }
  static AlertDialog shown(Projection p, boolean retryable) {
    p.showStopFailureDialog(p.activity, p.root, p.status, p.generation, "failed", retryable);
    var dialog = MaterialAlertDialogBuilder.last;
    check(dialog != null && dialog.showing, "failure information must be shown, not auto-approved");
    check(p.stops == 0 && p.offers == 0 && p.root.posts == 0, "show must dispatch and schedule zero");
    check(dialog.positive != null, "explicit retry action must exist"); return dialog;
  }
  void click(AlertDialog dialog) {
    explicitClick = true;
    try { dialog.clickPositive(); } finally { explicitClick = false; }
  }
  static void builderControls(boolean global) {
    fresh(global);
    int[] calls = {0};
    AlertDialog ordinary = new DshaDialogBuilder(new Context())
        .setPositiveButton("retry", (d, w) -> calls[0]++).show();
    check(ordinary.showing && calls[0] == 0, "ordinary builder never auto-answers");
    ordinary.clickPositive(); check(calls[0] == 1, "ordinary click invokes positive once");
    ordinary = new DshaDialogBuilder(new Context())
        .setPositiveButton("retry", (d, w) -> calls[0]++).show();
    ordinary.clickCancel(); check(calls[0] == 1, "ordinary cancel invokes no positive");
    int[] safetyCalls = {0};
    AlertDialog safety = new DshaDialogBuilder(new Context(), true)
        .setPositiveButton("approve", (d, w) -> safetyCalls[0]++).show();
    check(safetyCalls[0] == (global ? 1 : 0) && safety.showing != global,
        "real safety builder global auto-answer path must remain exercised");
  }
  public static void run() {
    for (boolean global : new boolean[] {false, true}) {
      builderControls(global);
      for (boolean retryable : RETRYABLE_STATES) {
        Projection p = fresh(global); AlertDialog dialog = shown(p, retryable);
        p.root.advance(WebStopBudget.FORCE_RETRY_MILLIS * 2);
        check(p.stops == 0 && p.offers == 0 && p.root.posts == 0, "show plus time dispatches zero");
        p = fresh(global); dialog = shown(p, retryable); dialog.clickCancel();
        p.root.advance(WebStopBudget.FORCE_RETRY_MILLIS * 2);
        check(p.stops == 0 && p.offers == 0 && p.root.posts == 0, "cancel plus time dispatches zero");
        p = fresh(global); dialog = shown(p, retryable); p.click(dialog);
        check(p.stops == (retryable ? 0 : 1) && p.offers == 0,
            "click is immediate only for nonretryable stop");
        check(p.root.posts == (retryable ? 1 : 0), "only retryable click posts exactly once");
        p.root.advance(WebStopBudget.FORCE_RETRY_MILLIS - 1);
        check(p.offers == 0, "no retry before due time");
        p.root.advance(1);
        check(p.offers == (retryable ? 1 : 0), "one retry exactly at due time");
        p.root.advance(WebStopBudget.FORCE_RETRY_MILLIS * 2);
        check(p.stops == (retryable ? 0 : 1) && p.offers == (retryable ? 1 : 0),
            "later clock advance cannot repeat consumed action");
        for (int kind = 0; kind < 6; kind++) {
          p = fresh(global); dialog = shown(p, retryable); p.stale(kind); p.click(dialog);
          check(p.stops == 0 && p.offers == 0 && p.root.posts == 0,
              "stale click must not even enqueue: " + kind);
          p.root.advance(WebStopBudget.FORCE_RETRY_MILLIS * 2);
          check(p.stops == 0 && p.offers == 0, "stale click plus time: " + kind);
          if (retryable) {
            p = fresh(global); dialog = shown(p, true); p.click(dialog);
            check(p.root.posts == 1, "valid click queued before stale delay");
            p.root.advance(WebStopBudget.FORCE_RETRY_MILLIS - 1); p.stale(kind);
            p.root.advance(1); p.root.advance(WebStopBudget.FORCE_RETRY_MILLIS * 2);
            check(p.stops == 0 && p.offers == 0 && p.root.tasks.isEmpty(),
                "stale delayed execution must consume without dispatch: " + kind);
          }
        }
        System.out.println("matrix global=" + global + " retryable=" + retryable
            + ": show/cancel=0, click/due=1, stale click=6, stale delay=" + (retryable ? 6 : 0));
      }
    }
  }
}
"""
            .replace(
                "RETRYABLE_STATES",
                onlyRetryable == null
                    ? "new boolean[] {false, true}"
                    : "new boolean[] {" + onlyRetryable + "}");
  }

  private static final class Parsed {
    final String source;
    final CompilationUnitTree unit;
    final com.sun.source.util.SourcePositions positions;

    Parsed(Path path) throws Exception {
      source = Files.readString(path, StandardCharsets.UTF_8);
      try (var files =
          ToolProvider.getSystemJavaCompiler()
              .getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
        var errors = new StringWriter();
        JavacTask task =
            (JavacTask)
                ToolProvider.getSystemJavaCompiler()
                    .getTask(
                        errors,
                        files,
                        null,
                        List.of("-proc:none"),
                        null,
                        files.getJavaFileObjects(path.toFile()));
        unit = task.parse().iterator().next();
        if (!errors.toString().isEmpty())
          throw new AssertionError("Source parse failed: " + errors);
        positions = Trees.instance(task).getSourcePositions();
      }
    }

    String text(com.sun.source.tree.Tree tree) {
      return source.substring(
          (int) positions.getStartPosition(unit, tree), (int) positions.getEndPosition(unit, tree));
    }

    List<MethodTree> methods(String name) {
      ClassTree type = (ClassTree) unit.getTypeDecls().get(0);
      List<MethodTree> result = new ArrayList<>();
      for (var member : type.getMembers())
        if (member instanceof MethodTree method && method.getName().contentEquals(name))
          result.add(method);
      if (result.isEmpty()) throw new AssertionError("Missing real method: " + name);
      return result;
    }
  }
}
