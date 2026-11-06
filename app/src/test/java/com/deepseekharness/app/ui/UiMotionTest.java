package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.ContentResolver;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;

/** 状态文本淡入与数值过渡：正常时按 150/200ms 生效，系统关闭动画时直接落到静态值。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = UiMotionTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class UiMotionTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private ContentResolver resolver;

  @Before
  public void setUp() {
    resolver = RuntimeEnvironment.getApplication().getContentResolver();
    animationScales(1f);
  }

  @After
  public void tearDown() {
    animationScales(1f);
  }

  private void animationScales(float value) {
    Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, value);
    Settings.Global.putFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, value);
  }

  private static TextView view(String text) {
    TextView view = new TextView(RuntimeEnvironment.getApplication());
    view.setText(text);
    return view;
  }

  @Test
  public void crossFadeTextLeavesEqualTextUntouched() {
    TextView view = view("DSH 已就绪");
    UiMotion.crossFadeText(view, "DSH 已就绪");
    assertEquals("DSH 已就绪", view.getText().toString());
    assertEquals(1f, view.getAlpha(), 0.001f);
  }

  @Test
  public void crossFadeTextFallsBackToStaticWhenAnimationsDisabled() {
    animationScales(0f);
    TextView view = view("启动中");
    UiMotion.crossFadeText(view, "已就绪");
    assertEquals("已就绪", view.getText().toString());
    assertEquals(1f, view.getAlpha(), 0.001f);
  }

  @Test
  public void crossFadeTextWritesNewValueImmediatelyAndFadesIn() {
    TextView view = view("启动中");
    UiMotion.crossFadeText(view, "已就绪");
    // 先写值再淡入：刷新很快时也不会停在旧文字上。
    assertEquals("已就绪", view.getText().toString());
    assertEquals(0f, view.getAlpha(), 0.001f);
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    assertEquals(1f, view.getAlpha(), 0.001f);
  }

  @Test
  public void crossFadeTextToleratesNullViewAndText() {
    UiMotion.crossFadeText(null, "任意文字");
    TextView view = view("旧值");
    UiMotion.crossFadeText(view, null);
    assertEquals("", view.getText().toString());
  }

  @Test
  public void animateCounterFallsBackToStaticWhenAnimationsDisabled() {
    animationScales(0f);
    TextView view = view("2");
    UiMotion.animateCounter(view, 10);
    assertEquals("10", view.getText().toString());
  }

  @Test
  public void animateCounterSkipsEqualValue() {
    TextView view = view("10");
    animationScales(0f);
    UiMotion.animateCounter(view, 10);
    assertEquals("10", view.getText().toString());
  }

  @Test
  public void animateCounterWritesValueDirectlyWhenCurrentTextIsNotANumber() {
    TextView view = view("—");
    UiMotion.animateCounter(view, 7);
    assertEquals("7", view.getText().toString());
  }

  @Test
  public void animateCounterStepsThroughValuesAndEndsOnTarget() {
    TextView view = view("0");
    UiMotion.animateCounter(view, 10);
    assertEquals("0", view.getText().toString());
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    assertEquals("10", view.getText().toString());
  }

  @Test
  public void animateCounterSupportsDecimals() {
    TextView view = view("1.0");
    UiMotion.animateCounter(view, 2.5f);
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    assertEquals("2.5", view.getText().toString());
  }
}
