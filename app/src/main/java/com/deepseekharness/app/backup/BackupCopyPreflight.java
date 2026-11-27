package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.BackupScopes;
import com.deepseekharness.app.util.Ids;
import java.io.*;
import java.util.*;

/** 本次只读复核的观察结果，不是删除许可；不解密、不访问备份描述中的路径。 */
public final class BackupCopyPreflight {
  public record Result(String reason, int verifiedOthers) {}

  private static final String DATA = "portable.dshbak",
      PROOF = "verified.json",
      JOURNAL = "operation.json";
  private static final Set<String> REQUIRED = Set.of(DATA, PROOF, JOURNAL);
  private static final int MAX_NODES = 2048, RECORD = BackupLimits.RECORD;
  private static final long MAX_BYTES = 512L << 20,
      MAX_METADATA = 8L << 20,
      MAX_NANOS = 30_000_000_000L;

  private final BackupFileSystem fs;
  private final BackupControl control;
  private final long started = System.nanoTime();
  private int nodesLeft = MAX_NODES;
  private long bytesLeft = MAX_BYTES, metadataLeft = MAX_METADATA;

  private BackupCopyPreflight(BackupFileSystem fs, BackupControl control) {
    this.fs = fs;
    this.control = control;
  }

  static Result inspect(
      BackupFileSystem fs, File operations, VerifiedBackupCopy selected, BackupControl control)
      throws IOException {
    return new BackupCopyPreflight(fs, control).inspect(operations.getAbsoluteFile(), selected);
  }

  private Result inspect(File operations, VerifiedBackupCopy selected) throws IOException {
    require(operations.getName().equals(HostOperationArchive.ROOT));
    Snapshot initial = observe(operations, selected.id);
    VerifiedBackupCopy current = initial.copies.get(selected.id);
    require(
        current != null
            && current.artifact.equals(selected.artifact)
            && current.directoryNode.same(selected.directoryNode)
            && current.artifactNode.same(selected.artifactNode)
            && current.proofDigest.equals(selected.proofDigest));
    if (initial.referenced || !initial.eligible.contains(selected.id))
      return new Result("INELIGIBLE", 0);
    if (!matches(current)) return new Result("INELIGIBLE", 0);
    int others = 0;
    Set<String> verifiedDigests = new HashSet<>();
    verifiedDigests.add(current.sha256);
    for (String id : initial.eligible) {
      VerifiedBackupCopy other = initial.copies.get(id);
      if (verifiedDigests.contains(other.sha256)
          || !other.scope.equals(current.scope)
          || !current.metadata.get("roots").equals(other.metadata.get("roots"))) continue;
      if (matches(other)) {
        verifiedDigests.add(other.sha256);
        if (++others == 3) break;
      }
    }
    Snapshot end = observe(operations, selected.id);
    require(
        !end.referenced
            && initial.records.equals(end.records)
            && initial.nodes.keySet().equals(end.nodes.keySet()));
    for (var row : initial.nodes.entrySet())
      require(row.getValue().same(end.nodes.get(row.getKey())));
    check();
    return new Result(others == 3 ? "OBSERVED" : "INSUFFICIENT", others);
  }

  private static final class Snapshot {
    final Map<File, BackupFileSystem.Node> nodes = new HashMap<>();
    final Set<String> identities = new HashSet<>();
    final Map<File, String> records = new HashMap<>();
    final Map<String, VerifiedBackupCopy> copies = new LinkedHashMap<>();
    final Set<String> eligible = new LinkedHashSet<>();
    boolean referenced;
  }

