package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Simulates new app processes without touching real device preferences or su. */
public final class RootSettingsAuthorityTest {
  @Test
  public void newProcessRejectsStalePersistedRootAndFullPolicy() {
    var persistedRoot = new AtomicBoolean(true);
    var oldProcess = new RootSettingsAuthority();
    oldProcess.saveRoot(true, () -> true);
    oldProcess.savePolicy(DeviceShellPolicy.Mode.FULL, () -> true);
    assertTrue(oldProcess.allowed(persistedRoot::get));
    assertEquals(DeviceShellPolicy.Mode.FULL, oldProcess.policy());

    var restarted = new RootSettingsAuthority();
    assertFalse(restarted.allowed(persistedRoot::get));
    assertEquals(DeviceShellPolicy.Mode.STRICT, restarted.policy());
  }

  @Test
  public void failedDisableCannotClaimDurableRevocationAndCannotRearm() {
    var persistedRoot = new AtomicBoolean(true);
    var authority = new RootSettingsAuthority();
    authority.saveRoot(true, () -> true);
    assertTrue(authority.allowed(persistedRoot::get));
    try {
      authority.saveRoot(false, () -> false);
      fail("expected durable save failure");
    } catch (IllegalStateException expected) {
      assertFalse(authority.allowed(persistedRoot::get));
    }
    assertFalse(new RootSettingsAuthority().allowed(persistedRoot::get));
  }

  @Test
  public void failedEnableNeverArmsRootEvenIfPreferenceMemoryChanged() {
    var persistedRoot = new AtomicBoolean(true);
    var authority = new RootSettingsAuthority();
    try {
      authority.saveRoot(true, () -> false);
      fail("expected durable save failure");
    } catch (IllegalStateException expected) {
      assertFalse(authority.allowed(persistedRoot::get));
    }
  }

  @Test
  public void failedPolicySaveCannotActivatePermissiveMode() {
    var authority = new RootSettingsAuthority();
    authority.saveRoot(true, () -> true);
    try {
      authority.savePolicy(DeviceShellPolicy.Mode.FULL, () -> false);
      fail("expected durable save failure");
    } catch (IllegalStateException expected) {
      assertEquals(DeviceShellPolicy.Mode.STRICT, authority.policy());
    }
  }

  @Test
  public void pendingRevocationDisarmsImmediatelyBeforeDiskWrite() {
    var authority = new RootSettingsAuthority();
    authority.saveRoot(true, () -> true);
    assertTrue(authority.disarm().wasArmed);
    assertFalse(authority.allowed(() -> true));
    assertFalse(authority.disarm().wasArmed);
  }

  @Test
  public void newerChangeCannotBeRearmedByOlderBlockedCommit() throws Exception {
    var authority = new RootSettingsAuthority();
    var commitEntered = new CountDownLatch(1);
    var releaseCommit = new CountDownLatch(1);
    var worker = Executors.newSingleThreadExecutor();
    try {
      var older =
          worker.submit(
              () -> {
                try {
                  authority.saveRoot(
                      true,
                      () -> {
                        commitEntered.countDown();
                        try {
                          return releaseCommit.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          return false;
                        }
                      });
                } catch (IllegalStateException superseded) {
                  return;
                }
                throw new AssertionError("旧保存不能重新开启 Root");
              });
      assertTrue(commitEntered.await(5, TimeUnit.SECONDS));
      authority.disarm();
      assertFalse(authority.allowed(() -> true));
      releaseCommit.countDown();
      older.get(5, TimeUnit.SECONDS);
      assertFalse(authority.allowed(() -> true));
    } finally {
      releaseCommit.countDown();
      worker.shutdownNow();
    }
  }
}
