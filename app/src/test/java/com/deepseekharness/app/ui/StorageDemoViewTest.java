package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = StorageDemoViewTest.App.class, qualifiers = "w360dp-h800dp-mdpi")
public final class StorageDemoViewTest {
  public static final class App extends DshaApp { @Override public void onCreate() {} }
  private final List<Runnable> jobs = new ArrayList<>();
  private StorageDemoController owner;
  private StorageDemoController.Page page;
  private View root;
  @Before public void setup() {
    owner = new StorageDemoController(jobs::add);
    root = create();
  }
  private View create() {
    return StorageDemoFragment.build(new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
        R.style.Theme_DeepseekHarness), owner, value -> page = value);
  }
  @Test public void entryHasSyntheticLabel() {
    assertEquals(StorageDemoFragment.class, SettingsFragment.storageDemoPage().getClass());
    assertEquals("存储总览 · 合成演示",
        com.deepseekharness.app.contract.BuiltinModules.STORAGE_DEMO_CONTRIBUTION.title());
    assertTrue(words(root).contains("不读取设备目录"));
    assertTrue(words(root).contains("零业务读取"));
    assertEquals(0, owner.reads());
  }
  @Test public void loadingAndPartialAreReadable() {
    click("请求部分合成样例");
    assertTrue(words(root).contains("加载中"));
    assertEquals(0, owner.reads());
    jobs.remove(0).run();
    String text = words(root);
    assertTrue(text.contains("部分结果")); assertTrue(text.contains("1536 B"));
    assertTrue(text.contains("4096 B")); assertTrue(text.contains("未知（不是 0）"));
    for (String label : List.of("私有逻辑字节", "私有分配估算", "SAF 逻辑字节", "卷可用空间快照"))
      assertTrue(text.contains(label));
  }
  @Test public void emptyAndUnknownAreDistinct() {
    click("请求空合成样例"); jobs.remove(0).run();
    assertTrue(words(root).contains("空结果")); assertTrue(words(root).contains("0 B"));
    click("请求未知合成样例"); jobs.remove(0).run();
    assertTrue(words(root).contains("未知 · 合成样例"));
    assertTrue(words(root).contains("未知（不是 0）"));
  }
  @Test public void cancelAndReopenHaveNoLateViewReply() {
    click("请求部分合成样例"); click("取消合成请求");
    assertTrue(words(root).contains("已取消"));
    page.close(); View old = root; String before = words(old);
    root = create(); click("请求空合成样例");
    assertTrue(words(root).contains("等待旧合成任务可信退出"));
    String next = words(root); jobs.remove(0).run();
    assertEquals(before, words(old)); assertEquals(next, words(root));
    assertEquals(0, owner.reads());
    click("请求空合成样例"); jobs.remove(0).run();
    assertTrue(words(root).contains("空结果"));
  }
  @Test public void native360dpHas48dpTargets() {
    root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, 360, 800);
    List<Button> buttons = new ArrayList<>(); collect(root, buttons);
    assertEquals(4, buttons.size());
    for (Button button : buttons) {
      assertTrue(button.getMeasuredHeight() >= 48);
      assertTrue(button.getMeasuredWidth() >= 48);
      assertTrue(button.getRight() <= 360);
    }
  }
  private void click(String label) {
    List<Button> buttons = new ArrayList<>(); collect(root, buttons);
    for (Button button : buttons) if (label.contentEquals(button.getText())) {
      assertTrue(button.performClick()); return;
    }
    fail("Missing visible control: " + label);
  }
  private static void collect(View view, List<Button> result) {
    if (view instanceof Button) result.add((Button) view);
    if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
      collect(((ViewGroup) view).getChildAt(i), result);
  }
  private static String words(View view) {
    StringBuilder result = new StringBuilder();
    if (view instanceof TextView) result.append(((TextView) view).getText()).append('\n');
    if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
      result.append(words(((ViewGroup) view).getChildAt(i)));
    return result.toString();
  }
}
