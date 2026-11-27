package com.deepseekharness.app.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import com.deepseekharness.app.util.ThemeMotion;

/**
 * 主题切换过渡：圆形扩散、对角擦除、淡入淡出，或直接切换。
 *
 * <p>AppCompat 切换明暗会重建 Activity，无法原地渐变。做法：
 *
 * <ol>
 *   <li>切换前用 PixelCopy 截下旧主题整窗（含 WebView 等硬件层）；
 *   <li>截图到手立刻<b>静态</b>盖住旧窗口，再切换夜间模式——重建期间无论窗口是否沿用，都不会先露出新主题；
 *   <li>新页面领取遮罩，等它画完第一帧（布局最重的一帧）后才开始动画，避免开头几帧被吞掉、像跳帧回闪；
 *   <li>动画只向外铺开新主题，不回缩。
 * </ol>
 *
 * <p>截图按 Activity 类登记，超过 {@link ThemeMotion#CAPTURE_TTL_MS} 未领取即连同静态遮罩一起丢弃。系统关闭动画、
 * 截图失败或窗口未就绪时直接切换，不阻塞也不重试。
 */
final class ThemeReveal {
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static Pending pending;

  private record Pending(
      Class<? extends Activity> owner,
      Bitmap shot,
      float x,
      float y,
      String style,
      long capturedAt,
      Cover holding,
      ThemeChangeRequest.Request request,
      AutoCloseable retirement) {}

  private ThemeReveal() {}

  /**
   * 截下当前整窗、静态盖住后执行 {@code change}（通常是切换夜间模式）。直接切换或截图失败时立即执行。
   *
   * @param x 过渡起点（窗口坐标）：圆心，或对角擦除的最近角
   */
  static void capture(
      Activity activity,
      ThemeChangeRequest.Request request,
      float x,
      float y,
      String style,
      Runnable change) {
    discard();
    String kind = ThemeMotion.normalizeStyle(style);
    View decor = activity.getWindow().getDecorView();
    int width = decor.getWidth(), height = decor.getHeight();
    if (!ThemeMotion.needsCapture(kind)
        || !UiMotion.animationsEnabled(activity)
        || width <= 0
        || height <= 0
        || !decor.isAttachedToWindow()) {
      if (claim(activity, request)) change.run();
      return;
    }
    final Bitmap shot;
    try {
      shot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    } catch (OutOfMemoryError | IllegalArgumentException error) {
      if (claim(activity, request)) change.run();
      return;
    }
    Class<? extends Activity> owner = activity.getClass();
    java.util.concurrent.atomic.AtomicBoolean delivered =
        new java.util.concurrent.atomic.AtomicBoolean();
    try {
      PixelCopy.request(
          activity.getWindow(),
          shot,
          result -> {
            if (!delivered.compareAndSet(false, true)) return;
            if (!claim(activity, request)) {
              shot.recycle();
              return;
            }
            if (result != PixelCopy.SUCCESS) {
              shot.recycle();
              change.run();
              return;
            }
            // 先静态盖住旧窗口：重建若沿用同一窗口，这层就是新页面第一帧上的遮罩。
            Cover holding = new Cover(activity, shot, x, y, kind);
            ((ViewGroup) decor).addView(holding, new ViewGroup.LayoutParams(-1, -1));
            var registry =
                com.deepseekharness.app.contract.BuiltinModules.ensureRegistered(activity);
            var lease = com.deepseekharness.app.contract.BuiltinModules.themeLease(registry);
            AutoCloseable retirement =
                lease == null
                    ? () -> {}
                    : com.deepseekharness.app.contract.BuiltinModules.observeThemeRetirement(
                        registry,
                        lease,
                        () -> {
                          if (Looper.myLooper() == Looper.getMainLooper()) request.retirePending();
                          else MAIN.post(request::retirePending);
                        });
            Pending captured =
                new Pending(
                    owner,
                    shot,
                    x,
                    y,
                    kind,
                    SystemClock.uptimeMillis(),
                    holding,
                    request,
                    retirement);
            pending = captured;
            request.ownPending(
                () -> {
                  if (pending == captured) discard();
                });
            // 无人领取（未发生重建）时按时撤掉遮罩并释放约一屏 ARGB 的内存。
            MAIN.postDelayed(
                () -> {
                  if (pending == captured) discard();
                },
                ThemeMotion.CAPTURE_TTL_MS);
            if (!request.moduleActive()) request.retirePending();
            else change.run();
          },
          MAIN);
    } catch (RuntimeException error) {
      if (!delivered.compareAndSet(false, true)) return;
      if (!shot.isRecycled()) shot.recycle();
      if (claim(activity, request)) change.run();
    }
  }

