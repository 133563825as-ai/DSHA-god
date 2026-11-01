package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class DshEngineChoiceTest {
  @Test
  public void missingOrEmptyKeepsTheExistingContainer() {
    assertEquals(DshEngineChoice.Kind.CONTAINER, DshEngineChoice.parse(null).kind);
    assertEquals(DshEngineChoice.Kind.CONTAINER, DshEngineChoice.parse("").kind);
    assertFalse(DshEngineChoice.parse(null).unrecognized);
    assertFalse(DshEngineChoice.parse("").unrecognized);
  }

  @Test
  public void knownValuesSelectTheirEngine() {
    assertTrue(DshEngineChoice.parse("ubuntu").isContainer());
    assertFalse(DshEngineChoice.parse("ubuntu").unrecognized);
    assertEquals(DshEngineChoice.Kind.NATIVE, DshEngineChoice.parse("native").kind);
    assertFalse(DshEngineChoice.parse("native").unrecognized);
  }

  @Test
  public void unknownValuesNeverSelectNativeAndAreFlagged() {
    for (String value : new String[] {"Native", " native", "native ", "android", "proot", "1"}) {
      DshEngineChoice choice = DshEngineChoice.parse(value);
      assertTrue(value, choice.isContainer());
      assertTrue(value, choice.unrecognized);
    }
  }

  @Test
  public void storedValuesRoundTrip() {
    for (DshEngineChoice.Kind kind : DshEngineChoice.Kind.values()) {
      DshEngineChoice choice = DshEngineChoice.parse(DshEngineChoice.stored(kind));
      assertEquals(kind, choice.kind);
      assertFalse(choice.unrecognized);
    }
    assertEquals("ubuntu", DshEngineChoice.stored(DshEngineChoice.Kind.CONTAINER));
    assertEquals("native", DshEngineChoice.stored(DshEngineChoice.Kind.NATIVE));
  }
}
