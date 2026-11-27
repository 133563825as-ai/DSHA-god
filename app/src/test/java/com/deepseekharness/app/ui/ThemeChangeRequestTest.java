package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 截图可晚于下一次选择/页面销毁；只有当前活页的一次回调能提交。 */
public final class ThemeChangeRequestTest {
  @Test
  public void equalModeSelectionRetiresAnEarlierCaptureWithoutStartingAnother() {
    ThemeChangeRequest changes = new ThemeChangeRequest();
    ThemeChangeRequest.Request dark = changes.begin();
    // 当前偏好仍是 system：选择同有效明暗的 light，直接保存，不再截图。
    changes.retire();
    String selected = "light";
    if (dark.claim(true)) selected = "dark";
    assertEquals("light", selected);
  }

  @Test
  public void olderCallbackCannotTakeTheNewerRequestsCommit() {
    ThemeChangeRequest changes = new ThemeChangeRequest();
    ThemeChangeRequest.Request old = changes.begin();
    ThemeChangeRequest.Request latest = changes.begin();
    assertFalse(old.claim(true));
    assertTrue(latest.claim(true));
    assertFalse(old.claim(true));
  }

  @Test
  public void lateOldCallbackCannotOverwriteAnAlreadyCommittedNewChoice() {
    ThemeChangeRequest changes = new ThemeChangeRequest();
    ThemeChangeRequest.Request dark = changes.begin();
    ThemeChangeRequest.Request light = changes.begin();
    String selected = "system";
    if (light.claim(true)) selected = "light";
    if (dark.claim(true)) selected = "dark";
    assertEquals("light", selected);
  }

  @Test
  public void finishingOrDestroyedOwnerCannotCommitEvenIfItsRequestIsLatest() {
    ThemeChangeRequest changes = new ThemeChangeRequest();
    ThemeChangeRequest.Request pending = changes.begin();
    assertFalse(pending.claim(false));
    // 失败的 owner 检查也消费该请求，不能随后补交。
    assertFalse(pending.claim(true));
  }

  @Test
  public void pageDestructionRetiresPendingWorkWithoutAReplacement() {
    ThemeChangeRequest changes = new ThemeChangeRequest();
    ThemeChangeRequest.Request pending = changes.begin();
    changes.retire();
    changes.retire();
    assertFalse(pending.claim(true));
  }

  @Test
  public void successFailureOrExceptionFallbackCanCommitAtMostOnce() {
    ThemeChangeRequest changes = new ThemeChangeRequest();
    ThemeChangeRequest.Request pending = changes.begin();
    int submissions = 0;
    if (pending.claim(true)) submissions++;
    if (pending.claim(true)) submissions++;
    if (pending.claim(true)) submissions++;
    assertEquals(1, submissions);
  }

  @Test
  public void consumedButNeverStartedRevealClosesOnceOnDestroy() {
    ThemeChangeRequest page = new ThemeChangeRequest();
    int[] releases = {0};
    ThemeChangeRequest.Reveal reveal = page.ownReveal(() -> releases[0]++);
    page.retire();
    page.retire();
    reveal.close();
    assertFalse(reveal.start(true));
    assertEquals(1, releases[0]);
  }

  @Test
  public void latePostedStartFromOldGenerationCannotStartOrClearNewReveal() {
    ThemeChangeRequest page = new ThemeChangeRequest();
    int[] oldReleases = {0}, newReleases = {0};
    ThemeChangeRequest.Reveal old = page.ownReveal(() -> oldReleases[0]++);
    ThemeChangeRequest.Reveal latest = page.ownReveal(() -> newReleases[0]++);
    assertFalse(old.start(true));
    old.close();
    assertTrue(latest.start(true));
    assertEquals(1, oldReleases[0]);
    assertEquals(0, newReleases[0]);
    latest.close();
    assertEquals(1, newReleases[0]);
  }

  @Test
  public void finishingOrDetachedBeforeStartReleasesWithoutStarting() {
    ThemeChangeRequest page = new ThemeChangeRequest();
    int[] releases = {0};
    ThemeChangeRequest.Reveal reveal = page.ownReveal(() -> releases[0]++);
    assertFalse(reveal.start(false));
    assertFalse(reveal.start(true));
    assertEquals(1, releases[0]);
  }

  @Test
  public void startedRevealKeepsOwnershipUntilEndOrCancel() {
    ThemeChangeRequest page = new ThemeChangeRequest();
    int[] releases = {0};
    ThemeChangeRequest.Reveal reveal = page.ownReveal(() -> releases[0]++);
    assertTrue(reveal.start(true));
    assertFalse(reveal.start(true));
    assertEquals(0, releases[0]);
    reveal.close();
    reveal.close();
    page.retire();
    assertEquals(1, releases[0]);
  }

  @Test
  public void newerCaptureRetiresConsumedRevealWithoutLosingItsOwnClaim() {
    ThemeChangeRequest page = new ThemeChangeRequest();
    int[] releases = {0};
    ThemeChangeRequest.Reveal old = page.ownReveal(() -> releases[0]++);
    ThemeChangeRequest.Request latest = page.begin();
    assertFalse(old.start(true));
    assertEquals(1, releases[0]);
    assertTrue(latest.claim(true));
  }

  @Test
  public void destroyOldOwnerDoesNotCloseNewOwnersConsumedReveal() {
    ThemeChangeRequest oldPage = new ThemeChangeRequest();
    ThemeChangeRequest newPage = new ThemeChangeRequest();
    int[] oldReleases = {0}, newReleases = {0};
    ThemeChangeRequest.Reveal old = oldPage.ownReveal(() -> oldReleases[0]++);
    ThemeChangeRequest.Reveal latest = newPage.ownReveal(() -> newReleases[0]++);
    oldPage.retire();
    assertFalse(old.start(true));
    assertTrue(latest.start(true));
    assertEquals(1, oldReleases[0]);
    assertEquals(0, newReleases[0]);
  }

  @Test
  public void cleanupReentryCannotReleaseTwice() {
    ThemeChangeRequest page = new ThemeChangeRequest();
    int[] releases = {0};
    ThemeChangeRequest.Reveal[] slot = new ThemeChangeRequest.Reveal[1];
    slot[0] =
        page.ownReveal(
            () -> {
              releases[0]++;
              slot[0].close();
              page.retire();
            });
    slot[0].close();
    assertEquals(1, releases[0]);
  }

  @Test
  public void destroyedOldPageCannotRetireAReplacementPagesChoice() {
    ThemeChangeRequest oldPage = new ThemeChangeRequest();
    ThemeChangeRequest.Request old = oldPage.begin();
    ThemeChangeRequest newPage = new ThemeChangeRequest();
    ThemeChangeRequest.Request current = newPage.begin();
    oldPage.retire();
    assertFalse(old.claim(true));
    assertTrue(current.claim(true));
  }
}