  private static boolean claim(Activity activity, ThemeChangeRequest.Request request) {
    return request.claim(!activity.isFinishing() && !activity.isDestroyed());
  }

  /**
   * 重建后的同类页面在 setContentView 之后调用：领取遮罩，等首帧画完后开始过渡。返回是否已开始。
   *
   * <p>窗口被沿用时直接接管旧遮罩；窗口是新建的则补一层同样的静态遮罩，保证首帧仍是旧主题。
   */
  static boolean consume(Activity activity, ThemeChangeRequest changes) {
    Pending taken = pending;
    if (taken == null || !changes.samePage(taken.request)) return false;
    pending = null;
    releasePending(taken);
    if (!taken.request.moduleActive()
        || !changes.active()
        || activity.isFinishing()
        || activity.isDestroyed()
        || taken.owner != activity.getClass()
        || !ThemeMotion.captureFresh(taken.capturedAt, SystemClock.uptimeMillis())
        || !UiMotion.animationsEnabled(activity)) {
      taken.holding.remove();
      return false;
    }
    ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
    Cover cover = taken.holding;
    if (cover.getParent() != decor) {
      // 新窗口：旧遮罩随旧窗口离开，截图转交给新遮罩，不在此回收。
      cover.release(false);
      cover = new Cover(activity, taken.shot, taken.x, taken.y, taken.style);
      decor.addView(cover, new ViewGroup.LayoutParams(-1, -1));
    } else {
      // 沿用窗口：保持在最上层，盖住新内容。
      cover.bringToFront();
    }
    cover.startAfterFirstFrame(activity, changes);
    return true;
  }

  /** 丢弃未领取的截图，撤掉静态遮罩并释放内存。 */
  static void discard() {
    Pending stale = pending;
    pending = null;
    if (stale != null) {
      releasePending(stale);
      stale.holding.remove();
    }
  }

  private static void releasePending(Pending value) {
    value.request.ownPending(null);
    try {
      value.retirement.close();
    } catch (Exception ignored) {
    }
  }

  /**
   * 截图遮罩：用位图着色器绘制旧主题，按样式挖掉已露出新主题的区域；边缘抗锯齿。
   *
   * <p>动画期间吞掉触摸，避免点到半变色的界面。
   */
  private static final class Cover extends View {
    private final Bitmap shot;
    private final float cx, cy;
    private final String style;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint diagonal = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final BitmapShader image;
    private final Path shape = new Path();
    private final boolean rtl;
    private float progress;
    private boolean ownsShot = true, started, removed;
    private ThemeChangeRequest.Reveal reveal;
    private ViewTreeObserver firstFrameObserver;
    private ViewTreeObserver.OnPreDrawListener firstFrame;
    private Runnable postedStart;
    private ValueAnimator animator;

