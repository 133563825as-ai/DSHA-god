package com.deepseekharness.app.runtime;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import com.deepseekharness.app.util.PluginDownloadSource;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuestPluginScriptsTest {
  @Test
  public void startupPreparesOnceForTwoIndependentOrderedHelpersAndNextCommandPreparesAgain()
      throws Exception {
    var calls = new ArrayList<String>();
    var scripts =
        new GuestPluginScripts(
            preparation(calls, true, true),
            (command, timeout) -> {
              calls.add(command);
              return com.deepseekharness.app.util.BoundedProcessRunner.localCompletion(
                  command.contains("register-builtin")
                      ? "BUILTIN_REGISTER_OK"
                      : "PLUGIN_RESULT: legacy receipt");
            });
    try (var startup = scripts.startup()) {
      assertEquals("BUILTIN_REGISTER_OK", startup.register().output);
      assertEquals("PLUGIN_RESULT: legacy receipt", startup.legacy().output);
      assertThrows(IOException.class, startup::register);
      assertThrows(IOException.class, startup::legacy);
    }
    assertEquals(List.of("ready", "python", "pnpm", "assets"), calls.subList(0, 4));
    assertEquals(6, calls.size());
    assertEquals("python3 -u /root/.dsh/register-builtin-plugins.py 2>&1", calls.get(4));
    assertEquals(
        "DSHA_PLUGIN_DOWNLOAD_SOURCE=auto python3 /root/.dsh/plugin-manager.py migrate-review-markers 2>&1",
        calls.get(5));
    scripts.manager("list", "", PluginDownloadSource.AUTO);
    assertEquals(List.of("ready", "query"), calls.subList(6, 8));
    assertTrue(calls.get(8).contains("DSHA_PLUGIN_QUERY=1"));
  }

  @Test
  public void missingOrUnknownBuiltinResultCannotAdvanceLegacyOrBeRetriedInTheScope()
      throws Exception {
    for (String mode : List.of("missing", "unknown", "no-result")) {
      var calls = new ArrayList<String>();
      var scripts =
          new GuestPluginScripts(
              preparation(calls, true, true),
              (command, timeout) -> {
                calls.add(command);
                if (mode.equals("unknown")) throw new IOException("GUEST_EXIT_UNCONFIRMED");
                return mode.equals("no-result")
                    ? null
                    : com.deepseekharness.app.util.BoundedProcessRunner.localCompletion(
                        "diagnostic only");
              });
      try (var startup = scripts.startup()) {
        assertThrows(IOException.class, startup::legacy);
        assertThrows(IOException.class, startup::register);
        assertThrows(IOException.class, startup::register);
        assertThrows(IOException.class, startup::legacy);
      }
      assertEquals(5, calls.size());
    }
  }

  @Test
  public void startupScopeCannotBeUsedAfterCloseOrFromAnotherThread() throws Exception {
    var scripts =
        new GuestPluginScripts(
            preparation(new ArrayList<>(), true, true),
            (command, timeout) -> {
              fail("no guest dispatch");
              return null;
            });
    var startup = scripts.startup();
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    Thread other =
        new Thread(
            () -> {
              try {
                startup.register();
              } catch (Throwable error) {
                failure.set(error);
              }
            });
    other.start();
    other.join(2000);
    assertFalse(other.isAlive());
    assertTrue(failure.get() instanceof IOException);
    startup.close();
    assertThrows(IOException.class, startup::register);
    assertThrows(IOException.class, startup::legacy);
  }

  @Test
  public void failedStartupPreparationReleasesQueueForTheNextCaller() throws Exception {
    var broken =
        new GuestPluginScripts(
            preparation(new ArrayList<>(), false, true),
            (command, timeout) -> {
              fail();
              return null;
            });
    assertThrows(GuestPreparationFailure.class, broken::startup);
    var completed = new java.util.concurrent.atomic.AtomicBoolean();
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    Thread next =
        new Thread(
            () -> {
              try {
                var scripts =
                    new GuestPluginScripts(
                        preparation(new ArrayList<>(), true, true),
                        (command, timeout) ->
                            com.deepseekharness.app.util.BoundedProcessRunner.localCompletion(
                                "BUILTIN_REGISTER_OK"));
                try (var startup = scripts.startup()) {
                  startup.register();
                  completed.set(true);
                }
              } catch (Throwable error) {
                failure.set(error);
              }
            });
    next.start();
    next.join(2000);
    if (next.isAlive()) {
      next.interrupt();
      next.join(2000);
    }
    assertNull(failure.get());
    assertTrue(completed.get());
  }

  @Test
  public void registerBudgetFollowsManagedAssetIdentity() throws Exception {
    for (boolean same : new boolean[] {true, false}) {
      var timeouts = new ArrayList<Long>();
      var scripts =
          new GuestPluginScripts(
              new GuestPluginScripts.Preparation() {
                public boolean environmentReady() {
                  return true;
                }

                public boolean rootPresent() {
                  return true;
                }

                public boolean pythonReady() {
                  return true;
                }

                public void pnpm() {}

                public void assets() {}

                public String managedAssetId() {
                  return ASSET;
                }

                public String lastRegisteredAssetId() {
                  return same ? ASSET : OTHER;
                }
              },
              (command, timeout) -> {
                timeouts.add(timeout);
                return com.deepseekharness.app.util.BoundedProcessRunner.localCompletion(
                    "BUILTIN_REGISTER_OK");
              });
      try (var startup = scripts.startup()) {
        startup.register();
      }
      assertEquals(1, timeouts.size());
      assertEquals(same ? 90_000L : 240_000L, timeouts.get(0).longValue());
    }
  }

  @Test
  public void registerTimeoutReportsHostAndGuestTimingAndClearsStaleLock() throws Exception {
    var calls = new ArrayList<String>();
    GuestPluginScripts.Runner runner =
        new GuestPluginScripts.Runner() {
          public com.deepseekharness.app.util.BoundedProcessRunner.Result run(
              String command, long timeoutMs) {
            calls.add(command);
            return com.deepseekharness.app.util.BoundedProcessRunner.localCompletion(
                "PROFILE_LOCK_CLEARED");
          }

          public com.deepseekharness.app.util.BoundedProcessRunner.Result run(
              String command, long timeoutMs, java.util.function.Consumer<String> onLine)
              throws IOException, InterruptedException {
            calls.add(command);
            if (command.startsWith("python3 -u /root/.dsh/register-builtin-plugins.py")) {
              onLine.accept(
                  "REGISTER_TIMING: {\"phase\":\"runtime-links-check\",\"elapsedMs\":479409}");
              return timeoutResult();
            }
            return com.deepseekharness.app.util.BoundedProcessRunner.localCompletion(
                "PROFILE_LOCK_CLEARED");
          }
        };
    var scripts = new GuestPluginScripts(preparation(new ArrayList<>(), true, true), runner);
    var failure = new java.util.concurrent.atomic.AtomicReference<IOException>();
    try (var startup = scripts.startup()) {
      failure.set(assertThrows(IOException.class, startup::register));
    }
    String message = failure.get().getMessage();
    assertTrue(message, message.startsWith("BUILTIN_REGISTER_TIMEOUT"));
    assertTrue(message, message.contains("hostElapsedMs="));
    assertTrue(message, message.contains("lastGuestPhase=runtime-links-check"));
    assertTrue(message, message.contains("guestElapsedMs=479409"));
    assertTrue(message, message.contains("GUEST_TIMING_MISMATCH"));
    assertEquals(2, calls.size());
    assertTrue(calls.get(1), calls.get(1).contains("--clear-stale-profile-lock"));
  }

  @Test
  public void missingRegistrationReceiptFailsWithTimingEvidenceAndNoCleanup() throws Exception {
    var calls = new ArrayList<String>();
    var scripts =
        new GuestPluginScripts(
            preparation(new ArrayList<>(), true, true),
            (command, timeout) -> {
              calls.add(command);
              return com.deepseekharness.app.util.BoundedProcessRunner.localCompletion(
                  "diagnostic only");
            });
    try (var startup = scripts.startup()) {
      IOException error = assertThrows(IOException.class, startup::register);
      assertTrue(
          error.getMessage(), error.getMessage().startsWith("BUILTIN_REGISTER_RECEIPT_MISSING"));
      assertTrue(error.getMessage(), error.getMessage().contains("hostElapsedMs="));
      assertTrue(error.getMessage(), error.getMessage().contains("lastGuestPhase=none"));
    }
    assertEquals("非超时失败不做锁清理，也不隐式重试", 1, calls.size());
  }

  private static final String ASSET = "a".repeat(64);
  private static final String OTHER = "b".repeat(64);

  private static com.deepseekharness.app.util.BoundedProcessRunner.Result timeoutResult()
      throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder(
                "sh",
                "-c",
                "printf 'REGISTER_TIMING: {\"phase\":\"runtime-links-check\",\"elapsedMs\":479409}\\n'; sleep 5")
            .start();
    com.deepseekharness.app.util.BoundedProcessRunner.Result result =
        com.deepseekharness.app.util.BoundedProcessRunner.collect(
            process, 300, 65536, Process::destroy);
    assertTrue("fixture must time out", result.timedOut);
    return result;
  }

  private static GuestPluginScripts.Preparation preparation(
      List<String> calls, boolean ready, boolean python) {
    return new GuestPluginScripts.Preparation() {
      public boolean environmentReady() {
        calls.add("ready");
        return ready;
      }

      public boolean rootPresent() {
        return ready;
      }

      public boolean pythonReady() {
        calls.add("python");
        return python;
      }

      public boolean queryReady() {
        calls.add("query");
        return ready && python;
      }

      public void pnpm() {
        calls.add("pnpm");
      }

      public void assets() {
        calls.add("assets");
      }
    };
  }

  @Test
  public void failedPreparationNeverStartsProfileWritingHelper() throws Exception {
    List<String> calls = new ArrayList<>();
    GuestPluginScripts first =
        new GuestPluginScripts(
            preparation(calls, false, true),
            (command, timeout) -> {
              fail("must not start");
              return null;
            });
    assertThrows(GuestPreparationFailure.class, () -> first.builtin(""));
    assertEquals(List.of("ready"), calls);
    calls.clear();
    GuestPluginScripts selected =
        new GuestPluginScripts(
            preparation(calls, true, false),
            (command, timeout) -> {
              fail();
              return null;
            });
    assertThrows(
        GuestPreparationFailure.class,
        () -> selected.manager("list", "", PluginDownloadSource.AUTO));
    assertEquals(List.of("ready", "query"), calls);
  }

  @Test
  public void commandSelectionAndPreparationPreserveFixedProotAndActualTask() throws Exception {
    List<String> calls = new ArrayList<>();
    Thread caller = Thread.currentThread();
    var queueField = GuestPluginScripts.class.getDeclaredField("SCRIPT_QUEUE");
    queueField.setAccessible(true);
    Object queue = queueField.get(null);
    var monitorField = GuestScriptQueue.class.getDeclaredField("lock");
    monitorField.setAccessible(true);
    Object monitor = monitorField.get(queue);
    GuestPluginScripts scripts =
        new GuestPluginScripts(
            preparation(calls, true, true),
            (command, timeout) -> {
              assertSame(caller, Thread.currentThread());
              assertFalse(
                  "The command callback must run without the queue monitor",
                  Thread.holdsLock(monitor));
              calls.add(command);
              assertTrue(timeout >= 900_000);
              return null;
            });
    scripts.manager("import 'local archive.tar.gz'", "a".repeat(32), PluginDownloadSource.AUTO);
    assertEquals(List.of("ready", "python", "pnpm", "assets"), calls.subList(0, 4));
    assertTrue(
        calls
            .get(4)
            .startsWith(
                "DSHA_PLUGIN_TASK="
                    + "a".repeat(32)
                    + " DSHA_PLUGIN_DOWNLOAD_SOURCE=auto python3 /root/.dsh/plugin-manager.py"));
    assertFalse(calls.get(4).contains("--ignore-scripts"));
    assertFalse(calls.get(4).contains("--frozen-lockfile"));
  }

  @Test
  public void migrationOnlyUsesKnownVerbAndQuotesStartupIdentity() {
    assertThrows(
        IllegalArgumentException.class, () -> GuestPluginScripts.migrationCommand("$(bad)", "id"));
    assertTrue(GuestPluginScripts.migrationCommand("prepare", "a'b").contains("'a'\\''b'"));
  }

  @Test
  public void verifiedReuseRunsPrepareDowngradeButSkipsFinalizeOnlyForPreparedStartup()
      throws Exception {
    String generation = java.util.UUID.randomUUID().toString();
    List<String> calls = new ArrayList<>();
    var preparation =
        new GuestPluginScripts.Preparation() {
          public boolean environmentReady() {
            return true;
          }

          public boolean rootPresent() {
            return true;
          }

          public boolean pythonReady() {
            return true;
          }

          public void pnpm() {}

          public void assets() {
            calls.add("assets");
          }

          public Rc1MigrationCache.Reuse reusableMigration() {
            return Rc1MigrationCache.Reuse.history(
                    generation,
                    "verified-historical",
                    "a".repeat(64),
                    "d".repeat(64),
                    null,
                    "0.2.2-rc.1",
                    java.util.Map.of("settings.yaml", "a".repeat(64)))
                .withStartup("startup", "history-only");
          }
        };
    GuestPluginScripts scripts =
        new GuestPluginScripts(
            preparation,
            (command, timeout) -> {
              calls.add(command);
              return com.deepseekharness.app.util.BoundedProcessRunner.localCompletion(
                  migrationReceipt("prepare", generation)
                      .replace("\"prepared\"", "\"historical-complete\"")
                      .replace("\"current\"", "\"historical\"")
                      .replace("\"pending\"", "\"verified-historical\"")
                      .replace("\"COMPLETED\"", "\"NOT_SENT\""));
            });
    var prepared = scripts.migration("prepare", "startup");
    assertEquals(0, prepared.exitCode);
    assertTrue(
        com.deepseekharness.app.util.Rc1MigrationResult.allowsStart(
            com.deepseekharness.app.util.Rc1MigrationResult.parse(prepared.output)));
    var finalized = scripts.migration("finalize", "startup");
    assertTrue(finalized.output.contains("historical-complete"));
    assertEquals(finalized, scripts.migration("finalize", "startup"));
    assertEquals(2, calls.size());
    assertEquals("assets", calls.get(0));
    assertTrue(calls.get(1).contains(" prepare "));
    assertTrue(
        scripts
            .migration("finalize", "another-startup")
            .output
            .contains("FINALIZE_TICKET_MISSING"));
    assertEquals(2, calls.size());
  }

  private static final class MigrationPreparation implements GuestPluginScripts.Preparation {
    Rc1MigrationCache.Reuse reuse = Rc1MigrationCache.Reuse.absent();
    int assets;

    public boolean environmentReady() {
      return true;
    }

    public boolean rootPresent() {
      return true;
    }

    public boolean pythonReady() {
      return true;
    }

    public void pnpm() {}

    public void assets() {
      assets++;
    }

    public Rc1MigrationCache.Reuse reusableMigration() {
      return reuse;
    }
  }

  private static String migrationReceipt(String phase, String generation) {
    return "DSHA_RC1_MIGRATION={\"resultVersion\":3,\"phase\":\""
        + phase
        + "\","
        + "\"status\":\""
        + (phase.equals("prepare") ? "prepared" : "committed")
        + "\","
        + "\"startupId\":\"startup\",\"generation\":\""
        + generation
        + "\","
        + "\"verificationScope\":\"current\",\"contentCopyStatus\":\"verified\","
        + "\"sourceObjectIdentity\":\"unknown\",\"settingsImportStatus\":\""
        + (phase.equals("prepare") ? "pending" : "verified-current")
        + "\","
        + "\"executionState\":\"COMPLETED\"}";
  }

  @Test
  public void cachedPrepareEvidenceLossNeverDispatchesGuest() throws Exception {
    var prep = new MigrationPreparation();
    String generation = java.util.UUID.randomUUID().toString();
    prep.reuse =
        Rc1MigrationCache.Reuse.history(
                generation,
                "not-required",
                null,
                "d".repeat(64),
                null,
                "0.2.2-rc.1",
                java.util.Map.of())
            .withStartup("startup", "history-only");
    var scripts =
        new GuestPluginScripts(
            prep,
            (command, timeout) -> {
              assertTrue("仅prepare降权，finalize不得补派", command.contains(" prepare "));
              return com.deepseekharness.app.util.BoundedProcessRunner.localCompletion(
                  migrationReceipt("prepare", generation)
                      .replace("\"prepared\"", "\"historical-complete\"")
                      .replace("\"current\"", "\"historical\"")
                      .replace("\"pending\"", "\"not-required\"")
                      .replace("\"COMPLETED\"", "\"NOT_SENT\""));
            });
    scripts.migration("prepare", "startup");
    prep.reuse = Rc1MigrationCache.Reuse.unavailable("HOST_STATE_MISSING");
    var first = scripts.migration("finalize", "startup");
    assertTrue(first.output.contains("FINALIZE_EVIDENCE_UNAVAILABLE"));
    assertEquals(first, scripts.migration("finalize", "startup"));
    assertEquals(1, prep.assets);
  }

  @Test
  public void cachedFinalizeRejectsSameGenerationWrongStartupOrWriteAllowed() throws Exception {
    for (String mode : List.of("other-startup", "write-allowed")) {
      var prep = new MigrationPreparation();
      String generation = java.util.UUID.randomUUID().toString();
      var history =
          Rc1MigrationCache.Reuse.history(
              generation,
              "not-required",
              null,
              "d".repeat(64),
              null,
              "0.2.2-rc.1",
              java.util.Map.of());
      prep.reuse = history.withStartup("startup", "history-only");
      var count = new java.util.concurrent.atomic.AtomicInteger();
      var scripts =
          new GuestPluginScripts(
              prep,
              (command, timeout) -> {
                count.incrementAndGet();
                return com.deepseekharness.app.util.BoundedProcessRunner.localCompletion(
                    migrationReceipt("prepare", generation)
                        .replace("\"prepared\"", "\"historical-complete\"")
                        .replace("\"current\"", "\"historical\"")
                        .replace("\"pending\"", "\"not-required\"")
                        .replace("\"COMPLETED\"", "\"NOT_SENT\""));
              });
      scripts.migration("prepare", "startup");
      prep.reuse =
          mode.equals("other-startup")
              ? history.withStartup("other", "history-only")
              : history.withStartup("startup", "write-allowed");
      assertTrue(
          scripts
              .migration("finalize", "startup")
              .output
              .contains("FINALIZE_EVIDENCE_UNAVAILABLE"));
      assertEquals(1, count.get());
    }
  }

  @Test
  public void cachedPrepareRequiresFreshDurableDowngradeBeforeIssuingTicket() throws Exception {
    var prep = new MigrationPreparation();
    String generation = java.util.UUID.randomUUID().toString();
    prep.reuse =
        Rc1MigrationCache.Reuse.history(
                generation,
                "not-required",
                null,
                "d".repeat(64),
                null,
                "0.2.2-rc.1",
                java.util.Map.of())
            .withStartup("old", "write-allowed");
    var count = new java.util.concurrent.atomic.AtomicInteger();
    var scripts =
        new GuestPluginScripts(
            prep,
            (command, timeout) -> {
              count.incrementAndGet();
              return com.deepseekharness.app.util.BoundedProcessRunner.localCompletion(
                  migrationReceipt("prepare", generation)
                      .replace("\"prepared\"", "\"historical-complete\"")
                      .replace("\"current\"", "\"historical\"")
                      .replace("\"pending\"", "\"not-required\"")
                      .replace("\"COMPLETED\"", "\"NOT_SENT\""));
            });
    assertTrue(
        scripts.migration("prepare", "startup").output.contains("PREPARE_EVIDENCE_UNAVAILABLE"));
    assertTrue(scripts.migration("finalize", "startup").output.contains("FINALIZE_TICKET_MISSING"));
    assertEquals(1, count.get());
  }

  @Test
  public void guestFinalizeKeepsPrepareGenerationAcrossCacheTransitionAndRejectsWrongResult()
      throws Exception {
    var prep = new MigrationPreparation();
    var commands = new ArrayList<String>();
    String generation = java.util.UUID.randomUUID().toString();
    var scripts =
        new GuestPluginScripts(
            prep,
            (command, timeout) -> {
              commands.add(command);
              return com.deepseekharness.app.util.BoundedProcessRunner.localCompletion(
                  migrationReceipt(
                      command.contains(" prepare ") ? "prepare" : "finalize",
                      command.contains(" prepare ")
                          ? generation
                          : java.util.UUID.randomUUID().toString()));
            });
    scripts.migration("prepare", "startup");
    prep.reuse =
        Rc1MigrationCache.Reuse.history(
                generation,
                "not-required",
                null,
                "d".repeat(64),
                null,
                "0.2.2-rc.1",
                java.util.Map.of())
            .withStartup("startup", "history-only");
    var first = scripts.migration("finalize", "startup");
    assertTrue(commands.get(1).contains("--expected-generation '" + generation + "'"));
    assertTrue(first.output.contains("FINALIZE_RESULT_BINDING"));
    assertEquals(first, scripts.migration("finalize", "startup"));
    assertEquals(2, commands.size());
  }

  @Test
  public void failedPrepareCannotBeReplayedOrGrantFinalizeDispatch() throws Exception {
    var count = new java.util.concurrent.atomic.AtomicInteger();
    var scripts =
        new GuestPluginScripts(
            new MigrationPreparation(),
            (command, timeout) -> {
              count.incrementAndGet();
              throw new IOException("prepare receipt lost");
            });
    assertThrows(IOException.class, () -> scripts.migration("prepare", "startup"));
    assertTrue(
        scripts.migration("prepare", "startup").output.contains("PREPARE_ALREADY_ATTEMPTED"));
    assertTrue(scripts.migration("finalize", "startup").output.contains("FINALIZE_TICKET_MISSING"));
    assertEquals(1, count.get());
  }

  @Test
  public void unknownGuestFinalizeCannotBeRedispatched() throws Exception {
    for (String mode : List.of("exception", "null", "timeout")) {
      var prep = new MigrationPreparation();
      var count = new java.util.concurrent.atomic.AtomicInteger();
      String generation = java.util.UUID.randomUUID().toString();
      var scripts =
          new GuestPluginScripts(
              prep,
              (command, timeout) -> {
                if (count.getAndIncrement() == 0)
                  return com.deepseekharness.app.util.BoundedProcessRunner.localCompletion(
                      migrationReceipt("prepare", generation));
                if (mode.equals("exception")) throw new IOException("lost receipt");
                return mode.equals("null") ? null : timeoutResult();
              });
      scripts.migration("prepare", "startup");
      if (mode.equals("exception"))
        assertThrows(IOException.class, () -> scripts.migration("finalize", "startup"));
      else scripts.migration("finalize", "startup");
      var repeated = scripts.migration("finalize", "startup");
      assertNotNull(repeated);
      assertFalse(
          com.deepseekharness.app.util.Rc1MigrationResult.isRoutineReceipt(
              repeated.timedOut
                  ? java.util.Map.of()
                  : com.deepseekharness.app.util.Rc1MigrationResult.parse(repeated.output)));
      assertEquals(2, count.get());
    }
  }

  @Test
  public void listUsesItsShortBudgetAndPreparationConsumesThatSameDeadline() throws Exception {
    var calls = new ArrayList<String>();
    var scripts =
        new GuestPluginScripts(
            preparation(calls, true, true),
            (command, timeout) -> {
              assertTrue(timeout > 0 && timeout <= GuestPluginScripts.QUERY_TIMEOUT_MS);
              return null;
            });
    scripts.manager("refresh", "", PluginDownloadSource.AUTO);
    var slow =
        new GuestPluginScripts(
            new GuestPluginScripts.Preparation() {
              public boolean environmentReady() {
                return true;
              }

              public boolean rootPresent() {
                return true;
              }

              public boolean pythonReady() {
                fail("查询不能走完整 Python 准备");
                return false;
              }

              public boolean queryReady() {
                try {
                  Thread.sleep(70);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                return true;
              }

              public void pnpm() {
                fail("期限过后不继续准备");
              }

              public void assets() {
                fail("期限过后不继续准备");
              }
            },
            (command, timeout) -> {
              fail("准备耗尽预算后不能启动guest");
              return null;
            });
    var timeout =
        assertThrows(
            IOException.class, () -> slow.manager("list", "", PluginDownloadSource.AUTO, 30, true));
    assertEquals("PLUGIN_QUERY_TIMEOUT", timeout.getMessage());
  }

  @Test
  public void cancelledPreparationCannotLaunchAQueuedGuestOrConsumeAnotherCommand()
      throws Exception {
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var scripts =
        new GuestPluginScripts(
            new GuestPluginScripts.Preparation() {
              public boolean environmentReady() {
                cancelled.set(true);
                return true;
              }

              public boolean rootPresent() {
                return true;
              }

              public boolean pythonReady() {
                fail();
                return false;
              }

              public boolean queryReady() {
                return true;
              }

              public void pnpm() {
                fail();
              }

              public void assets() {
                fail();
              }

              public boolean cancelled(String task) {
                return cancelled.get();
              }
            },
            (command, timeout) -> {
              fail("取消后不能启动guest");
              return null;
            });
    var failure =
        assertThrows(
            IOException.class,
            () -> scripts.manager("list", "a".repeat(32), PluginDownloadSource.AUTO));
    assertEquals("PLUGIN_QUERY_CANCELLED", failure.getMessage());
    var next =
        new GuestPluginScripts(
            preparation(new ArrayList<>(), true, true), (command, timeout) -> null);
    next.manager("enable-state 'official'", "", PluginDownloadSource.AUTO);
  }

  @Test
  public void managerInheritsEarlierSubmissionDeadlineAndRestoresItAfterTheCall() throws Exception {
    var scripts =
        new GuestPluginScripts(
            preparation(new ArrayList<>(), true, true),
            (command, timeout) -> {
              fail("提交期限已耗尽，不能重新取得30秒预算");
              return null;
            });
    var submission = new GuestScriptBudget(20, () -> false, "PLUGIN_QUERY");
    try (var scope = submission.enter()) {
      Thread.sleep(45);
      var failure =
          assertThrows(
              IOException.class, () -> scripts.manager("list", "", PluginDownloadSource.AUTO));
      assertEquals("PLUGIN_QUERY_TIMEOUT", failure.getMessage());
    }
    var next =
        new GuestPluginScripts(
            preparation(new ArrayList<>(), true, true),
            (command, timeout) -> {
              assertTrue(timeout > 0);
              return null;
            });
    next.manager("list", "", PluginDownloadSource.AUTO);
  }
}
