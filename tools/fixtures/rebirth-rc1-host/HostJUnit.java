package com.deepseekharness.app.runtime;

import org.junit.runner.*;
import org.junit.runner.notification.*;

/** 只加可审计进度，不改测试、期望、超时或选择集合。 */
public final class HostJUnit {
  public static void main(String[] args) throws Exception {
    JUnitCore core = new JUnitCore();
    core.addListener(new RunListener() {
      private long started;
      public void testStarted(Description d) { started = System.nanoTime(); System.out.println("START " + d); }
      public void testFinished(Description d) { System.out.println("END " + d + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000); }
      public void testFailure(Failure f) { System.out.println(f.getTrace()); }
    });
    int count = 0, failures = 0, ignored = 0;
    for (String name : args) {
      Result result = core.run(Class.forName(name));
      count += result.getRunCount(); failures += result.getFailureCount(); ignored += result.getIgnoreCount();
      System.out.println(name + " tests=" + result.getRunCount() + " failures=" + result.getFailureCount() + " ignored=" + result.getIgnoreCount());
    }
    System.out.println("TOTAL tests=" + count + " failures=" + failures + " ignored=" + ignored);
    if (failures != 0 || ignored != 0) System.exit(1);
  }
}