  private Snapshot observe(File operations, String selected) throws IOException {
    Snapshot s = new Snapshot();
    File files = operations.getParentFile();
    var owner = node(s, files);
    require(owner.type.equals("DIRECTORY"));
    require(node(s, operations).type.equals("DIRECTORY"));
    File history = fs.child(operations, HostOperationArchive.COMPLETED);
    var archived = node(s, history);
    Set<String> active = names(operations, nodesLeft);
    require(active.remove(HostOperationArchive.COMPLETED) == archived.type.equals("DIRECTORY"));
    require(archived.type.equals("MISSING") || archived.type.equals("DIRECTORY"));
    for (String id : active) operation(s, operations, id);
    if (archived.type.equals("DIRECTORY"))
      for (String id : names(history, nodesLeft)) operation(s, history, id);

    var historyProof = optionalRecord(s, files, "host-backup-completed-v1.json", 4096);
    if (archived.type.equals("DIRECTORY")) {
      require(
          historyProof != null
              && BackupJson.number(historyProof, "version") == 1
              && owner.key.equals(historyProof.get("filesKey"))
              && archived.key.equals(historyProof.get("historyKey")));
    } else require(historyProof == null);
    var pointer = optionalRecord(s, files, "host-backup-last-task-v1.json", 1024);
    if (pointer != null) {
      String id = BackupJson.string(pointer, "id");
      require(BackupJson.number(pointer, "version") == 1 && s.copies.containsKey(id));
      s.referenced = selected.equals(id);
    }
    File catalogue = fs.child(files, "host-backup-catalogue");
    var catalogueNode = node(s, catalogue);
    if (!catalogueNode.type.equals("MISSING")) {
      require(
          catalogueNode.type.equals("DIRECTORY")
              && names(catalogue, 1).equals(Set.of("latest.json")));
      var latest = optionalRecord(s, catalogue, "latest.json", RECORD);
      require(latest != null);
      String id = BackupJson.string(latest, "operation");
      require(
          Ids.uuid(id)
              && s.copies.containsKey(id)
              && BackupJson.string(latest, "sha256").matches("[a-f0-9]{64}")
              && BackupJson.string(latest, "payloadSha256").matches("[a-f0-9]{64}")
              && !BackupJson.string(latest, "uri").isEmpty()
              && !BackupJson.string(latest, "name").isEmpty());
      String sourceHash =
          BackupJson.string(
              latest, latest.containsKey("sourceSha256") ? "sourceSha256" : "payloadSha256");
      require(s.copies.get(id).sha256.equals(sourceHash));
      s.referenced |= selected.equals(id);
    }
    return s;
  }

  private void operation(Snapshot s, File root, String id) throws IOException {
    require(Ids.uuid(id) && !s.copies.containsKey(id));
    File directory = fs.child(root, id);
    var dir = node(s, directory);
    require(dir.type.equals("DIRECTORY"));
    Set<String> members = names(directory, 4);
    if (members.remove("source-checks"))
      require(node(s, fs.child(directory, "source-checks")).type.equals("FILE"));
    // 未知作业、额外文件或重导出引用均不解释为安全；不递归读取其内容。
    require(members.equals(REQUIRED));
    File artifact = fs.child(directory, DATA),
        proof = fs.child(directory, PROOF),
        journal = fs.child(directory, JOURNAL);
    var data = node(s, artifact);
    var copy =
        new VerifiedBackupCopy(
            id, artifact, dir, data, recordBytes(s, proof, BackupLimits.MANIFEST));
    var state = BackupJson.read(recordBytes(s, journal, RECORD), RECORD);
    require(
        id.equals(state.get("id"))
            && BackupJson.number(state, "version") == 1
            && Boolean.FALSE.equals(state.get("busy"))
            && "FINISHED".equals(state.get("stage")));
    s.copies.put(id, copy);
    if (Boolean.TRUE.equals(copy.metadata.get("automatic"))
        && Long.valueOf(5).equals(copy.metadata.get("formatVersion"))
        && copy.created > 0
        && copy.metadata.get("plugins") instanceof Map
        && "EXPORT".equals(copy.metadata.get("operation"))
        && "PASSWORD_ENCRYPTED".equals(copy.metadata.get("sensitivePolicy"))
        && copy.scope.equals(copy.metadata.get("requestedScope"))
        && knownBreadth(copy)
        && "COMPLETE".equals(copy.result(true))
        && "COMPLETE".equals(state.get("result"))
        && "".equals(state.get("error"))
        && DATA.equals(state.get("artifact"))
        && BackupJson.number(state, "bytes") == copy.bytes
        && BackupJson.number(state, "entries") == copy.entries) s.eligible.add(id);
  }

