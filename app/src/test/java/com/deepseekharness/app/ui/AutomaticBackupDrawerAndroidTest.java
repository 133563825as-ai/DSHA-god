package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.provider.Settings;
import android.text.Layout;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.backup.AutomaticBackups;
import com.deepseekharness.app.backup.BackupCopyPreflight;
import com.deepseekharness.app.backup.BackupFileSystem.Node;
import com.deepseekharness.app.backup.NativeBackupJobs;
import com.deepseekharness.app.backup.NativeBackupJobs.PreflightRequest;
import com.deepseekharness.app.backup.VerifiedBackupCopy;
import com.deepseekharness.app.util.UiText;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

/** 只验 Android 页面对合成记录的呈现与请求归属；不启动应用服务、读写备份或运行读屏软件。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = AutomaticBackupDrawerAndroidTest.NoStartup.class,
    qualifiers = "en-rUS-w360dp-h740dp-port-mdpi",
    shadows = {
      AutomaticBackupDrawerAndroidTest.Jobs.class,
      AutomaticBackupDrawerAndroidTest.Request.class,
      AutomaticBackupDrawerAndroidTest.NoSchedule.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class AutomaticBackupDrawerAndroidTest {
  public static final class NoStartup extends Application {}

  private static final long CREATED = 1700000000000L;
  private static final String CHECK = "Check other copies (read-only)";
  private static final String READY =
      "Not checked. Local automatic copies are read only on request. Closing these details cancels this check.";
  private static final String CHECKING = "Checking read-only; no result yet.";
  private static final String RESTORE = "Inspect for restore",
      EXPORT = "Export backup without a password";
  private static final String TRIAL_SCOPE =
      "In this trial you can check local automatic copies without changing them. Restore and export outside the app are unavailable. The last refresh does not prove a copy can be restored.";
  private static final String UNINSTALL =
      "Uninstalling removes local copies and their key. This trial cannot export backups outside the app or restore from them.";

  // 用实际宿主身份作独立对照，不以页面按钮是否存在或被测能力位决定期望结果。
  private boolean trialIdentity() {
    return "dsh.android.client.rebirth".equals(host.get().getPackageName());
  }

  private List<String> expectedActions() {
    return trialIdentity() ? List.of(CHECK, "Close") : List.of(CHECK, RESTORE, EXPORT, "Close");
  }

  @Implements(value = AutomaticBackups.class, isInAndroidSdk = false)
  public static final class NoSchedule {
    @Implementation
    protected static void schedule(Context ignored) {
      throw new AssertionError("unexpected schedule");
    }

    @Implementation
    protected static void request(Context ignored, boolean manual) {
      throw new AssertionError("unexpected backup");
    }
  }

  @Implements(value = NativeBackupJobs.class, isInAndroidSdk = false)
  public static final class Jobs {
    static NativeBackupJobs instance;
    static NativeBackupJobs.State idle;
    static NativeBackupJobs.Copies list;
    static MutableLiveData<NativeBackupJobs.State> changes;
    static final LinkedBlockingQueue<Thread> readers = new LinkedBlockingQueue<>();
    static int checks;
    static java.lang.reflect.Constructor<PreflightRequest> newRequest;
    static PreflightRequest pending;
    static Consumer<PreflightRequest> completion;

    @Implementation
    protected void __constructor__(Context ignored) {} // 禁止生产构造器读取历史任务。

    @Implementation
    protected static NativeBackupJobs get(Context ignored) {
      return instance;
    }

    @Implementation
    protected LiveData<NativeBackupJobs.State> changes() {
      return changes;
    }

    @Implementation
    protected NativeBackupJobs.State state() {
      return idle;
    }

    @Implementation
    protected NativeBackupJobs.Copies verifiedCopies() {
      readers.add(Thread.currentThread());
      return list;
    }

    @Implementation
    protected PreflightRequest inspectCopy(
        VerifiedBackupCopy selected, Consumer<PreflightRequest> ready) {
      assertSame(list.valid.get(0), selected);
      checks++;
      try {
        // 真实构造器只保存 read 字段；close/snapshot 在本测试中由 Request 截获。
        pending = newRequest.newInstance((Object) null);
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError(failure);
      }
      completion = ready;
      return pending;
    }
  }

  /** 即使取消也允许测试人工投递迟到通知，专门验证页面自身的身份检查。 */
  @Implements(value = PreflightRequest.class, isInAndroidSdk = false)
  public static final class Request {
    boolean closed;

    @Implementation
    protected void close() {
      closed = true;
    }

    @Implementation
    protected BackupCopyPreflight.Result snapshot() {
      return new BackupCopyPreflight.Result("INSUFFICIENT", 2);
    }
  }

  private ActivityController<AutomaticBackupActivity> host;
  private String previousLanguage;

  @Before
  public void setUp() throws Exception {
    previousLanguage = UiText.language();
    UiText.setLanguage("en");
    var jobs = NativeBackupJobs.class.getDeclaredConstructor(Context.class);
    jobs.setAccessible(true);
    Jobs.instance = jobs.newInstance((Object) null); // 仅走 Jobs.__constructor__，不提供文件上下文。
    Jobs.newRequest =
        PreflightRequest.class.getDeclaredConstructor(
            Class.forName("com.deepseekharness.app.backup.WorkerQuiescence$Read"));
    Jobs.newRequest.setAccessible(true);
    Jobs.changes = new MutableLiveData<>();
    Jobs.readers.clear();
    Jobs.checks = 0;
    Jobs.pending = null;
    Jobs.completion = null;
    var state =
        NativeBackupJobs.State.class.getDeclaredConstructor(
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            long.class,
            long.class,
            boolean.class);
    state.setAccessible(true);
    Jobs.idle = state.newInstance("", "IDLE", "", "", "", 0L, 0L, false);
    var copy =
        VerifiedBackupCopy.class.getDeclaredConstructor(
            String.class, File.class, Node.class, Node.class, byte[].class);
    copy.setAccessible(true);
    String proof =
        "{\"encryptedSha256\":\""
            + "0".repeat(64)
            + "\",\"encryptedBytes\":128,\"entries\":1,"
            + "\"createdAt\":"
            + CREATED
            + ",\"integrity\":\"QUIESCENT\",\"requestedScope\":\"application\",\"automatic\":true}";
    var record =
        copy.newInstance(
            "00000000-0000-4000-8000-000000000001",
            null,
            new Node("DIRECTORY", "dir", 0, 0, 0, 0),
            new Node("FILE", "data", 128, 0, 0, 0),
            proof.getBytes(StandardCharsets.UTF_8));
    var copies = NativeBackupJobs.Copies.class.getDeclaredConstructor(List.class, Map.class);
    copies.setAccessible(true);
    Jobs.list = copies.newInstance(List.of(record), Map.of());
    Settings.Global.putFloat(
        RuntimeEnvironment.getApplication().getContentResolver(),
        Settings.Global.ANIMATOR_DURATION_SCALE,
        0f);
  }

  @After
  public void tearDown() {
    if (host != null) host.pause().stop().destroy();
    UiText.setLanguage(previousLanguage);
    RuntimeEnvironment.setFontScale(1f);
  }

  private void launch() throws Exception {
    host = Robolectric.buildActivity(AutomaticBackupActivity.class).setup();
    settleList();
  }

  /** 合成结果仍由页面自己的列表线程投递；等到该线程已发完消息才 drain，不靠睡眠猜时间。 */
  private void settleList() throws Exception {
    var reading = AutomaticBackupActivity.class.getDeclaredField("reading");
    reading.setAccessible(true);
    // onCreate、onResume 都会刷新；某些调度次序下第二轮已经开始，不能点上一轮留下的行。
    do {
      Thread reader = Jobs.readers.poll(5, TimeUnit.SECONDS);
      assertNotNull("list refresh must reach synthetic records", reader);
      assertNotSame(Thread.currentThread(), reader);
      reader.join(5000);
      assertFalse("list worker did not finish", reader.isAlive());
      ShadowLooper.shadowMainLooper().idle();
    } while (reading.getBoolean(host.get()) || !Jobs.readers.isEmpty());
  }

  private View copyRow() {
    String stamp = android.text.format.DateFormat.format("MM-dd HH:mm", CREATED).toString();
    View row = text(host.get().getWindow().getDecorView(), stamp);
    while (!row.isClickable()) row = (View) row.getParent();
    return row;
  }

  private AlertDialog open() {
    assertTrue(copyRow().performClick());
    ShadowLooper.shadowMainLooper().idle();
    var dialog = (AlertDialog) ShadowDialog.getLatestDialog();
    assertNotNull(dialog);
    assertTrue(dialog.isShowing());
    text(decor(dialog), READY);
    return dialog;
  }

  private static View decor(AlertDialog dialog) {
    return dialog.getWindow().getDecorView();
  }

  private static TextView find(View root, String value) {
    if (root instanceof TextView tv && value.contentEquals(tv.getText())) return tv;
    if (root instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        TextView found = find(group.getChildAt(i), value);
        if (found != null) return found;
      }
    return null;
  }

  private static TextView text(View root, String value) {
    var found = find(root, value);
    assertNotNull(value, found);
    return found;
  }

  private static TextView start(AlertDialog dialog) {
    assertEquals(0, Jobs.checks);
    assertTrue(text(decor(dialog), CHECK).performClick());
    assertEquals(1, Jobs.checks);
    assertFalse(Shadow.<Request>extract(Jobs.pending).closed);
    return text(decor(dialog), CHECKING);
  }

  private void assertAbandoned(AlertDialog old, TextView oldStatus) {
    assertFalse(old.isShowing());
    assertTrue(Shadow.<Request>extract(Jobs.pending).closed);
    // 向生产页面的回调注入延迟完成；此处不是 Android 事件，也不是实际摘要核验。
    Jobs.completion.accept(Jobs.pending);
    assertEquals(CHECKING, oldStatus.getText().toString());
    assertEquals(1, Jobs.checks);
    assertNull(Shadow.<ShadowActivity>extract(host.get()).getNextStartedActivity());
  }

  @Test
  public void backKeyDownAndUpClosesOnlyModalAndCancelsCheck() throws Exception {
    launch();
    var dialog = open();
    var status = start(dialog);
    assertTrue(dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)));
    assertTrue(dialog.isShowing());
    assertTrue(dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)));
    ShadowLooper.shadowMainLooper().idle();
    assertAbandoned(dialog, status);
    assertNoOwnedDetails(host.get());
    assertFalse(host.get().isFinishing());
  }

  @Test
  public void stopThenForegroundDoesNotRestoreModalOrReplayItsRequest() throws Exception {
    launch();
    var original = host.get();
    var dialog = open();
    var status = start(dialog);
    host.pause().stop();
    assertFalse(dialog.isShowing());
    assertNoOwnedDetails(original);
    assertTrue(Shadow.<Request>extract(Jobs.pending).closed);
    host.restart().resume().visible();
    settleList();
    assertSame(original, host.get());
    assertNoOwnedDetails(host.get());
    assertNoModal();
    assertAbandoned(dialog, status);
    assertFreshManualOpen();
  }

  @Test
  public void landscapeRecreatesActivityAndDropsModalAndRequest() throws Exception {
    launch();
    var original = host.get();
    var dialog = open();
    var status = start(dialog);
    var rotated = new Configuration(original.getResources().getConfiguration());
    assertEquals(Configuration.ORIENTATION_PORTRAIT, rotated.orientation);
    rotated.orientation = Configuration.ORIENTATION_LANDSCAPE;
    rotated.screenWidthDp = 740;
    rotated.screenHeightDp = 360;
    host.configurationChange(rotated);
    settleList();
    assertNotSame(original, host.get());
    assertTrue(original.isDestroyed());
    assertNoOwnedDetails(original);
    assertNoOwnedDetails(host.get());
    assertEquals(
        Configuration.ORIENTATION_LANDSCAPE,
        host.get().getResources().getConfiguration().orientation);
    assertNoModal();
    assertAbandoned(dialog, status);
    assertFreshManualOpen();
  }

  private static void assertNoOwnedDetails(AutomaticBackupActivity owner) throws Exception {
    for (String name : List.of("copyDrawer", "copyRead")) {
      var field = AutomaticBackupActivity.class.getDeclaredField(name);
      field.setAccessible(true);
      assertNull(name, field.get(owner));
    }
  }

  private static void assertNoModal() {
    var latest = ShadowDialog.getLatestDialog();
    assertFalse(latest != null && latest.isShowing());
  }

  private void assertFreshManualOpen() {
    var fresh = open();
    Jobs.completion.accept(Jobs.pending);
    text(decor(fresh), READY);
    assertEquals("opening details alone is not a read request", 1, Jobs.checks);
  }

  @Test
  public void copyRoutesMatchInstalledVariantAndDoNotStartBackupJobs() throws Exception {
    launch();
    assertEquals(
        "built trial flag must match independently identified app",
        trialIdentity(),
        com.deepseekharness.app.BuildConfig.REBIRTH_CANDIDATE);
    View page = host.get().getWindow().getDecorView();
    text(page, "Backup timing");
    text(page, "Create automatic backup now");
    View row = copyRow();
    boolean canReveal = row.performAccessibilityAction(SwipeAction.OPEN_ACTION, null);
    if (trialIdentity()) {
      assertFalse("no swipe or accessibility shortcut to restricted actions", canReveal);
      assertNull(find(page, RESTORE));
      assertNull(find(page, "Export")); // 包括隐藏的行内动作。
      text(page, UNINSTALL);
      var dialog = open();
      text(decor(dialog), TRIAL_SCOPE);
      assertNull(find(decor(dialog), RESTORE));
      assertNull(find(decor(dialog), EXPORT));
      var checking = start(dialog);
      assertTrue(text(decor(dialog), "Close").performClick());
      ShadowLooper.shadowMainLooper().idle();
      assertAbandoned(dialog, checking);
      assertNoOwnedDetails(host.get());
    } else {
      assertTrue("regular copy row must offer its actions", canReveal);
      assertNull(find(page, UNINSTALL));
      for (String label : List.of(RESTORE, "Export")) {
        if (label.equals("Export"))
          assertTrue(row.performAccessibilityAction(SwipeAction.OPEN_ACTION, null));
        assertTrue(text(page, label).performClick());
        assertCopyHandoff(label.equals(RESTORE) ? "auto_restore_id" : "auto_export_id");
      }
      for (String label : List.of(RESTORE, EXPORT)) {
        var dialog = open();
        assertNull(find(decor(dialog), TRIAL_SCOPE));
        assertTrue(text(decor(dialog), label).performClick());
        assertCopyHandoff(label.equals(RESTORE) ? "auto_restore_id" : "auto_export_id");
      }
      assertEquals(0, Jobs.checks);
    }
    assertNull(Shadow.<ShadowActivity>extract(host.get()).getNextStartedActivity());
  }

  private void assertCopyHandoff(String extra) {
    var intent = Shadow.<ShadowActivity>extract(host.get()).getNextStartedActivity();
    assertNotNull(extra, intent);
    assertEquals(NativeDataActivity.class.getName(), intent.getComponent().getClassName());
    assertEquals(Jobs.list.valid.get(0).id, intent.getStringExtra(extra));
  }

  @Test
  public void accessibilityNodesHaveNamedActionsAndPoliteChangingStatus() throws Exception {
    launch();
    var dialog = open();
    for (String name : expectedActions()) {
      var node = text(decor(dialog), name).createAccessibilityNodeInfo();
      CharSequence label =
          node.getContentDescription() != null ? node.getContentDescription() : node.getText();
      assertEquals(name, label.toString());
      assertTrue(name, node.isClickable() && node.isEnabled());
      assertTrue(
          name,
          node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
    }
    var status = text(decor(dialog), READY);
    assertLive(status, READY);
    start(dialog);
    assertLive(status, CHECKING);
    Jobs.completion.accept(Jobs.pending);
    assertLive(status, "At the time of this check, fewer than 3 other copies qualified: 2");
  }

  private static void assertLive(TextView status, String message) {
    var node = status.createAccessibilityNodeInfo();
    assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, node.getLiveRegion());
    assertEquals(message, node.getText().toString()); // 元数据，不代表实际 TalkBack 播报。
  }

  @Test
  @Config(qualifiers = "en-rUS-w360dp-h400dp-port-mdpi")
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  public void shortScreenLargeFontActionsFitAndReadOnlyTargetIsAtLeast48dp() throws Exception {
    RuntimeEnvironment.setFontScale(1.3f);
    launch();
    View root = decor(open());
    assertEquals(1.3f, root.getResources().getConfiguration().fontScale, 0.001f);
    assertTrue("short window", root.getHeight() > 0 && root.getHeight() <= 400);
    var check = (Button) text(root, CHECK);
    View ancestor = (View) check.getParent();
    while (!(ancestor instanceof ScrollView)) ancestor = (View) ancestor.getParent();
    var scroll = (ScrollView) ancestor;
    scroll.scrollTo(0, 0);
    assertTrue("details must overflow the short viewport", scroll.canScrollVertically(1));
    scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
    assertTrue("details must actually scroll", scroll.getScrollY() > 0);
    for (String name : expectedActions()) assertFits((Button) text(root, name));
    assertEquals(0, Jobs.checks);
  }

  private static void assertFits(Button button) {
    String name = button.getText().toString();
    int min = Math.round(48 * button.getResources().getDisplayMetrics().density);
    Rect visible = new Rect();
    assertTrue(name, button.getLocalVisibleRect(visible));
    assertEquals(name + " clipped", new Rect(0, 0, button.getWidth(), button.getHeight()), visible);
    // 既有页脚保留实际高度供单独审查；本项 48dp 验收目标仅为新增核验按钮。
    float density = button.getResources().getDisplayMetrics().density;
    System.out.println(
        "AutomaticBackupDrawer target: "
            + name
            + " "
            + visible.width() / density
            + " x "
            + visible.height() / density
            + " dp");
    if (CHECK.equals(name))
      assertTrue(
          name + " actual target " + visible, visible.width() >= min && visible.height() >= min);
    Layout layout = button.getLayout();
    assertNotNull(name, layout);
    int n = layout.getLineCount();
    assertTrue(name, n > 0);
    assertEquals(name, name.length(), layout.getLineEnd(n - 1));
    int width =
        button.getWidth() - button.getCompoundPaddingLeft() - button.getCompoundPaddingRight();
    int height =
        button.getHeight() - button.getCompoundPaddingTop() - button.getCompoundPaddingBottom();
    assertTrue(name + " vertically clipped", layout.getHeight() <= height);
    for (int i = 0; i < n; i++) {
      assertEquals(name + " ellipsized", 0, layout.getEllipsisCount(i));
      assertTrue(
          name + " horizontally clipped",
          layout.getLineLeft(i) >= -1 && layout.getLineRight(i) <= width + 1);
    }
  }
}
