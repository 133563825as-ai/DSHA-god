package com.deepseekharness.app.contract;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.Test;

/** 同包既有调用由互调测试执行；这里用真实 javac 验证异包可见边界。 */
public class ContractJavaBoundaryTest {
  @Test
  public void publicJavaCallShapesRemainAvailableToOtherPackages() throws Exception {
    assertTrue(
        compiles(
            """
        var id = ModuleId.of("demo.external");
        var descriptor = new ModuleDescriptor(id, 1, "External", java.util.Set.of(), ModuleDescriptor.Enablement.OPTIONAL_USER);
        ModuleFactory factory = () -> () -> java.util.List.of(Contribution.card(id, "card", "卡片", "Card", 0));
        var store = new SharedPreferencesLayoutStore(null);
        var registry = new ModuleRegistry(store, new SafeModeGate(), DiagnosticsSink.discarding());
        registry.register(descriptor, factory);
        BuiltinModules.ensureRegistered(registry);
        registry.install(id);
        registry.dispatch(id, registry.generation(id), () -> {});
        registry.close(id, ModuleInstance.CloseReason.HOST_CLOSE);
        LayoutSnapshot.empty().withHidden("demo.external#card", true);
        RouteTable.Target.module("app.page");
        ContractResult.rejected("REJECTED");
        """));
  }

  @Test
  public void originalPackageOnlyEntrypointsRemainUnavailableToOtherJavaPackages()
      throws Exception {
    for (String statement :
        List.of(
            "new GenerationLease(null, 1L, null);",
            "((GenerationLease) null).invalidate();",
            "new SafeModeGate().enterSafeMode();",
            "new SafeModeGate().exitSafeMode();",
            "LayoutSnapshot.validateKey(\"demo.external#card\");",
            "ContributionView.of(null, false, 1L);",
            "BuiltinModules.ensureRegistered((android.content.SharedPreferences) null);")) {
      assertFalse(statement, compiles(statement));
    }
  }

  private static boolean compiles(String statements) throws IOException {
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull("验证要求 JDK17 的 javac，不能跳过此测试", compiler);
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    Path output = Files.createTempDirectory("contract-java-boundary-").toRealPath();
    JavaFileObject source =
        new SimpleJavaFileObject(
            URI.create("string:///external/Caller.java"), JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return "package external; import com.deepseekharness.app.contract.*; public class Caller { void call() {"
                + statements
                + "} }";
          }
        };
    try (StandardJavaFileManager manager =
        compiler.getStandardFileManager(diagnostics, null, null)) {
      boolean accepted =
          compiler
              .getTask(
                  null,
                  manager,
                  diagnostics,
                  List.of(
                      "-proc:none",
                      "--release",
                      "17",
                      "-classpath",
                      System.getProperty("java.class.path"),
                      "-d",
                      output.toString()),
                  null,
                  List.of(source))
              .call();
      if (!accepted)
        assertTrue(
            diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
      return accepted;
    } finally {
      // 只清理本测试刚创建的私有临时目录，拒绝任何越出该根目录的目标。
      try (var files = Files.walk(output)) {
        for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
          if (!file.toAbsolutePath().normalize().startsWith(output))
            throw new IOException("临时目录边界错误");
          Files.delete(file);
        }
      }
    }
  }
}