  private static boolean knownBreadth(VerifiedBackupCopy copy) {
    if (!(copy.metadata.get("roots") instanceof List<?> roots)
        || roots.isEmpty()
        || roots.size() > 512) return false;
    Set<String> ids = new HashSet<>();
    for (Object item : roots) {
      if (!(item instanceof Map<?, ?> root)
          || !(root.get("id") instanceof String id)
          || !id.matches("[a-z][a-z0-9_-]{0,79}")
          || !ids.add(id)
          || !(root.get("scope") instanceof String scope)
          || !BackupScopes.ALL.contains(scope)
          || !(root.get("external") instanceof Boolean)
          || (!copy.scope.equals("application") && !copy.scope.equals(scope))) return false;
    }
    return true;
  }

  private BackupFileSystem.Node node(Snapshot s, File file) throws IOException {
    check();
    require(--nodesLeft >= 0);
    var n = fs.stat(file);
    require(s.nodes.put(file, n) == null);
    if (!n.type.equals("MISSING"))
      require(
          n.key != null
              && !n.key.isEmpty()
              && !n.key.equals("null")
              && s.identities.add(n.device + ":" + n.key));
    return n;
  }

  private Set<String> names(File directory, int limit) throws IOException {
    check();
    var names = fs.listBounded(directory, Math.min(nodesLeft, limit), control);
    require(names.size() <= Math.min(nodesLeft, limit));
    Set<String> unique = new TreeSet<>(names);
    require(unique.size() == names.size());
    return unique;
  }

  private Map<String, Object> optionalRecord(Snapshot s, File directory, String name, int limit)
      throws IOException {
    require(node(s, fs.child(directory, name + ".previous")).type.equals("MISSING"));
    File file = fs.child(directory, name);
    var n = node(s, file);
    if (n.type.equals("MISSING")) return null;
    return BackupJson.read(recordBytes(s, file, n, limit), limit);
  }

  private byte[] recordBytes(Snapshot s, File file, int limit) throws IOException {
    return recordBytes(s, file, node(s, file), limit);
  }

  private byte[] recordBytes(Snapshot s, File file, BackupFileSystem.Node n, int limit)
      throws IOException {
    require(n.type.equals("FILE") && n.size >= 0 && n.size <= limit && n.size < metadataLeft);
    byte[] bytes = read(file, n, true);
    s.records.put(file, BackupArchive.hex(BackupArchive.sha().digest(bytes)));
    return bytes;
  }

  private boolean matches(VerifiedBackupCopy copy) throws IOException {
    return copy.sha256.equals(BackupArchive.hex(read(copy.artifact, copy.artifactNode, false)));
  }

  /** 限制声明值和实际读量；多一字节即失败，短读、关闭异常和取消都不能当作摘要不匹配。 */
  private byte[] read(File file, BackupFileSystem.Node n, boolean metadata) throws IOException {
    check();
    require(n.size >= 0 && n.size < bytesLeft);
    var output = metadata ? new ByteArrayOutputStream((int) n.size) : null;
    var digest = metadata ? null : BackupArchive.sha();
    long left = n.size;
    try (InputStream in = fs.read(file, n)) {
      byte[] buffer = new byte[(int) Math.min(65536, left + 1)];
      for (; ; ) {
        check();
        int count = in.read(buffer, 0, (int) Math.min(buffer.length, left + 1));
        if (count == -1) break;
        bytesLeft -= count;
        if (metadata) metadataLeft -= count;
        require(count > 0 && count <= left);
        if (metadata) output.write(buffer, 0, count);
        else {
          for (int i = 0; i < count && n.size - left + i < PortableBackupCrypto.MAGIC.length; i++)
            require(buffer[i] == PortableBackupCrypto.MAGIC[(int) (n.size - left) + i]);
          digest.update(buffer, 0, count);
        }
        left -= count;
      }
    }
    check();
    require(left == 0);
    return metadata ? output.toByteArray() : digest.digest();
  }

  private void check() throws IOException {
    control.check();
    require(System.nanoTime() - started < MAX_NANOS);
  }

  private static void require(boolean condition) throws IOException {
    if (!condition) throw new IOException("PREFLIGHT_UNPROVEN");
  }
}
