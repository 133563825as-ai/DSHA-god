package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.BackupJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** 唯一结构化回执；历史完成不升级为本次核验，缺字段或互斥组合不能授予许可。 */
public final class Rc1MigrationResult {
  private Rc1MigrationResult() {}

  private static final String PREFIX = "DSHA_RC1_MIGRATION=";

  public static Map<String, Object> parse(String output) throws IOException {
    Map<String, Object> result = null;
    if (output != null)
      for (String line : output.split("\\r?\\n"))
        if (line.startsWith(PREFIX)) {
          if (result != null) throw new IOException("RC1_MIGRATION_DUPLICATE_RESULT");
          result =
              BackupJson.read(
                  line.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8), 65536);
        }
    if (result == null) throw new IOException("RC1_MIGRATION_RESULT_MISSING");
    return result;
  }

  public static boolean versionThree(Map<String, Object> value) {
    if (value == null) return false;
    Object version = value.get("resultVersion");
    return Integer.valueOf(3).equals(version) || Long.valueOf(3).equals(version);
  }

  /** 绑定不是仅比较一个字符串：缺少最小协议字段也不算受管回执。 */
  public static boolean boundTo(Map<String, Object> value, String startupId) {
    return required(value)
        && startupId != null
        && !startupId.isEmpty()
        && startupId.equals(text(value, "startupId"));
  }

  public static boolean boundTo(
      Map<String, Object> value, String startupId, String phase, String generation) {
    return boundTo(value, startupId)
        && phase.equals(text(value, "phase"))
        && generation != null
        && Ids.uuid(generation)
        && generation.equals(text(value, "generation"));
  }

  private static boolean required(Map<String, Object> value) {
    if (!versionThree(value)) return false;
    for (String key :
        new String[] {
          "phase",
          "status",
          "startupId",
          "verificationScope",
          "contentCopyStatus",
          "sourceObjectIdentity",
          "settingsImportStatus",
          "executionState"
        }) if (!(value.get(key) instanceof String) || text(value, key).isEmpty()) return false;
    if (!"unknown".equals(text(value, "sourceObjectIdentity"))) return false;
    String phase = text(value, "phase");
    if (!Set.of("prepare", "finalize").contains(phase)) return false;
    if ("skipped".equals(text(value, "status"))) return !value.containsKey("generation");
    return Ids.uuid(text(value, "generation"));
  }

  private static boolean skipped(Map<String, Object> value) {
    return required(value)
        && "prepare".equals(text(value, "phase"))
        && "skipped".equals(text(value, "status"))
        && "DSH_MISSING".equals(text(value, "reason"))
        && "none".equals(text(value, "verificationScope"))
        && "unknown".equals(text(value, "contentCopyStatus"))
        && "unknown".equals(text(value, "settingsImportStatus"))
        && "NOT_SENT".equals(text(value, "executionState"));
  }

  public static boolean isHistorical(Map<String, Object> value) {
    return required(value)
        && "historical-complete".equals(text(value, "status"))
        && "historical".equals(text(value, "verificationScope"))
        && "verified".equals(text(value, "contentCopyStatus"))
        && Set.of("verified-historical", "not-required")
            .contains(text(value, "settingsImportStatus"))
        && "NOT_SENT".equals(text(value, "executionState"));
  }

  private static boolean currentCopyVerified(Map<String, Object> value) {
    return required(value)
        && "current".equals(text(value, "verificationScope"))
        && "verified".equals(text(value, "contentCopyStatus"))
        && "COMPLETED".equals(text(value, "executionState"));
  }

  /** 只消费 prepare；finalize 或 verify-existing 不能借局部成功字段授予启动。 */
  public static boolean allowsStart(Map<String, Object> value) {
    if (!"prepare".equals(text(value, "phase"))) return false;
    return skipped(value)
        || isHistorical(value)
        || "prepared".equals(text(value, "status"))
            && currentCopyVerified(value)
            && Set.of("pending", "not-required").contains(text(value, "settingsImportStatus"));
  }

  public static boolean allowsFinalize(Map<String, Object> value) {
    if (!"finalize".equals(text(value, "phase"))) return false;
    return isHistorical(value)
        || "committed".equals(text(value, "status"))
            && currentCopyVerified(value)
            && Set.of("verified-current", "verified-historical", "not-required")
                .contains(text(value, "settingsImportStatus"));
  }

  public static boolean isRoutineReceipt(Map<String, Object> value) {
    return allowsStart(value) || allowsFinalize(value);
  }

  public static boolean needsAttention(Map<String, Object> value) {
    return !isRoutineReceipt(value);
  }

  private static String text(Map<String, Object> value, String key) {
    if (value == null) return "";
    Object found = value.get(key);
    return found instanceof String ? (String) found : "";
  }
}
