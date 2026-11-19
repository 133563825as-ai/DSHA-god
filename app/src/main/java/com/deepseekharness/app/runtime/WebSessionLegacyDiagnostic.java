package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.util.WebSessionStorage;
import java.io.File;
import java.io.IOException;

/** 单次、同步、仅元数据观察；不是认证记录或停止许可，不自动交付到生命周期。 */
public final class WebSessionLegacyDiagnostic {
  public static final long NORMAL_LIMIT_BYTES = 32768;

  public enum Type {
    FILE,
    MISSING,
    LINK,
    DIRECTORY,
    SPECIAL,
    UNKNOWN
  }

  public enum Comparison {
    WITHIN_LIMIT,
    ABOVE_LIMIT,
    UNKNOWN
  }

  public enum Stability {
    UNCHANGED_METADATA,
    CHANGED,
    UNKNOWN
  }

  public enum Code {
    METADATA_ONLY,
    HOME_UNAVAILABLE,
    HOME_CHANGED,
    METADATA_UNAVAILABLE
  }

  public enum Verification {
    UNVERIFIED
  }

  public static final class DiagnosticReport {
    public final Code code;
    public final Type type;
    public final boolean knownSize;
    // 未知时为 -1；不输出节点 key、路径或异常内容。
    public final long sizeBytes;
    public final long normalLimitBytes = NORMAL_LIMIT_BYTES;
    public final Comparison comparison;
    public final Stability stability;
    public final Verification verification = Verification.UNVERIFIED;

    private DiagnosticReport(Code code, BackupFileSystem.Node node, Stability stability) {
      this.code = code;
      type = typeOf(node);
      knownSize = type == Type.FILE && node.size >= 0;
      sizeBytes = knownSize ? node.size : -1;
      comparison =
          !knownSize
              ? Comparison.UNKNOWN
              : sizeBytes > NORMAL_LIMIT_BYTES ? Comparison.ABOVE_LIMIT : Comparison.WITHIN_LIMIT;
      this.stability = stability;
    }
  }

  private WebSessionLegacyDiagnostic() {}

  /** 固定框架 data/no_backup；不调用可能隐式创建目录的 getNoBackupFilesDir。 */
  public static DiagnosticReport inspect(Context context) {
    try {
      String data = context.getApplicationInfo().dataDir;
      if (data == null) return report(Code.HOME_UNAVAILABLE, null, Stability.UNKNOWN);
      return inspect(new AndroidBackupFileSystem(), new File(data));
    } catch (RuntimeException unavailable) {
      return report(Code.HOME_UNAVAILABLE, null, Stability.UNKNOWN);
    }
  }

  /** 包内测试缝：只有框架 data 目录，没有任意记录路径或 basename 参数。 */
  static DiagnosticReport inspect(BackupFileSystem fs, File frameworkData) {
    WebSessionStorage.Home home;
    try {
      home = WebSessionStorage.bind(fs, frameworkData, new File(frameworkData, "no_backup"));
    } catch (IOException | RuntimeException unavailable) {
      return report(Code.HOME_UNAVAILABLE, null, Stability.UNKNOWN);
    }
    File file = new File(home.directory, "web-session.enc");
    BackupFileSystem.Node before;
    try {
      before = fs.stat(file);
    } catch (IOException | RuntimeException unavailable) {
      return report(Code.METADATA_UNAVAILABLE, null, Stability.UNKNOWN);
    }
    try {
      home.verify(fs);
    } catch (IOException | RuntimeException changed) {
      return report(Code.HOME_CHANGED, null, Stability.UNKNOWN);
    }
    BackupFileSystem.Node after;
    try {
      after = fs.stat(file);
    } catch (IOException | RuntimeException unavailable) {
      return report(Code.METADATA_UNAVAILABLE, null, Stability.UNKNOWN);
    }
    try {
      home.verify(fs);
    } catch (IOException | RuntimeException changed) {
      return report(Code.HOME_CHANGED, null, Stability.UNKNOWN);
    }
    // stat 现有契约没有祖先 NOFOLLOW + fd identity 绑定。同值不代表强稳定或正文不变。
    Stability stability =
        before == null || after == null
            ? Stability.UNKNOWN
            : before.same(after) ? Stability.UNCHANGED_METADATA : Stability.CHANGED;
    return report(
        Code.METADATA_ONLY, stability == Stability.UNCHANGED_METADATA ? before : null, stability);
  }

  private static DiagnosticReport report(
      Code code, BackupFileSystem.Node node, Stability stability) {
    return new DiagnosticReport(code, node, stability);
  }

  private static Type typeOf(BackupFileSystem.Node node) {
    if (node == null) return Type.UNKNOWN;
    return switch (node.type) {
      case "FILE" -> Type.FILE;
      case "MISSING" -> Type.MISSING;
      case "LINK" -> Type.LINK;
      case "DIRECTORY" -> Type.DIRECTORY;
      case "SPECIAL" -> Type.SPECIAL;
      default -> Type.UNKNOWN;
    };
  }
}
