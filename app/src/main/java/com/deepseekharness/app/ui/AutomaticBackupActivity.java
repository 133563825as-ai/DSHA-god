package com.deepseekharness.app.ui;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.content.Intent;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.util.AutomaticBackupPolicy;
import com.deepseekharness.app.util.UiText;

/** 自动计划、本机副本与原有恢复预检共用同一条数据路径。 */
public final class AutomaticBackupActivity extends AppCompatActivity {
  private CardPage page;
  private TextView state;
  private LinearLayout copies;
  private boolean reading;
  private FiniteSkeleton skeleton;
  private boolean skeletonPlayed;
  private BottomSheetCard.Sheet copyDrawer;
  private NativeBackupJobs.PreflightRequest copyRead;

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }

  static String stage(String value) {
    return switch (value) {
      case "PREPARING" -> t("准备数据", "Preparing data");
      case "CAPTURING" -> t("保存并核验文件", "Saving and verifying files");
      case "WRITING" -> t("写入并验证无密码副本", "Writing and verifying an unencrypted copy");
      case "ENCRYPTING" -> t("加密并验证副本", "Encrypting and verifying the copy");
      case "EXPORTING" -> t("完成副本记录", "Finishing the copy record");
      default -> t("正在处理", "Working");
    };
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    render();
    NativeBackupJobs.get(this)
        .changes()
        .observe(
            this,
            value -> {
              if (state == null) return;
              if (value.busy) {
                closeCopyDrawer();
                FadeSwap.text(
                    state, UiText.format("备份任务进行中：%s · %s 项", stage(value.stage), value.entries));
              } else refresh();
            });
  }

  private void render() {
    page =
        new CardPage(
            this,
            t("自动备份", "Automatic backups"),
            BuildConfig.REBIRTH_CANDIDATE
                ? t(
                    "试用版仅创建本机加密自动副本，不备份 Ubuntu 或个人项目。",
                    "This trial creates encrypted automatic copies on this device only. Ubuntu and personal projects are not included.")
                : t(
                    "默认开启。备份对话、附件、配置和插件；不重复备份 Ubuntu。个人项目请使用手动备份。",
                    "On by default. Saves conversations, attachments, settings and plugins, without copying Ubuntu. Use manual backup for personal projects."));
    UiNavigation.addHeader(this, page.root, t("自动备份", "Automatic backups"), this::finish);
    // 页首只留「默认开启」这一句；备份范围、不重复 Ubuntu 与手动备份建议收进 ⓘ。
    InfoHint.attach(page.subtitle, t("自动备份", "Automatic backups"));
    var prefs = AutomaticBackups.prefs(this);
    LinearLayout settings = page.card();
    var enabled = new com.google.android.material.materialswitch.MaterialSwitch(this);
    enabled.setText(t("自动备份", "Automatic backups"));
    enabled.setChecked(AutomaticBackups.enabled(this));
    settings.addView(enabled);
    enabled.setOnCheckedChangeListener(
        (button, value) -> {
          prefs.edit().putBoolean("enabled", value).commit();
          AutomaticBackups.schedule(this);
          refresh();
        });
    String mode = prefs.getString("mode", "daily");
    String[] names = {
      t("每天指定时间", "Daily at a chosen time"),
      t("每隔一段时间", "At an interval"),
      t("停止 DSH 后", "After stopping DSH")
    };
    page.entry(
        settings,
        t("备份时机", "Backup timing"),
        names["interval".equals(mode) ? 1 : "stop".equals(mode) ? 2 : 0],
        R.drawable.ic_ui2_box,
        () ->
            CardSheet.choices(
                this,
                t("备份时机", "Backup timing"),
                () -> java.util.Arrays.asList(names),
                index -> {
                  prefs
                      .edit()
                      .putString("mode", new String[] {"daily", "interval", "stop"}[index])
                      .putLong("anchor", System.currentTimeMillis())
                      .commit();
                  render();
                }));
    if ("daily".equals(mode)) {
      int minute = prefs.getInt("minute", 180);
      page.entry(
          settings,
          t("每天时间", "Daily time"),
          String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60),
          R.drawable.ic_ui2_box,
          () ->
              new TimePickerDialog(
                      this,
                      (picker, h, m) -> {
                        prefs.edit().putInt("minute", h * 60 + m).commit();
                        render();
                      },
                      minute / 60,
                      minute % 60,
                      true)
                  .show());
    } else if ("interval".equals(mode)) {
      page.entry(
          settings,
          t("间隔小时", "Interval in hours"),
          String.valueOf(prefs.getInt("hours", 24)),
          R.drawable.ic_ui2_box,
          () -> {
            EditText hours = new EditText(this);
            hours.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            hours.setText(String.valueOf(prefs.getInt("hours", 24)));
            var dialog =
                new DshaDialogBuilder(this)
                    .setTitle(t("间隔 1–168 小时", "Interval: 1–168 hours"))
                    .setView(hours)
                    .setPositiveButton(t("保存", "Save"), null)
                    .setNegativeButton(t("取消", "Cancel"), null)
                    .create();
            dialog.setOnShowListener(
                d ->
                    dialog
                        .getButton(-1)
                        .setOnClickListener(
                            v -> {
                              try {
                                int value = Integer.parseInt(hours.getText().toString());
                                if (value < 1 || value > 168) throw new NumberFormatException();
                                prefs.edit().putInt("hours", value).commit();
                                dialog.dismiss();
                                render();
                              } catch (NumberFormatException invalid) {
                                hours.setError(t("请输入 1–168", "Enter 1–168"));
                              }
                            }));
            dialog.show();
          });
    }
    TextView timingHint =
        page.text(
            t(
                "到期时仍在运行则延后，不会停止对话或终端。时间由系统调度，可能略有延迟。轮换时保留最近 3 份已完成且校验通过的自动副本；本次正在收尾的副本另计。关闭后取消后续任务，已有副本保留。",
                "If DSH or a terminal is running, backup waits. Android scheduling may delay the selected time. Rotation keeps the latest 3 finished, verified automatic copies; the copy being finalized is separate. Turning off cancels future jobs and preserves existing copies."),
            12,
            R.color.text_secondary);
    // 行内只留「到期仍在运行则延后」；调度延迟与保留策略收进 ⓘ。
    InfoHint.attach(timingHint, t("备份时机", "Backup timing"));
    settings.addView(timingHint);
    TextView localHint =
        page.text(
            BuildConfig.REBIRTH_CANDIDATE
                ? t(
                    "卸载会清除本机副本和密钥。试用版不能将备份导出到应用外，也不能从备份恢复。",
                    "Uninstalling removes local copies and their key. This trial cannot export backups outside the app or restore from them.")
                : t(
                    "自动副本与本机密钥在卸载时会清除。换机或卸载前，请导出无需密码的 tar.gz 并保存到应用外。",
                    "Local copies and their device key are removed when the app is uninstalled. Export a tar.gz without a password and save it outside the app before moving or uninstalling."),
            11,
            R.color.text_muted);
    localHint.setPadding(0, page.dp(10), 0, 0);
    // 行内只留卸载会清除；换机前导出无密码备份的做法收进 ⓘ。
    if (!BuildConfig.REBIRTH_CANDIDATE)
      InfoHint.attach(localHint, t("本机副本 · 保留最近 3 份", "Local copies · latest 3 kept"));
    settings.addView(localHint);
    state = page.text("", 12, R.color.text_secondary);
    page.content.addView(state);
    page.label(t("本机副本 · 保留最近 3 份", "Local copies · latest 3 kept"));
    copies = page.card();
    page.button(
        page.footer,
        t("现在创建自动备份", "Create automatic backup now"),
        true,
        () -> {
          if (NativeBackupJobs.get(this).state().busy) {
            state.setText(t("备份任务进行中，请等待完成。", "A backup task is running. Wait for it to finish."));
            return;
          }
          if (!AutomaticBackups.enabled(this)) {
            state.setText(t("请先打开自动备份", "Enable automatic backups first"));
            return;
          }
          AutomaticBackups.request(this, true);
          state.setText(
              t(
                  "已安排，稍后查看结果",
                  "Scheduled. DSH and terminals must be stopped. Refresh shortly to view the result."));
        });
    page.button(page.footer, t("刷新备份记录", "Refresh backup records"), false, this::refresh);
    setContentView(page.root);
    PressFeedback.attachButtons(findViewById(android.R.id.content));
    refresh();
  }

  private void openCopy(String extra, String copyId) {
    startActivity(new Intent(this, NativeDataActivity.class).putExtra(extra, copyId));
  }

  /**
   * G1 详情 / 动作抽屉：只读展示副本时间、大小、编号与结果；动作跳到 NativeData 的既有预检 / 导出
   * 流程。点遮罩或返回键只关闭抽屉，不会执行任何动作。
   */
  private void showCopyDrawer(
      VerifiedBackupCopy copy,
      String title,
      String detail,
      int otherRecords,
      boolean incompleteList) {
    closeCopyDrawer();
    CardPage sheet = new CardPage(this, t("备份详情", "Backup details"), title, true);
    LinearLayout facts = sheet.card();
    sheet.kv(
        facts,
        t("创建时间", "Created"),
        android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", copy.created).toString());
    sheet.kv(facts, t("大小", "Size"), com.deepseekharness.app.util.Fmt.bytes(copy.bytes));
    sheet.kv(facts, t("编号", "ID"), copy.id);
    if (!detail.isEmpty()) sheet.kv(facts, t("摘要", "Summary"), detail);
    String result = copy.result(true);
    if (!result.isEmpty()) sheet.kv(facts, t("记录结果", "Recorded result"), result);
    sheet.kv(
        facts,
        t("其他标为完整的自动备份", "Other automatic backups recorded as complete"),
        Integer.toString(otherRecords));
    if (incompleteList)
      sheet.content.addView(
          sheet.text(
              t(
                  "部分记录无法读取，以上只计可读记录。",
                  "Some records could not be read; the count covers readable records only."),
              12,
              R.color.shell_text_secondary));
    TextView note =
        sheet.text(
            BuildConfig.REBIRTH_CANDIDATE
                ? t(
                    "试用版详情仅供只读核验本机自动副本，不提供恢复或向应用外导出。上次刷新的记录不能证明副本可恢复。",
                    "In this trial you can check local automatic copies without changing them. Restore and export outside the app are unavailable. The last refresh does not prove a copy can be restored.")
                : t(
                    "这里只显示上次刷新时的记录，不代表还有可恢复的副本。预检或导出时会复核所选副本；此页不会删除原件。",
                    "These records are from the last refresh, not proof that another copy can be restored. Inspect or export rechecks the selected copy. This page does not delete originals."),
            12,
            R.color.text_secondary);
    note.setPadding(0, sheet.dp(4), 0, sheet.dp(8));
    sheet.content.addView(note);
    final BottomSheetCard.Sheet[] drawer = new BottomSheetCard.Sheet[1];
    TextView checkStatus =
        sheet.text(
            t(
                "尚未核验。仅按需读取本机自动副本；关闭详情会取消本次核验。",
                "Not checked. Local automatic copies are read only on request. Closing these details cancels this check."),
            14,
            R.color.text_secondary);
    checkStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    sheet.content.addView(checkStatus);
    sheet
        .button(
            sheet.content,
            t("核验其他副本（只读）", "Check other copies (read-only)"),
            false,
            () -> startCopyRead(drawer[0], copy, checkStatus))
        .setMinimumHeight(sheet.dp(48));
    if (!BuildConfig.REBIRTH_CANDIDATE) {
      sheet.button(
          sheet.footer,
          t("预检恢复", "Inspect for restore"),
          true,
          () -> {
            if (drawer[0] == null || drawer[0] != copyDrawer || !copyDrawer.isShowing()) return;
            closeCopyDrawer();
            openCopy("auto_restore_id", copy.id);
          });
      sheet.button(
          sheet.footer,
          t("导出无密码备份", "Export backup without a password"),
          false,
          () -> {
            if (drawer[0] == null || drawer[0] != copyDrawer || !copyDrawer.isShowing()) return;
            closeCopyDrawer();
            openCopy("auto_export_id", copy.id);
          });
    }
    sheet.button(
        sheet.footer,
        t("关闭", "Close"),
        false,
        () -> {
          if (drawer[0] != null) drawer[0].dismiss();
        });
    drawer[0] = BottomSheetCard.show(this, sheet);
    copyDrawer = drawer[0];
    if (copyDrawer != null)
      copyDrawer
          .dialog()
          .setOnDismissListener(
              ignored -> {
                if (copyDrawer == drawer[0]) closeCopyDrawer();
              });
  }

  private void closeCopyDrawer() {
    var old = copyDrawer;
    copyDrawer = null;
    if (copyRead != null) {
      copyRead.close();
      copyRead = null;
    }
    if (old != null && old.isShowing()) old.dismiss();
  }

  private void startCopyRead(
      BottomSheetCard.Sheet drawer, VerifiedBackupCopy copy, TextView status) {
    if (drawer == null
        || copyDrawer != drawer
        || !drawer.isShowing()
        || isFinishing()
        || isDestroyed()) return;
    if (copyRead != null) {
      copyRead.close();
      copyRead = null;
    }
    copyRead =
        NativeBackupJobs.get(this)
            .inspectCopy(
                copy,
                request -> {
                  if (drawer != copyDrawer
                      || !drawer.isShowing()
                      || request != copyRead
                      || isFinishing()
                      || isDestroyed()) return;
                  var observed = request.snapshot();
                  if (observed == null) {
                    status.setText(
                        t("记录或任务状态已变化，请重新核验。", "Records or job state changed. Check again."));
                    return;
                  }
                  String message =
                      switch (observed.reason()) {
                        case "OBSERVED" ->
                            t(
                                "本次核验时，另有至少 3 份同范围副本的内容摘要匹配。未实际恢复，也不代表可以删除。",
                                "At the time of this check, at least 3 other copies with matching scope passed the digest check. Restoration was not tested; this is not permission to delete.");
                        case "INSUFFICIENT" ->
                            t(
                                    "本次核验时，其他同范围且摘要匹配的副本不足 3 份：",
                                    "At the time of this check, fewer than 3 other copies qualified: ")
                                + observed.verifiedOthers();
                        case "INELIGIBLE" ->
                            t(
                                "所选副本不满足本次核验条件，不能作为处理依据。",
                                "The selected copy does not meet the conditions for this check.");
                        default ->
                            t(
                                "未能确认。记录可能变化、不可读或超出核验限额。",
                                "Not verified. Records may have changed, been unreadable, or exceeded the check limit.");
                      };
                  status.setText(message);
                });
    status.setText(
        copyRead == null
            ? t(
                "未开始核验：作业不空闲或状态未确认。可稍后手动重试。",
                "Check not started: jobs are busy or their state is unknown. Try again later.")
            : t("正在只读核验；还没有核验结果。", "Checking read-only; no result yet."));
  }

  private void refresh() {
    closeCopyDrawer();
    if (reading) return;
    reading = true;
    FadeSwap.text(state, t("正在读取记录…", "Reading records…"));
    showFirstLoadSkeleton();
    new Thread(
            () -> {
              try {
                var found = NativeBackupJobs.get(this).verifiedCopies();
                int count = 0;
                for (var c : found.valid) {
                  if (AutomaticBackupPolicy.isCompleteAutomaticRecord(
                      Boolean.TRUE.equals(c.metadata.get("automatic")), c.result(true))) count++;
                }
                final int completeRecords = count;
                var prefs = AutomaticBackups.prefs(this);
                long last = prefs.getLong("last", 0);
                String error = prefs.getString("error", "");
                runOnUiThread(
                    () -> {
                      reading = false;
                      if (isFinishing() || isDestroyed()) return;
                      closeCopyDrawer(); // An older list can still be clicked while the refresh is
                      // in flight.
                      stopSkeleton();
                      copies.removeAllViews();
                      String status =
                          AutomaticBackups.enabled(this)
                              ? t(
                                  "已开启 · 等待到期且 DSH 停止",
                                  "On · waiting for the scheduled time and DSH to stop")
                              : t("已关闭", "Off");
                      if (last > 0)
                        status +=
                            "\n"
                                + t("上次完成：", "Last completed: ")
                                + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", last);
                      if (!error.isEmpty()) {
                        String reason =
                            switch (error) {
                              case "BACKUP_PRUNE_DIGEST_MISMATCH_RETAINED" ->
                                  t(
                                      "有自动副本的内容摘要不匹配，已保留；不计入最近三份已验证副本。",
                                      "An automatic copy failed its digest check and was kept, outside the latest three verified copies.");
                              case "BACKUP_PRUNE_RECORD_UNREADABLE" ->
                                  t(
                                      "有备份记录无法核实，本轮未清理旧副本。",
                                      "A backup record could not be verified. No old copies were removed this round.");
                              default -> error;
                            };
                        status += "\n" + t("待处理：", "Needs attention: ") + reason;
                      }
                      var job = NativeBackupJobs.get(this).state();
                      if (job.busy)
                        status =
                            t("备份任务进行中：", "Backup in progress: ")
                                + stage(job.stage)
                                + " · "
                                + job.entries
                                + t(" 项", " entries");
                      if ("DATA_SAVED_PLUGIN_WARNINGS".equals(job.result))
                        status +=
                            "\n"
                                + (BuildConfig.REBIRTH_CANDIDATE
                                    ? t(
                                        "数据已保存；部分插件依赖尚未核实。",
                                        "Data saved; some plugin dependencies are unverified.")
                                    : t(
                                        "数据已保存；部分插件依赖需在恢复预检中核对。",
                                        "Data saved; some plugin dependencies need review before restore."));
                      FadeSwap.text(state, status);
                      for (var copy : found.valid)
                        if (Boolean.TRUE.equals(copy.metadata.get("automatic"))) {
                          String title =
                              android.text.format.DateFormat.format("MM-dd HH:mm", copy.created)
                                  .toString();
                          String detail =
                              com.deepseekharness.app.util.Fmt.bytes(copy.bytes)
                                  + ("DATA_SAVED_PLUGIN_WARNINGS".equals(copy.result(true))
                                      ? t(" · 插件依赖待核对", " · review plugin dependencies")
                                      : "");
                          View copyRow =
                              page.entry(
                                  copies,
                                  title,
                                  detail,
                                  R.drawable.ic_recovery_document,
                                  () ->
                                      showCopyDrawer(
                                          copy,
                                          title,
                                          detail,
                                          AutomaticBackupPolicy.otherCompleteRecords(
                                              completeRecords,
                                              AutomaticBackupPolicy.isCompleteAutomaticRecord(
                                                  true, copy.result(true))),
                                          !found.unreadable.isEmpty()));
                          // F2：左滑露出「预检恢复 / 导出」；滑动只露出动作，点按才生效。
                          if (!BuildConfig.REBIRTH_CANDIDATE) {
                            SwipeAction.attach(
                                copyRow,
                                SwipeAction.Model.of(
                                    SwipeAction.Action.of(
                                        t("预检恢复", "Inspect for restore"),
                                        R.drawable.ic_recovery_refresh,
                                        () -> openCopy("auto_restore_id", copy.id)),
                                    SwipeAction.Action.of(
                                        t("导出", "Export"),
                                        t("导出无密码备份", "Export backup without a password"),
                                        R.drawable.ic_ui_export,
                                        () -> openCopy("auto_export_id", copy.id))));
                          }
                          // F1：备份记录列表首次出现前 8 条错峰，刷新重排不重放。
                          StaggerList.revealRow(copyRow, "auto-backup-copies", copy.id);
                        }
                      if (copies.getChildCount() == 0)
                        copies.addView(
                            page.text(
                                found.unreadable.isEmpty()
                                    ? t(
                                        "还没有自动备份",
                                        "No automatic backup yet. The first copy is created when due, with DSH and terminals stopped.")
                                    : t(
                                        "部分记录无法读取，未能确认是否有自动备份。",
                                        "Some records could not be read. Whether an automatic backup exists is unknown."),
                                12,
                                R.color.text_muted));
                    });
              } catch (Exception error) {
                runOnUiThread(
                    () -> {
                      reading = false;
                      FadeSwap.text(state, NativeBackupJobs.code(error));
                    });
              }
            },
            "auto-backup-records")
        .start();
  }

  /**
   * E4：首次读取记录时用 2 行骨架占位，数据到达立即收掉；上限 1.5 秒后骨架自己停，
   * 页面保留静态「正在读取记录…」，不做无限 shimmer。
   */
  private void showFirstLoadSkeleton() {
    if (skeletonPlayed || copies.getChildCount() > 0) return;
    skeletonPlayed = true;
    LinearLayout host = new LinearLayout(this);
    host.setOrientation(LinearLayout.VERTICAL);
    copies.addView(host, new LinearLayout.LayoutParams(-1, -2));
    skeleton =
        FiniteSkeleton.attach(host, 2, com.deepseekharness.app.util.SkeletonTiming.DEFAULT_MS);
    skeleton.start();
  }

  private void stopSkeleton() {
    if (skeleton != null) {
      skeleton.stop();
      skeleton = null;
    }
  }

  @Override
  protected void onStop() {
    closeCopyDrawer();
    super.onStop();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (state != null) refresh();
  }
}
