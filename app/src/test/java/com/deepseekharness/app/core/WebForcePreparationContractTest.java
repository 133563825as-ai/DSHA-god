package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.runtime.WebProcessManager;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.WebStopDiagnostic;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/** 三态结果与双语文案不授予启动或维护权限；不执行 Android / 进程操作。 */
public final class WebForcePreparationContractTest {
  private static final WebStopDiagnostic EXITED =
      WebStopDiagnostic.of(WebStopDiagnostic.Code.RECORDED_GUEST_ALREADY_EXITED);

  @After
  public void language() {
    UiText.setLanguage("zh");
  }

  @Test
  public void nonLiveCannotCarryRequestAndLiveCannotOmitIt() {
    for (var status : WebProcessManager.ForcePreparationStatus.values()) {
      if (status == WebProcessManager.ForcePreparationStatus.LIVE_CANDIDATE) {
        assertThrows(
            IllegalArgumentException.class,
            () -> new HarnessController.ForceStopPreparation(status, null, List.of(EXITED)));
      } else {
        assertNull(
            new HarnessController.ForceStopPreparation(status, null, List.of(EXITED)).request());
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new HarnessController.ForceStopPreparation(
                    status, new HarnessController.ForceStopRequest(7, null), List.of(EXITED)));
      }
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HarnessController.ForceStopPreparation(
                WebProcessManager.ForcePreparationStatus.LIVE_CANDIDATE,
                new HarnessController.ForceStopRequest(7, null),
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HarnessController.ForceStopPreparation(
                WebProcessManager.ForcePreparationStatus.UNKNOWN, null, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new HarnessController.ForceStopPreparation(null, null, List.of(EXITED)));
  }

  @Test
  public void diagnosticsAreImmutableAndGuestExitIsNotStopSuccess() {
    List<WebStopDiagnostic> facts = new ArrayList<>(List.of(EXITED));
    var prepared =
        new HarnessController.ForceStopPreparation(
            WebProcessManager.ForcePreparationStatus.ALREADY_EXITED, null, facts);
    facts.clear();
    assertEquals(List.of(EXITED), prepared.diagnostics());
    assertThrows(UnsupportedOperationException.class, () -> prepared.diagnostics().clear());
    var pending =
        new WebStopCoordinator.Result(
            WebStopCoordinator.Status.UNCONFIRMED,
            "WEB_PROCESS_EXIT_UNCONFIRMED",
            prepared.diagnostics());
    assertFalse(pending.stopped());
    assertTrue(pending.unconfirmedStop());
  }

  @Test
  public void exitedTextRequiresLauncherAndBackgroundVerificationInBothLanguages() {
    UiText.setLanguage("zh");
    String zh = WebForceStopText.recordedGuestExited();
    assertTrue(zh.contains("Web 进程已确认退出；停止尚未完成"));
    assertTrue(zh.contains("容器启动器或后台任务"));
    assertTrue(zh.contains("未发送强制结束信号"));
    assertTrue(zh.contains("重新核验停止"));
    assertFalse(zh.contains("身份不符"));
    assertTrue(WebStopText.render(EXITED).contains("停止尚未完成"));
    UiText.setLanguage("en");
    String en = WebForceStopText.recordedGuestExited();
    assertTrue(en.contains("stop is not complete"));
    assertTrue(en.contains("launcher or background tasks still need verification"));
    assertTrue(en.contains("No force-stop signal was sent"));
    assertTrue(en.contains("Re-check Stop"));
    assertTrue(WebStopText.render(EXITED).contains("no signal was sent"));
    assertNotEquals(zh, en);
  }
}
