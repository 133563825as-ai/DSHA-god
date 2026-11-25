package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.plugins.PluginManagementEngine;
import com.deepseekharness.app.plugins.PluginOperationQueue;
import com.deepseekharness.app.util.PluginInstallLink;
import com.deepseekharness.app.util.PluginSource;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.UiText;

/** 网页只提供来源；本地检查、脚本授权、真正提交是三个不同的用户动作。 */
public final class PluginInstallActivity extends AppCompatActivity {
  private PluginManagementEngine repository;
  private PluginInstallLink request;
  private PluginSource source;
  private com.deepseekharness.app.plugins.PluginRegistry.Snapshot shownCatalog;
  private String operationId;
  private Button install;
  private TextView status, details;
  private CheckBox scripts;
  private boolean blockedGesture;

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    setContentView(R.layout.activity_plugin_install);
    PressFeedback.attachButtons(findViewById(android.R.id.content));
    status = findViewById(R.id.link_install_status);
    details = findViewById(R.id.link_install_details);
    install = findViewById(R.id.link_install_confirm);
    status.setSaveEnabled(false);
    details.setSaveEnabled(false);
    install.setSaveEnabled(false);
    operationId = saved == null ? null : saved.getString("pluginOperationId");
    findViewById(R.id.link_install_close).setOnClickListener(v -> finish());
    findViewById(R.id.link_install_manage).setOnClickListener(v -> openMain(true));
    try {
      request = PluginInstallLink.parse(getIntent().getDataString());
      if (request.builtin.isEmpty()) {
        source = PluginSource.parse(request.url);
        details.setSingleLine(false);
        details.setEllipsize(null);
        details.setText(UiText.raw(disclosure()));
      }
    } catch (IllegalArgumentException error) {
      status.setText(
          UiText.choose(
              "无法安全显示此安装来源。请在插件管理中手动选择。",
              "This source cannot be displayed safely. Select it manually in plugin management."));
      install.setEnabled(false);
      return;
    }
    if (!request.builtin.isEmpty()) {
      status.setText(UiText.format("请在插件管理中查看 %s 的安装和启用状态", request.builtin));
      install.setText(UiText.text("打开插件管理"));
      install.setOnClickListener(v -> openMain(true));
      return;
    }
    scripts = new CheckBox(this);
    scripts.setSaveEnabled(false);
    scripts.setText(
        UiText.choose(
            "仅本次允许插件和依赖运行安装脚本（含 pnpm hooks）",
            "Allow plugin and dependency install scripts (including pnpm hooks) for this attempt only"));
    scripts.setVisibility(View.GONE);
    LinearLayout parent = (LinearLayout) install.getParent();
    parent.addView(scripts, parent.indexOfChild(install));
    scripts.setOnCheckedChangeListener((button, checked) -> install.setEnabled(checked));
    showCheck();
  }

  private String disclosure() {
    String target;
    if (!source.npm.isEmpty()) target = "npm: " + source.npm;
    else if (!source.url.isEmpty()) {
      // 不把签名查询、片段或凭据印到屏幕；也不在截断、脱敏的地址上接受授权。
      java.net.URI uri = java.net.URI.create(source.url);
      if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null)
        throw new IllegalArgumentException();
      target = source.url;
    } else {
      var ref = source.github;
      target =
          "GitHub: "
              + ref.owner
              + "/"
              + ref.repo
              + " · "
              + ref.kind
              + ": "
              + (ref.treePath.isEmpty() ? ref.revision : ref.treePath);
    }
    // 固定标签与外部值分行；无法无歧义展示的来源只拒绝，不改写后继续安装。
    String values = target + request.name + request.version;
    if (target.length() > 1024
        || !values.matches("[\\x20-\\x7e·]+")
        || !SensitiveData.redact(values).equals(values)) throw new IllegalArgumentException();
    return UiText.choose("请求来源：", "Requested source: ")
        + target
        + (request.name.isEmpty()
            ? ""
            : "\n" + UiText.choose("要求名称：", "Required name: ") + request.name)
        + (request.version.isEmpty()
            ? ""
            : "\n" + UiText.choose("要求版本：", "Required version: ") + request.version)
        + "\nSHA-256: "
        + (request.sha256.isEmpty()
            ? UiText.choose("链接未指定", "not pinned by the link")
            : request.sha256)
        + "\n\n"
        + UiText.choose(
            "确认后将联网下载插件及依赖，安装或替换包内所有匹配的插件并启用。依赖版本可能重新解析；脚本可在安装完成前以 DSHA 的权限执行并读写应用数据。Web 可能停止并重启。仅在信任此来源时继续。",
            "Confirming downloads the plugin and dependencies, installs or replaces all matching plugins in the package, and enables them. Dependencies may change. Scripts can run before installation completes and read or write app data with DSHA’s permissions. Web may stop and restart. Continue only if you trust this source.");
  }

  private boolean interactive() {
    return !isFinishing()
        && !isDestroyed()
        && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
  }

  private void showCheck() {
    scripts.setVisibility(View.GONE);
    scripts.setChecked(false);
    status.setText(
        operationId == null
            ? UiText.choose(
                "尚未请求下载。可先检查本机环境。", "No download requested. Check the local environment first.")
            : UiText.choose(
                "已有一次提交；请核对状态，不会重放。",
                "A previous request was submitted. Check its status; it will not replay."));
    install.setText(UiText.choose("检查本机状态", "Check local status"));
    install.setEnabled(true);
    install.setOnClickListener(
        v -> {
          if (!interactive()) return;
          if (!HarnessController.get(this).isEnvironmentReady() && operationId == null) {
            status.setText(
                UiText.choose(
                    "请先初始化 DSHA。完成后仍需重新确认安装。",
                    "Complete DSHA setup first. Installation still requires fresh confirmation afterward."));
            install.setText(UiText.text("初始化 DSHA"));
            install.setOnClickListener(
                setup -> {
                  if (!interactive()) return;
                  getSharedPreferences("dsha-install-link", MODE_PRIVATE)
                      .edit()
                      .putString("pending", getIntent().getDataString())
                      .apply();
                  openMain(false);
                  finish();
                });
            return;
          }
          if (repository == null) {
            repository = PluginManagementEngine.get(this);
            repository.state().observe(this, ignored -> renderOperation());
          }
          repository.ensureLoaded();
          renderOperation();
        });
  }

  private void renderOperation() {
    var operation = operationId == null ? null : repository.operation(operationId);
    if (operationId != null
        && (operation == null
            || operation.pending()
            || operation.phase == PluginOperationQueue.Phase.UNCONFIRMED
            || operation.phase == PluginOperationQueue.Phase.TIMEOUT
            || operation.phase == PluginOperationQueue.Phase.SUCCEEDED)) {
      scripts.setChecked(false);
      scripts.setVisibility(View.GONE);
      install.setEnabled(false);
      install.setOnClickListener(null);
      install.setText(UiText.choose("已提交", "Submitted"));
      status.setText(
          operation != null && operation.pending()
              ? UiText.text("正在安装")
              : operation != null
                      && operation.phase == PluginOperationQueue.Phase.SUCCEEDED
                      && operation.transactionId != null
                      && !operation.transactionId.isEmpty()
                  ? UiText.text("安装完成")
                  : UiText.choose(
                      "结果未确认，请到插件管理核对。不会重放安装。",
                      "Result unconfirmed. Check plugin management; installation will not replay."));
      return;
    }
    var catalog = repository.registry().snapshot();
    if (shownCatalog != catalog) scripts.setChecked(false);
    shownCatalog = catalog;
    if (!catalog.known || catalog.source == null || catalog.stale || catalog.loading) {
      showCheck();
      status.setText(
          UiText.choose(
              "本机插件状态未就绪；可重新检查，不会下载插件。",
              "Local plugin status is not ready. Check again; this does not download the plugin."));
      return;
    }
    status.setText(
        operation == null
            ? UiText.choose("请核对来源及以下操作。", "Review the source and effects below.")
            : UiText.choose(
                "上次未完成。重试前请核对来源并重新授权。",
                "The previous attempt did not complete. Review the source and authorize again to retry."));
    scripts.setVisibility(View.VISIBLE);
    install.setText(UiText.choose("下载、安装并启用", "Download, install and enable"));
    install.setEnabled(scripts.isChecked());
    install.setOnClickListener(
        v -> {
          if (!interactive() || !scripts.isChecked() || !install.isEnabled()) return;
          scripts.setChecked(false);
          if (repository.registry().snapshot() != catalog) {
            renderOperation();
            return;
          }
          // inspect 不是预览：它先准备依赖（可执行脚本），再自动提交整个包。
          operationId = repository.inspect(source, request.sha256, request.name, request.version);
          renderOperation();
        });
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) blockedGesture = false;
    if ((event.getFlags()
                & (MotionEvent.FLAG_WINDOW_IS_OBSCURED
                    | MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED))
            != 0
        && !blockedGesture) {
      blockedGesture = true;
      if (scripts != null) scripts.setChecked(false);
      MotionEvent cancel = MotionEvent.obtain(event);
      cancel.setAction(MotionEvent.ACTION_CANCEL);
      super.dispatchTouchEvent(cancel);
      cancel.recycle();
    }
    return blockedGesture || super.dispatchTouchEvent(event);
  }

  @Override
  protected void onPause() {
    if (scripts != null) scripts.setChecked(false);
    super.onPause();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    // 外部新请求不可复用旧页面的来源、按钮或同意。
    finish();
  }

  @Override
  protected void onSaveInstanceState(Bundle saved) {
    super.onSaveInstanceState(saved);
    if (operationId != null) saved.putString("pluginOperationId", operationId);
  }

  private void openMain(boolean plugins) {
    startActivity(MainActivity.navigationIntent(this).putExtra("open_plugins", plugins));
  }
}
