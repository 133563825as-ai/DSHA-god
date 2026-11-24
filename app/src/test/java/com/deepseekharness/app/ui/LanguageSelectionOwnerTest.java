package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import org.junit.Test;

public final class LanguageSelectionOwnerTest {
  @Test public void capturedOwnerAndDialogAreSingleUse() {
    LanguageSelectionOwner owner = new LanguageSelectionOwner();
    int[] writes = {0};
    LanguageSelectionOwner.Guard live = write -> { write.run(); return true; };
    var first = owner.open(live);
    var second = owner.open(live);
    assertFalse(first.commit(() -> writes[0]++));
    assertTrue(second.commit(() -> writes[0]++));
    assertFalse(second.commit(() -> writes[0]++));
    var stopped = owner.open(live);
    owner.invalidate();
    assertFalse(stopped.commit(() -> writes[0]++));
    var denied = owner.open(write -> false);
    assertFalse(denied.commit(() -> writes[0]++));
    assertEquals(1, writes[0]);
  }
}