    Cover(Context context, Bitmap shot, float x, float y, String style) {
      super(context);
      this.shot = shot;
      cx = x;
      cy = y;
      this.style = style;
      image = new BitmapShader(shot, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
      paint.setShader(image);
      rtl =
          context.getResources().getConfiguration().getLayoutDirection()
              == View.LAYOUT_DIRECTION_RTL;
      shape.setFillType(Path.FillType.INVERSE_WINDING);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
      setClickable(true);
    }

    /** 新页面首帧完成后再起动画：首帧最重，过早开始会被吞掉前几帧。 */
    void startAfterFirstFrame(Activity owner, ThemeChangeRequest changes) {
      reveal = changes.ownReveal(this::remove);
      postedStart =
          () -> {
            if (removed) return;
            if (!reveal.start(
                !owner.isFinishing()
                    && !owner.isDestroyed()
                    && isAttachedToWindow()
                    && !shot.isRecycled())) return;
            start();
          };
      firstFrameObserver = getViewTreeObserver();
      firstFrame =
          () -> {
            clearFirstFrame();
            if (!removed) postOnAnimation(postedStart);
            return true;
          };
      firstFrameObserver.addOnPreDrawListener(firstFrame);
      invalidate();
    }

    private void clearFirstFrame() {
      if (firstFrame == null) return;
      if (firstFrameObserver.isAlive()) firstFrameObserver.removeOnPreDrawListener(firstFrame);
      // 附着时 observer 可能已合并到窗口树；也从当前活树撤回同一监听器。
      ViewTreeObserver current = getViewTreeObserver();
      if (current != firstFrameObserver && current.isAlive())
        current.removeOnPreDrawListener(firstFrame);
      firstFrame = null;
      firstFrameObserver = null;
    }

    private void start() {
      if (removed || started) return;
      started = true;
      animator = ValueAnimator.ofFloat(0f, 1f);
      animator.setDuration(ThemeMotion.durationMs(style));
      // 缓动在 ThemeMotion 里统一做（ease-out、单调不回缩）。
      animator.setInterpolator(new LinearInterpolator());
      animator.addUpdateListener(
          value -> {
            progress = (float) value.getAnimatedValue();
            invalidate();
          });
      animator.addListener(
          new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
              remove();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
              remove();
            }
          });
      animator.start();
    }

    /** 截图转交给另一层遮罩时不回收。 */
    void release(boolean recycle) {
      ownsShot = recycle;
      ViewGroup parent = (ViewGroup) getParent();
      if (parent != null) parent.removeView(this);
    }

    void remove() {
      if (removed) return;
      removed = true;
      clearFirstFrame();
      if (postedStart != null) removeCallbacks(postedStart);
      postedStart = null;
      if (animator != null) {
        animator.removeAllUpdateListeners();
        animator.removeAllListeners();
        animator.cancel();
        animator = null;
      }
      if (reveal != null) reveal.close();
      ViewGroup parent = (ViewGroup) getParent();
      if (parent != null) parent.removeView(this);
      if (ownsShot && !shot.isRecycled()) shot.recycle();
    }

    @Override
    protected void onDetachedFromWindow() {
      super.onDetachedFromWindow();
      // 仅已领取的遮罩在 detach 时终止；尚待跨窗口交接的 holding 仍由 Pending 管理。
      if (reveal != null) reveal.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
      if (shot.isRecycled()) return;
      float w = getWidth(), h = getHeight();
      float eased = ThemeMotion.revealFraction(progress);
      if (!started || progress <= 0f) {
        paint.setAlpha(255);
        canvas.drawRect(0, 0, w, h, paint);
        return;
      }
      switch (style) {
        case ThemeMotion.FADE:
          paint.setAlpha(Math.round(255 * (1f - eased)));
          canvas.drawRect(0, 0, w, h, paint);
          return;
        case ThemeMotion.DIAGONAL:
          {
            // 截图 × 斜向柔边渐变（DST_IN 取渐变透明度）：前沿垂直于屏幕对角线，没有锯齿硬边。
            float[] g = ThemeMotion.diagonalGradient(w, h, rtl, progress);
            Shader feather =
                new LinearGradient(
                    g[0], g[1], g[2], g[3], 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
            diagonal.setShader(new ComposeShader(image, feather, PorterDuff.Mode.DST_IN));
            canvas.drawRect(0, 0, w, h, diagonal);
            return;
          }
        default:
          {
            float radius = ThemeMotion.maxRadius(w, h, cx, cy) * eased;
            shape.reset();
            shape.setFillType(Path.FillType.INVERSE_WINDING);
            shape.addCircle(cx, cy, radius, Path.Direction.CW);
            paint.setAlpha(255);
            canvas.drawPath(shape, paint);
          }
      }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      return true;
    }
  }
}
