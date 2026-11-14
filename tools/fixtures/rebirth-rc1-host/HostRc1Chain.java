package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.regex.*;
import static org.junit.Assert.*;

/** 真实RC1核心不替换；这里只适配guest命令/Android文件身份的宿主边界。 */
public final class HostRc1Chain {
  private final Path checkout, run, files, root, home, state;
  private final String python, node;
  private int dispatched;
  private final HostFs fs = new HostFs();

  private HostRc1Chain(Path checkout, Path run, String python, String node, String name) throws Exception {
    this.checkout = checkout;
    this.run = run;
    this.python = python;
    this.node = node;
    files = run.resolve(name);
    root = files.resolve("guest");
    home = root.resolve(".dsh");
    state = files.resolve("rc1-migration-state");
    Files.createDirectories(home);
    Files.writeString(home.resolve("settings.yaml"), "first: {model: old, maxTokens: 2048}\n");
    // 使用当前树声明；不调用旧候选脚本/不写实际HOME。
    Files.copy(checkout.resolve("app/src/main/assets/runtime-patches.json"), home.resolve("runtime-patches.json"));
  }

  /** 仅将真实Python隔离绝对home在小JSON读取时映射成Java guest契约home；不改磁盘记录。 */
  private final class HostFs extends JvmBackupFileSystem {
    @Override public BackupFileSystem.Node stat(File file) throws IOException {
      Path p = file.toPath().toAbsolutePath().normalize();
      if (!p.startsWith(files)) throw new IOException("HOST_FIXTURE_OUTSIDE_BOUNDARY: " + p);
      BackupFileSystem.Node n = super.stat(file);
      if (n.type.equals("MISSING")) return n;
      Map<String,Object> unix = Files.readAttributes(p, "unix:dev,ino", LinkOption.NOFOLLOW_LINKS);
      return new BackupFileSystem.Node(n.type, unix.get("dev") + ":" + unix.get("ino"), n.size, n.modified,
          ((Number) unix.get("dev")).longValue(), n.mode);
    }
    @Override public byte[] small(File file, int maximum) throws IOException {
      byte[] bytes = super.small(file, maximum);
      if (file.toPath().startsWith(state) && file.getName().endsWith(".json"))
        return new String(bytes, StandardCharsets.UTF_8)
            .replace('"' + home.toString() + '"', "\"/root/.dsh\"")
            .getBytes(StandardCharsets.UTF_8);
      return bytes;
    }
  }

  private Map<String,Object> json(Path file) throws Exception {
    return BackupJson.read(Files.readAllBytes(file), 4 * 1024 * 1024);
  }

  private GuestPluginScripts scripts() {
    return new GuestPluginScripts(new GuestPluginScripts.Preparation() {
      public boolean environmentReady() { return true; }
      public boolean rootPresent() { return Files.isDirectory(root); }
      public boolean pythonReady() { throw new AssertionError("not installation validation"); }
      public void pnpm() { throw new AssertionError("no package installation"); }
      public void assets() { /* assets read directly from this tree; no installation */ }
      public Rc1MigrationCache.Reuse reusableMigration() {
        Rc1MigrationCache.Reuse result = Rc1MigrationCache.reuse(fs, files.toFile(), home.toFile());
        System.out.println("CACHE status=" + result.status + " reason=" + result.reason);
        return result;
      }
    }, (command, timeout) -> {
      // 不执行Java生成的shell字符串。逐一校验固定命令，只抽取本fixture已知安全ID。
      if (!command.startsWith("python3 -B /root/.dsh/rc1-migration.py ")
          || !command.contains(" --root /root --state-root /run/dsha-rc1-state --startup-id ")
          || !command.endsWith(" --approved-root /sdcard/Documents/dshdata --approved-root /storage/emulated/0/Documents/dshdata 2>&1"))
        throw new IOException("UNEXPECTED_GUEST_COMMAND: " + command);
      Matcher startup = Pattern.compile(" --startup-id '([a-z0-9-]+)'").matcher(command);
      if (!startup.find()) throw new IOException("STARTUP_NOT_SYNTHETIC");
      String phase = command.contains(" prepare ") ? "prepare" : "finalize";
      List<String> argv = new ArrayList<>(List.of(python, "-B",
          checkout.resolve("app/src/main/assets/rc1-migration.py").toString(), phase,
          "--root", root.toString(), "--state-root", state.toString(), "--startup-id", startup.group(1)));
      if (phase.equals("finalize")) {
        Matcher expected = Pattern.compile(" --expected-generation '([a-f0-9-]{36})'").matcher(command);
        if (!expected.find()) throw new IOException("EXPECTED_GENERATION_MISSING");
        argv.addAll(List.of("--expected-generation", expected.group(1)));
      }
      dispatched++;
      System.out.println("JAVA_GUEST_DISPATCH " + command);
      return process(argv, "python-" + files.getFileName() + "-" + dispatched);
    });
  }

