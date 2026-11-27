package com.deepseekharness.app.backup;

import java.io.*;
import java.util.*;

/** Prunes only old, complete, verified automatic copies; all other originals remain untouched. */
public final class AutomaticBackupPruner {
  private AutomaticBackupPruner() {}

  private static boolean finished(BackupFileSystem fs, VerifiedBackupCopy copy) throws IOException {
    File directory = copy.artifact.getParentFile();
    Map<String, Object> record =
        BackupJson.read(fs.small(fs.child(directory, "operation.json"), 16384), 16384);
    return copy.id.equals(record.get("id"))
        && Boolean.FALSE.equals(record.get("busy"))
        && "FINISHED".equals(record.get("stage"));
  }

  /** Returns the number of finished copies retained because their content digest did not match. */
  public static int prune(
      BackupFileSystem fs, List<VerifiedBackupCopy> copies, BackupControl control)
      throws IOException {
    List<VerifiedBackupCopy> automatic = new ArrayList<>();
    int mismatches = 0;
    control.check();
    for (VerifiedBackupCopy copy : copies) {
      control.check();
      if (Boolean.TRUE.equals(copy.metadata.get("automatic"))
          && Set.of("COMPLETE", "DATA_SAVED_PLUGIN_WARNINGS").contains(copy.result(true))
          && finished(fs, copy)) {
        if (copy.matchesDigest(fs, control)) automatic.add(copy);
        else mismatches++;
      }
    }
    automatic.sort((a, b) -> Long.compare(b.created, a.created));
    for (int index = 3; index < automatic.size(); index++) {
      VerifiedBackupCopy copy = automatic.get(index);
      File directory = copy.artifact.getParentFile(), parent = directory.getParentFile();
      control.check();
      if (!finished(fs, copy)) continue;
      Set<String> allowed =
          Set.of("portable.dshbak", "verified.json", "operation.json", "source-checks");
      if (!fs.list(directory).stream().allMatch(allowed::contains)) continue;
      copy.verify(fs, control);
      control.check();
      fs.removeOwned(parent, copy.id);
    }
    control.check();
    return mismatches;
  }
}
