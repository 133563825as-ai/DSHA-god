package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = StorageDemoDisplayTest.App.class,
    qualifiers = "w360dp-h480dp-mdpi")
public final class StorageDemoDisplayTest {
  public static final class App extends DshaApp { @Override public void onCreate() {} }
  private final String previousLanguage = UiText.language();
  private final List<Runnable> jobs = new ArrayList<>();
  private StorageDemoController.Page page;

  @After public void restore() {
    if (page != null) page.close();
    UiText.setLanguage(previousLanguage);
  }

  @Test public void darkShortLargeEnglishUsesAppLanguageAndKeepsControlsReachable() {
    // Deliberately disagree with resource locale: production text uses UiText via LanguageController.
    UiText.setLanguage("en");
    Context context = context(Configuration.UI_MODE_NIGHT_YES, 1.6f, Locale.CHINESE);
    Context light = context(Configuration.UI_MODE_NIGHT_NO, 1f, Locale.CHINESE);
    assertNotEquals("Night must select a different canvas token", light.getColor(R.color.shell_bg),
        context.getColor(R.color.shell_bg));
    StorageDemoController owner = new StorageDemoController(jobs::add);
    View root = StorageDemoFragment.build(context, owner, value -> page = value);
    assertTrue(words(root).contains("Storage overview · Synthetic demo"));
    assertTrue(words(root).contains("Zero business reads"));
    assertTrue(words(root).contains("No device directories, permissions or cleanup"));
    assertFalse("English negative control must reject hardcoded Chinese", words(root).matches("(?s).*[\\p{IsHan}].*"));
    assertEquals(0, owner.reads());
    checkLayoutAndTokens(root, context);

    click(root, "Request partial synthetic sample");
    assertTrue(words(root).contains("Loading · Synthetic data only"));
    assertEquals(0, owner.reads());
    jobs.remove(0).run();
    assertTrue(words(root).contains("Partial result · Synthetic sample"));
    assertTrue(words(root).contains("1536 B"));
    assertTrue(words(root).contains("4096 B"));
    assertTrue(words(root).contains("Unknown (not zero)"));
    assertTrue(words(root).contains("Partial/incomplete"));
    click(root, "Request empty synthetic sample"); jobs.remove(0).run();
    assertTrue(words(root).contains("Empty result · Synthetic sample"));
    assertTrue(words(root).contains("0 B"));
    click(root, "Request unknown synthetic sample"); jobs.remove(0).run();
    assertTrue(words(root).contains("Unknown · Synthetic sample"));
    assertFalse(words(root).contains("0 B"));
    assertFalse(words(root).matches("(?s).*[\\p{IsHan}].*"));
    checkLayoutAndTokens(root, context);
  }

  @Test public void lightChineseRemainsDistinctFromEnglishWithNoBusinessRead() {
    UiText.setLanguage("zh");
    Context context = context(Configuration.UI_MODE_NIGHT_NO, 1f, Locale.ENGLISH);
    StorageDemoController owner = new StorageDemoController(jobs::add);
    View root = StorageDemoFragment.build(context, owner, value -> page = value);
    assertTrue(words(root).contains("存储总览 · 合成演示"));
    assertTrue(words(root).contains("未请求 · 零业务读取"));
    assertFalse(words(root).contains("Storage overview"));
    assertEquals(0, owner.reads());
    checkLayoutAndTokens(root, context);
  }

  private static Context context(int night, float scale, Locale locale) {
    Context app = RuntimeEnvironment.getApplication();
    Configuration config = new Configuration(app.getResources().getConfiguration());
    config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
    config.fontScale = scale;
    config.setLocale(locale);
    Context result = new ContextThemeWrapper(app.createConfigurationContext(config),
        R.style.Theme_DeepseekHarness);
    assertEquals(night, result.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
    assertEquals(scale, result.getResources().getConfiguration().fontScale, 0.001f);
    return result;
  }

  private static void checkLayoutAndTokens(View root, Context context) {
    root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, 360, 480);
    assertEquals(context.getColor(R.color.shell_bg), ((ColorDrawable) root.getBackground()).getColor());
    List<TextView> texts = new ArrayList<>(); collect(root, texts);
    assertEquals(context.getColor(R.color.shell_text), texts.get(0).getCurrentTextColor());
    assertEquals("Heading retains sp sizing (including SDK34 non-linear font scaling)",
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 26,
            context.getResources().getDisplayMetrics()), texts.get(0).getTextSize(), 0.5f);
    if (context.getResources().getConfiguration().fontScale > 1f)
      assertTrue(texts.get(0).getTextSize() > 26f);
    ScrollView scroll = (ScrollView) ((ViewGroup) root).getChildAt(0);
    assertTrue("Short screen must really overflow", scroll.getChildAt(0).getHeight() > scroll.getHeight());
    int buttons = 0;
    Button last = null;
    for (TextView text : texts) {
      assertNotNull(text.getLayout());
      assertTrue("No clipped last text line: " + text.getText(),
          text.getLayout().getLineBottom(text.getLineCount() - 1) <=
              text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
      if (text instanceof Button) {
        buttons++; last = (Button) text;
        assertTrue(text.getWidth() >= 48); assertTrue(text.getHeight() >= 48);
        Rect bounds = new Rect(0, 0, text.getWidth(), text.getHeight());
        scroll.offsetDescendantRectToMyCoords(text, bounds);
        assertTrue(bounds.left >= 0); assertTrue(bounds.right <= 360);
      }
    }
    assertEquals(4, buttons);
    scroll.setSmoothScrollingEnabled(false);
    scroll.fullScroll(View.FOCUS_DOWN);
    Rect bounds = new Rect(0, 0, last.getWidth(), last.getHeight());
    scroll.offsetDescendantRectToMyCoords(last, bounds);
    assertTrue("Last action reachable above scroll bottom", bounds.bottom <= scroll.getScrollY() + scroll.getHeight());
    assertTrue(bounds.top >= scroll.getScrollY());
  }

  private static void click(View root, String label) {
    List<TextView> texts = new ArrayList<>(); collect(root, texts);
    for (TextView text : texts) if (text instanceof Button && label.contentEquals(text.getText())) {
      assertTrue(text.performClick()); return;
    }
    fail("Missing control: " + label);
  }
  private static void collect(View view, List<TextView> texts) {
    if (view instanceof TextView) texts.add((TextView) view);
    if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
      collect(((ViewGroup) view).getChildAt(i), texts);
  }
  private static String words(View root) {
    List<TextView> texts = new ArrayList<>(); collect(root, texts);
    StringBuilder result = new StringBuilder();
    for (TextView text : texts) result.append(text.getText()).append('\n');
    return result.toString();
  }
}
