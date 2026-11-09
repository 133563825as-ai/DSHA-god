package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.File;
import org.junit.Test;

public class EngineStateFilesTest {
  private static final File ROOTFS = new File("/data/user/0/x/files/linux/ubuntu");

  @Test
  public void containerFilesAreExactlyTheLegacyRootfsPaths() {
    File state = EngineStateFiles.containerStateDirectory(ROOTFS);
    // 旧写法：new File(rootfs, WebProcSel.pidFileRel(WebProcSel.STOP_SENTINEL)) 等。
    assertEquals(
        new File(ROOTFS, WebProcSel.pidFileRel(WebProcSel.STOP_SENTINEL)),
        EngineStateFiles.stopSentinel(state));
    assertEquals(
        new File(ROOTFS, WebProcSel.pidFileRel(WebProcSel.PID_WEB)), EngineStateFiles.pid(state));
    assertEquals(
        new File(ROOTFS, WebProcSel.pidFileRel(WebProcSel.IDENTITY_WEB)),
        EngineStateFiles.identity(state));
    // 旧写法：new File(rootfs, "root/.dsha-stopped")、new File(new File(rootfs, "root"),
    // ".dsha-web.pid")。
    assertEquals(new File(ROOTFS, "root/.dsha-stopped"), EngineStateFiles.stopSentinel(state));
    assertEquals(new File(new File(ROOTFS, "root"), ".dsha-web.pid"), EngineStateFiles.pid(state));
    assertEquals(
        new File(new File(ROOTFS, "root"), ".dsha-web.identity"), EngineStateFiles.identity(state));
  }

  @Test
  public void fileNamesMatchTheGuestPathsTheLaunchCommandWrites() {
    String guestState = "/" + EngineStateFiles.CONTAINER_STATE_RELATIVE + "/";
    assertEquals(guestState + EngineStateFiles.STOP_SENTINEL, WebProcSel.STOP_SENTINEL);
    assertEquals(guestState + EngineStateFiles.PID, WebProcSel.PID_WEB);
    assertEquals(guestState + EngineStateFiles.IDENTITY, WebProcSel.IDENTITY_WEB);
  }

  @Test
  public void launchCommandStillWritesAndChecksTheSameGuestFiles() {
    String command = WebLaunchCommand.build(false, "zh", "web", 0, 1);
    assertTrue(command.contains("echo $$ > " + WebProcSel.PID_WEB));
    assertTrue(command.contains("rm -f " + WebProcSel.IDENTITY_WEB));
    assertTrue(command.contains("[ ! -e " + WebProcSel.STOP_SENTINEL + " ] || exit 0;"));
  }

  @Test
  public void everyFileStaysDirectlyInsideTheStateDirectory() {
    File state = new File("/s");
    for (File file :
        new File[] {
          EngineStateFiles.stopSentinel(state),
          EngineStateFiles.pid(state),
          EngineStateFiles.identity(state)
        }) {
      assertEquals(file.getPath(), state, file.getParentFile());
      assertFalse(file.getName(), file.getName().contains("/"));
      assertFalse(file.getName(), file.getName().contains(".."));
    }
  }
}
