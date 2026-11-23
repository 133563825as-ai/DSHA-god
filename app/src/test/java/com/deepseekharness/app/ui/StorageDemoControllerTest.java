package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class StorageDemoControllerTest {
  @Test public void unvisitedAndOpenReadNothing() {
    List<Runnable> jobs = new ArrayList<>();
    StorageDemoController owner = new StorageDemoController(jobs::add);
    assertEquals(0, owner.reads());
    owner.open(state -> assertTrue(state.status.contains("零业务读取")));
    assertEquals(0, owner.reads());
    assertTrue(jobs.isEmpty());
  }
  @Test public void cancelHoldsProcessSlotUntilExit() {
    List<Runnable> jobs = new ArrayList<>();
    StorageDemoController owner = new StorageDemoController(jobs::add);
    var page = owner.open(s -> {});
    assertTrue(page.request(StorageDemoController.Sample.PARTIAL));
    page.cancel();
    assertEquals(com.deepseekharness.app.storage.model.StorageModel.Phase.CANCELLING, owner.phase());
    assertFalse(owner.open(s -> {}).request(StorageDemoController.Sample.EMPTY));
    jobs.remove(0).run();
    assertEquals(0, owner.reads());
    assertTrue(owner.open(s -> {}).request(StorageDemoController.Sample.EMPTY));
  }
  @Test public void reopenRejectsLateReply() {
    List<Runnable> jobs = new ArrayList<>();
    List<StorageDemoController.State> old = new ArrayList<>(), fresh = new ArrayList<>();
    StorageDemoController owner = new StorageDemoController(jobs::add);
    var page = owner.open(old::add);
    page.request(StorageDemoController.Sample.PARTIAL);
    page.close();
    var next = owner.open(fresh::add);
    int oldCount = old.size(), freshCount = fresh.size();
    jobs.remove(0).run();
    assertEquals(oldCount, old.size()); assertEquals(freshCount, fresh.size());
    assertEquals(0, owner.reads());
    assertTrue(next.request(StorageDemoController.Sample.EMPTY));
  }
  @Test public void fourLedgersRemainIndependent() {
    List<Runnable> jobs = new ArrayList<>();
    List<StorageDemoController.State> states = new ArrayList<>();
    StorageDemoController owner = new StorageDemoController(jobs::add);
    owner.open(states::add).request(StorageDemoController.Sample.PARTIAL);
    jobs.remove(0).run();
    var report = states.get(states.size() - 1).report;
    assertEquals(1536, report.privateByBucket().get("demo").logical().observed().getAsLong());
    assertEquals(4096, report.privateByBucket().get("demo").allocatedEstimate().observed().getAsLong());
    assertFalse(report.safByProvider().get("synthetic").observed().isPresent());
    assertFalse(report.volumeAvailable().get("synthetic-volume").observed().isPresent());
    assertTrue(report.privateByBucket().get("demo").logical().partial());
  }
}
