package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/** B2 接入：设备能力授权页的多段说明同一时刻只展开一段，收起后摘要仍可点。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = GrantsAccordionTest.App.class,
    shadows = GrantsAccordionTest.NoStorageManager.class,
    qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class GrantsAccordionTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  /** Robolectric 没有存储卷，原生 isExternalStorageManager 会越界；按「未授予」处理。 */
  @Implements(android.os.Environment.class)
  public static final class NoStorageManager {
    @Implementation
    protected static boolean isExternalStorageManager() {
      return false;
    }
  }

  public static final class Host extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
      FrameLayout content = new FrameLayout(this);
      content.setId(R.id.fragment_container);
      setContentView(content);
    }
  }

  private ActivityController<Host> host;
  private String previousLanguage;

  @Before
  public void setUp() {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
    host = Robolectric.buildActivity(Host.class).setup().visible();
    host.get()
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, new DeviceGrantsFragment())
        .commitNow();
    settle();
  }

  @After
  public void tearDown() {
    if (host != null) host.pause().stop().destroy();
    UiText.setLanguage(previousLanguage);
  }

  @Test
  public void openingAFileAccessNoteClosesTheChannelsNote() {
    View page = page();
    TextView channels = page.findViewById(R.id.grants_channels_label);
    TextView allFiles = page.findViewById(R.id.config_all_files_status);
    assertNotNull(channels);
    assertNotNull(allFiles);

    channels.performClick();
    settle();
    assertEquals(View.VISIBLE, detailVisibility(channels));
    assertEquals("已展开", channels.getStateDescription().toString());

    allFiles.performClick();
    settle();
    assertEquals(View.VISIBLE, detailVisibility(allFiles));
    assertEquals(View.GONE, detailVisibility(channels));
    assertEquals("已收起", channels.getStateDescription().toString());
  }

  @Test
  public void collapsingTheOpenNoteDoesNotOpenAnother() {
    View page = page();
    TextView allFiles = page.findViewById(R.id.config_all_files_status);
    TextView channels = page.findViewById(R.id.grants_channels_label);
    allFiles.performClick();
    settle();
    assertEquals(View.VISIBLE, detailVisibility(allFiles));
    allFiles.performClick();
    settle();
    assertEquals(View.GONE, detailVisibility(allFiles));
    assertEquals(View.GONE, detailVisibility(channels));
  }

  @Test
  public void disclosureSummariesStayClickableInTheAccordion() {
    View page = page();
    TextView channels = page.findViewById(R.id.grants_channels_label);
    TextView screenLabel = page.findViewById(R.id.grants_screen_label);
    assertTrue(channels.isClickable());
    assertTrue(screenLabel.isClickable());
    assertNotNull(channels.getCompoundDrawablesRelative()[2]);
    assertNotNull(screenLabel.getCompoundDrawablesRelative()[2]);
  }

  private View page() {
    View view =
        host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container).getView();
    assertNotNull(view);
    return view;
  }

  private void settle() {
    for (int pass = 0; pass < 3; pass++) {
      ShadowLooper.shadowMainLooper().idle();
      View root = host.get().getWindow().getDecorView();
      root.measure(
          View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
      root.layout(0, 0, 360, root.getMeasuredHeight());
    }
  }

  /** 摘要后面由 DisclosureCard 插入的详情容器第二格是详情本体。 */
  private static int detailVisibility(TextView summary) {
    if (!(summary.getParent() instanceof LinearLayout line)) return -1;
    if (line.getOrientation() == LinearLayout.HORIZONTAL) {
      // 状态胶囊与标题同一行时，详情容器由 DisclosureCard 插在这一行之后。
      if (!(line.getParent() instanceof LinearLayout outer)) return -1;
      int lineIndex = outer.indexOfChild(line);
      if (lineIndex < 0 || lineIndex + 1 >= outer.getChildCount()) return -1;
      if (!(outer.getChildAt(lineIndex + 1) instanceof LinearLayout holder)) return -1;
      if (holder.getChildCount() < 2) return -1;
      return holder.getChildAt(1).getVisibility();
    }
    int index = line.indexOfChild(summary);
    if (index < 0 || index + 1 >= line.getChildCount()) return -1;
    if (!(line.getChildAt(index + 1) instanceof LinearLayout holder)) return -1;
    if (holder.getChildCount() < 2) return -1;
    return holder.getChildAt(1).getVisibility();
  }
}
