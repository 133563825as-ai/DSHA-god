package com.deepseekharness.app.util;

import java.util.function.BooleanSupplier;

/** Process-local Root authority. Persisted settings alone never arm a new process. */
public final class RootSettingsAuthority {
  private boolean armed;
  private DeviceShellPolicy.Mode policy = DeviceShellPolicy.Mode.STRICT;
  private long changeGeneration;

  public static final class Change {
    public final long generation;
    public final boolean wasArmed;

    private Change(long generation, boolean wasArmed) {
      this.generation = generation;
      this.wasArmed = wasArmed;
    }
  }

  public synchronized boolean allowed(BooleanSupplier persistedRootEnabled) {
    if (!armed) return false;
    try {
      return persistedRootEnabled.getAsBoolean();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  public synchronized DeviceShellPolicy.Mode policy() {
    return policy;
  }

  /** Stop new Root dispatch before asynchronous persistence begins. */
  public synchronized Change disarm() {
    boolean wasArmed = armed;
    armed = false;
    if (changeGeneration == Long.MAX_VALUE)
      throw new IllegalStateException("ROOT_SETTINGS_GENERATION_EXHAUSTED");
    return new Change(++changeGeneration, wasArmed);
  }

  public void saveRoot(boolean enabled, BooleanSupplier durableSave) {
    saveRoot(disarm(), enabled, durableSave);
  }

  public void saveRoot(Change change, boolean enabled, BooleanSupplier durableSave) {
    requireCurrent(change);
    requireSaved(durableSave);
    synchronized (this) {
      requireCurrent(change);
      armed = enabled;
    }
  }

  public void savePolicy(DeviceShellPolicy.Mode requested, BooleanSupplier durableSave) {
    savePolicy(disarm(), requested, durableSave);
  }

  public void savePolicy(
      Change change, DeviceShellPolicy.Mode requested, BooleanSupplier durableSave) {
    requireCurrent(change);
    requireSaved(durableSave);
    synchronized (this) {
      requireCurrent(change);
      policy = requested == null ? DeviceShellPolicy.Mode.STRICT : requested;
      armed = change.wasArmed;
    }
  }

  private synchronized void requireCurrent(Change change) {
    if (change == null || change.generation != changeGeneration)
      throw new IllegalStateException("ROOT_SETTINGS_CHANGE_SUPERSEDED");
  }

  private static void requireSaved(BooleanSupplier durableSave) {
    if (!durableSave.getAsBoolean())
      throw new IllegalStateException("ROOT_SETTINGS_PERSISTENCE_FAILED");
  }
}