  private BoundedProcessRunner.Result process(List<String> argv, String label) throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(argv).directory(checkout.toFile()).redirectErrorStream(true);
    Process child = builder.start();
    BoundedProcessRunner.Result result = BoundedProcessRunner.collect(child, 120_000, 1024 * 1024, Process::destroy);
    Files.writeString(run.resolve(label + ".log"), String.join(" ", argv) + "\n" + result.output + "\nexit=" + result.exitCode);
    System.out.print(result.output);
    assertFalse(label, result.timedOut);
    assertFalse(label, result.truncated);
    return result;
  }

  private void hook(int updates) throws Exception {
    BoundedProcessRunner.Result result = process(List.of(node,
        checkout.resolve("tools/fixtures/rebirth-rc1-host/hook.mjs").toString(),
        checkout.toString(), root.toString(), state.toString(), Integer.toString(updates)),
        "hook-" + files.getFileName() + "-" + dispatched);
    assertEquals(result.output, 0, result.exitCode);
    assertTrue(result.output, result.output.contains("HOOK_PASS updates=" + updates));
  }

  private Map<String,Object> receipt(BoundedProcessRunner.Result result, String startup, String phase,
      String status, String scope, String settings, String execution, String generation) throws Exception {
    assertNotNull(result);
    assertEquals(result.output, 0, result.exitCode);
    assertFalse(result.timedOut);
    assertFalse(result.truncated);
    Map<String,Object> value = Rc1MigrationResult.parse(result.output);
    assertEquals(phase, value.get("phase"));
    assertEquals(status, value.get("status"));
    assertEquals(startup, value.get("startupId"));
    assertEquals(scope, value.get("verificationScope"));
    assertEquals("verified", value.get("contentCopyStatus"));
    assertEquals("unknown", value.get("sourceObjectIdentity"));
    assertEquals(settings, value.get("settingsImportStatus"));
    assertEquals(execution, value.get("executionState"));
    assertTrue(Ids.uuid((String) value.get("generation")));
    if (generation != null) assertEquals(generation, value.get("generation"));
    assertTrue(Rc1MigrationResult.boundTo(value, startup, phase, (String) value.get("generation")));
    return value;
  }

  private void firstThenCached() throws Exception {
    GuestPluginScripts first = scripts();
    Map<String,Object> prepared = receipt(first.migration("prepare", "boot-first"), "boot-first", "prepare",
        "prepared", "current", "pending", "COMPLETED", null);
    assertTrue(Rc1MigrationResult.allowsStart(prepared));
    String generation = (String) prepared.get("generation");
    hook(1);
    Map<String,Object> finalized = receipt(first.migration("finalize", "boot-first"), "boot-first", "finalize",
        "committed", "current", "verified-current", "COMPLETED", generation);
    assertTrue(Rc1MigrationResult.isRoutineReceipt(finalized));
    assertEquals(2, dispatched);
    Path ledger = state.resolve("generations/" + generation + "/settings-result.json");
    byte[] before = Files.readAllBytes(ledger);
    FileTime mtime = Files.getLastModifiedTime(ledger);
    assertEquals("write-allowed", ((Map<?,?>) json(state.resolve("current.json")).get("startupContext")).get("mode"));
    Rc1MigrationCache.Reuse reuse = Rc1MigrationCache.reuse(fs, files.toFile(), home.toFile());
    assertEquals(Rc1MigrationCache.Reuse.Status.REUSABLE_HISTORY, reuse.status);
    GuestPluginScripts cached = scripts();
    receipt(cached.migration("prepare", "boot-cached"), "boot-cached", "prepare", "historical-complete",
        "historical", "verified-historical", "NOT_SENT", generation);
    assertEquals("cached prepare still dispatches real Python", 3, dispatched);
    Map<?,?> context = (Map<?,?>) json(state.resolve("current.json")).get("startupContext");
    assertEquals("history-only", context.get("mode"));
    assertEquals("boot-cached", context.get("startupId"));
    hook(0); // 独立Node进程重开真实ConfigEditor/Loader，hook不得重复update。
    receipt(cached.migration("finalize", "boot-cached"), "boot-cached", "finalize", "historical-complete",
        "historical", "verified-historical", "NOT_SENT", generation);
    assertEquals("cached finalize remains local", 3, dispatched);
    assertArrayEquals(before, Files.readAllBytes(ledger));
    assertEquals("history ledger mtime must not move", mtime, Files.getLastModifiedTime(ledger));
    // 同一实例顺序finalize重复只返回原终态。
    cached.migration("finalize", "boot-cached");
    assertEquals(3, dispatched);
    System.out.println("PASS first->cached: durable history-only, real hook update0, ledger bytes+mtime unchanged, local finalize");
  }

  private Map<String,String> stateSnapshot() throws Exception {
    Map<String,String> snapshot = new TreeMap<>();
    try (var paths = Files.walk(state)) {
      for (Path p : paths.filter(Files::isRegularFile).toList())
        snapshot.put(state.relativize(p).toString(), Files.getLastModifiedTime(p) + ":"
            + Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
    }
    return snapshot;
  }

  private void wrongGeneration() throws Exception {
    GuestPluginScripts stale = scripts();
    Map<String,Object> prepared = receipt(stale.migration("prepare", "boot-stale"), "boot-stale", "prepare",
        "prepared", "current", "pending", "COMPLETED", null);
    String oldGeneration = (String) prepared.get("generation");
    Files.writeString(home.resolve("settings.yaml"), "first: {model: changed}\n");
    GuestPluginScripts current = scripts();
    Map<String,Object> next = receipt(current.migration("prepare", "boot-next"), "boot-next", "prepare",
        "prepared", "current", "pending", "COMPLETED", null);
    assertNotEquals(oldGeneration, next.get("generation"));
    Map<String,String> before = stateSnapshot();
    var result = stale.migration("finalize", "boot-stale");
    assertEquals("expected generation must fail, not pending exit0", 1, result.exitCode);
    Map<String,Object> rejected = Rc1MigrationResult.parse(result.output);
    assertEquals("EXPECTED_GENERATION_MISMATCH", rejected.get("reason"));
    assertEquals(next.get("generation"), rejected.get("generation"));
    assertEquals("unknown", rejected.get("status"));
    assertEquals("finalize", rejected.get("phase"));
    assertEquals("boot-stale", rejected.get("startupId"));
    assertFalse(Rc1MigrationResult.isRoutineReceipt(rejected));
    assertEquals("expected-generation gate must reject before state/receipt/reuse writes", before, stateSnapshot());
    assertEquals(3, dispatched);
    stale.migration("finalize", "boot-stale");
    assertEquals("unknown cannot replay", 3, dispatched);
    System.out.println("PASS expected-generation: old Java ticket->real Python prewrite rejection; all state file bytes+mtime unchanged");
  }

  public static void main(String[] args) throws Exception {
    Path checkout = Path.of(args[0]).toRealPath(), run = Path.of(args[1]).toRealPath();
    assertTrue(run.startsWith(checkout.resolve("app/build/rebirth-rc1-closure/host-verification")));
    new HostRc1Chain(checkout, run, args[2], args[3], "normal").firstThenCached();
    new HostRc1Chain(checkout, run, args[2], args[3], "generation").wrongGeneration();
  }
}
