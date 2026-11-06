package com.deepseekharness.app.ui;

import android.content.Context;
import com.deepseekharness.app.core.DiagnosticLog;
import com.deepseekharness.app.util.WebPageDiagnosticText;

/**
 * K0 证据：把 WebView 页面创建、销毁与 keeper 摘挂写成脱敏诊断行。
 *
 * <p>只记录原因码、代次、进程号与生命周期布尔事实；页面地址、cookie 与 token 不进入这里。
 */
final class WebPageDiagnostics {
  private WebPageDiagnostics() {}

  static void engine(Context context, long generation, String browserInfo) {
    DiagnosticLog.record(
        context,
        "WEB_ENGINE",
        WebPageDiagnosticText.engine(android.os.Process.myPid(), generation)
            + " webview="
            + String.valueOf(browserInfo));
  }

  static void pageDestroy(
      Context context, String reason, boolean changingConfig, boolean finishing, long generation) {
    DiagnosticLog.record(
        context,
        "WEB_PAGE_DESTROY",
        WebPageDiagnosticText.pageDestroy(reason, changingConfig, finishing, generation));
  }

  static void keepAlive(
      Context context, String action, String reason, long generation, boolean ready) {
    DiagnosticLog.record(
        context,
        "WEB_KEEPALIVE_" + action,
        WebPageDiagnosticText.keepAlive(action, reason, generation, ready));
  }
}
