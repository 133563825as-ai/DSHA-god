package com.deepseekharness.app.ui;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
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
 * 真实源码的窄 JVM 投影，不是 Android/Robolectric UI 验收。
 *
 * <p>用 JDK parser 原样提取 builder 构造、positive 包装、show、ALREADY_EXITED 分支和投递守卫； 只替代
 * Android/Material/策略与停止端口，拒绝自动业务派发。不另写一份“正确”决策算法。
 */
public final class WebStopRecheckDialogTest {
  @Test
  public void realBuilderAndProductionBranchRequireExplicitCurrentClick() throws Exception {
    verify(sourceRoot(), false);
  }

  /** 仓外 probe 可复跑；--old-true 只变异内存中的构造选择，预期 AssertionError/非零退出。 */
  public static void main(String[] args) throws Exception {
    verify(Path.of(args[0]), args.length == 2 && args[1].equals("--old-true"));
    System.out.println(
        "PASS: global on/off; explicit click/cancel; six stale guards at delivery/click");
  }

  private static Path sourceRoot() {
    Path path = Path.of("").toAbsolutePath();
    while (path != null && !Files.isRegularFile(path.resolve("app/src/main/AndroidManifest.xml")))
      path = path.getParent();
    if (path == null) throw new AssertionError("Cannot locate DSHA source tree");
    return path;
  }

  private static void verify(Path root, boolean oldTrue) throws Exception {
    var compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null)
      throw new AssertionError("This source-derived test requires JDK 17 javac");
    Path work = Files.createTempDirectory("f2-dialog-source-");
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

    String branch = launch.exitedBranch();
    if (oldTrue) {
      String from = "new DshaDialogBuilder(requireContext())";
      if (branch.indexOf(from) < 0 || branch.indexOf(from) != branch.lastIndexOf(from))
        throw new AssertionError("Expected exactly one current constructor for old-true mutation");
      branch = branch.replace(from, "new DshaDialogBuilder(requireContext(), true)");
    }
    put(
        sources,
        "com/deepseekharness/app/ui/Projection.java",
        projection(launch.deliveryGuard(), branch));
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
  public static String recordedGuestExited() { return "recorded guest exited; stop unfinished"; }
}
""");
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

  private static String projection(String guard, String branch) {
    return """
package com.deepseekharness.app.ui;
import android.content.Context;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.core.DeviceGrants;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
public final class Projection {
  static final class Activity extends Context {
    boolean finishing, destroyed;
    boolean isFinishing() { return finishing; }
    boolean isDestroyed() { return destroyed; }
  }
  static final class Status { void setText(String value) {} }
  static final class Controller { long generation = 11; long getWebGeneration() { return generation; } }
  final Activity activity = new Activity();
  final Object root = new Object();
  final Status status = new Status();
  final Controller controller = new Controller();
  final long consentRevision = DeviceGrants.revision;
  final long generation = controller.getWebGeneration();
  Object currentView = root;
  boolean resumed = true, explicitClick;
  int stops;
  Object getView() { return currentView; }
  boolean isResumed() { return resumed; }
  Context requireContext() { return activity; }
  String shortLine(String value, String fallback) { return value; }
  void requestStop(Activity activity, Object root, Status status) {
    if (!explicitClick) throw new AssertionError("requestStop without explicit click");
    stops++;
  }
  void deliver() {
"""
        + guard
        + "\n"
        + branch
        + """
  }
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
  static AlertDialog shown(Projection p) {
    p.deliver();
    var dialog = MaterialAlertDialogBuilder.last;
    check(dialog != null && dialog.showing, "dialog must be shown, not auto-approved");
    check(p.stops == 0, "zero dispatch before explicit click");
    check(dialog.positive != null, "re-check action must exist");
    return dialog;
  }
  public static void run() {
    for (boolean global : new boolean[] {false, true}) {
      fresh(global);
      int[] positiveCalls = {0};
      AlertDialog ordinary = new DshaDialogBuilder(new Context())
          .setPositiveButton("recheck", (d, w) -> positiveCalls[0]++).show();
      check(ordinary.showing && positiveCalls[0] == 0, "ordinary builder must not invoke positive");
      ordinary.clickPositive();
      check(positiveCalls[0] == 1, "ordinary positive is delivered only on click");
      ordinary = new DshaDialogBuilder(new Context())
          .setPositiveButton("recheck", (d, w) -> positiveCalls[0]++).show();
      ordinary.clickCancel();
      check(positiveCalls[0] == 1, "cancel invokes no positive listener");
      int[] safetyCalls = {0};
      AlertDialog safety = new DshaDialogBuilder(new Context(), true)
          .setPositiveButton("approve", (d, w) -> safetyCalls[0]++).show();
      check(safetyCalls[0] == (global ? 1 : 0), "real safety builder global dependency exercised");
      check(safety.showing != global, "real safety builder global show decision exercised");
      Projection p = fresh(global);
      AlertDialog dialog = shown(p);
      p.explicitClick = true; dialog.clickPositive(); p.explicitClick = false;
      check(p.stops == 1, "current explicit click dispatches exactly once");
      p = fresh(global); dialog = shown(p); dialog.clickCancel();
      check(p.stops == 0, "cancel must not dispatch");
      for (int kind = 0; kind < 6; kind++) {
        p = fresh(global); dialog = shown(p); p.stale(kind);
        p.explicitClick = true; dialog.clickPositive(); p.explicitClick = false;
        check(p.stops == 0, "stale click guard " + kind);
        p = fresh(global); p.stale(kind); p.deliver();
        check(MaterialAlertDialogBuilder.last == null && p.stops == 0, "stale delivery guard " + kind);
      }
    }
  }
}
""";
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

    String exitedBranch() {
      List<String> result = new ArrayList<>();
      new TreeScanner<Void, Void>() {
        @Override
        public Void visitCase(CaseTree tree, Void unused) {
          if (tree.getExpressions().size() == 1
              && tree.getExpressions().get(0).toString().equals("ALREADY_EXITED"))
            result.add(text(tree.getBody()));
          return super.visitCase(tree, unused);
        }
      }.scan(methods("offerForceStop").get(0), null);
      if (result.size() != 1)
        throw new AssertionError("Expected one production ALREADY_EXITED block");
      return result.get(0);
    }

    String deliveryGuard() {
      List<String> result = new ArrayList<>();
      new TreeScanner<Void, Void>() {
        @Override
        public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
          if (tree.getBody() instanceof BlockTree block
              && block.getStatements().size() > 1
              && block.getStatements().get(0).toString().equals("forceCandidatePending = false;")
              && block.getStatements().get(1) instanceof IfTree guard) result.add(text(guard));
          return super.visitLambdaExpression(tree, unused);
        }
      }.scan(methods("offerForceStop").get(0), null);
      if (result.size() != 1) throw new AssertionError("Expected one real UI-delivery guard");
      return result.get(0);
    }
  }
}
