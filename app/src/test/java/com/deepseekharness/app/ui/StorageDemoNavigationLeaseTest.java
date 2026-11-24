package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import com.deepseekharness.app.storage.model.StorageModel;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Plain JVM companion: the lifecycle calls used by the native route cannot free a live worker. */
public final class StorageDemoNavigationLeaseTest {
  @Test public void stoppedPageCannotPublishAcrossRecreatedRoute() {
    List<Runnable> jobs = new ArrayList<>();
    List<StorageDemoController.State> old = new ArrayList<>(), fresh = new ArrayList<>();
    StorageDemoController owner = new StorageDemoController(jobs::add);
    StorageDemoController.Page page = owner.open(old::add);
    assertTrue("LEASE_INITIAL_REQUEST", page.request(StorageDemoController.Sample.PARTIAL));
    page.cancel(); // Fragment.onStop
    page.close(); // Fragment.onDestroyView
    int oldCount = old.size();
    StorageDemoController.Page restored = owner.open(fresh::add);
    assertEquals("LEASE_OLD_WORKER_STILL_OWNS_SLOT", StorageModel.Phase.CANCELLING, owner.phase());
    assertFalse("LEASE_RECREATED_ROUTE_CANNOT_OVERLAP", restored.request(StorageDemoController.Sample.EMPTY));
    int freshCount = fresh.size();
    assertEquals("LEASE_EXACTLY_ONE_PENDING_WORKER", 1, jobs.size());
    jobs.remove(0).run();
    assertEquals("LEASE_NO_RETIRED_RENDER", oldCount, old.size());
    assertEquals("LEASE_NO_CROSS_ROUTE_RENDER", freshCount, fresh.size());
    assertEquals("LEASE_RETIRED_WORKER_ZERO_READS", 0, owner.reads());
    assertTrue("LEASE_RETRY_ONLY_AFTER_EXIT", restored.request(StorageDemoController.Sample.EMPTY));
    jobs.remove(0).run();
    assertEquals("LEASE_ONE_FRESH_READ", 1, owner.reads());
    assertTrue("LEASE_FRESH_EMPTY_RESULT", fresh.get(fresh.size() - 1).status.contains("空结果"));
    assertEquals("LEASE_OLD_VIEW_STAYS_RETIRED", oldCount, old.size());
  }
}
