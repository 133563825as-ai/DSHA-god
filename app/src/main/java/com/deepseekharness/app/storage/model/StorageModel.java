package com.deepseekharness.app.storage.model;

import java.util.*;

/** Pure, serialized-call mechanism experiment. No threads, Android, paths, or provider I/O. */
public final class StorageModel {
  private StorageModel() {}

  public enum Phase {
    IDLE,
    RUNNING,
    CANCELLING
  }

  public static final class Token {
    private Token() {} // identity, not a caller-supplied generation number
  }

  public static final class Slot {
    private Token occupant;
    private boolean lease;
    private Phase phase = Phase.IDLE;
    private int progressPublications, resultPublications;

    public Phase phase() {
      return phase;
    }

    public int progressPublications() {
      return progressPublications;
    }

    public int resultPublications() {
      return resultPublications;
    }

    public Optional<Token> request(
        boolean explicitUserRequest, java.util.function.Consumer<Token> syntheticBegin) {
      if (!explicitUserRequest || occupant != null) return Optional.empty();
      Token token = new Token();
      occupant = token;
      lease = true;
      phase = Phase.RUNNING;
      try {
        syntheticBegin.accept(token);
      } catch (RuntimeException failure) {
        if (occupant == token) {
          lease = false;
          phase =
              Phase.CANCELLING; // caller received token before dispatch: require actual exit ack
        }
        throw failure;
      }
      return Optional.of(token);
    }

    public boolean retire(Token token, Runnable syntheticCancel) {
      if (token == null || token != occupant || !lease) return false;
      lease = false; // MUST happen before invoking cancellation, including reentrant callbacks
      phase = Phase.CANCELLING;
      syntheticCancel.run();
      return true;
    }

    public boolean progress(Token token) {
      if (!publishable(token)) return false;
      progressPublications = Math.incrementExact(progressPublications);
      return true;
    }

    public boolean result(Token token) {
      if (!publishable(token)) return false;
      resultPublications = Math.incrementExact(resultPublications);
      return true;
    }

    private boolean publishable(Token token) {
      return token != null && token == occupant && lease && phase == Phase.RUNNING;
    }

    /** Caller may invoke only on actual worker exit, NOT cancellation request/timeout/result. */
    public boolean workerExited(Token token) {
      if (token == null || token != occupant) return false;
      lease = false;
      occupant = null;
      phase = Phase.IDLE;
      return true;
    }
  }

  public enum Type {
    FILE,
    DIRECTORY,
    SYMLINK
  }

  public enum Problem {
    INACCESSIBLE,
    BUDGET,
    REVOKED,
    BLOCKED,
    DIRECTORY_CHANGED
  }

  public record Inode(long dev, long ino, Type type) {
    public Inode {
      Objects.requireNonNull(type);
    }
  }

  public record Scope(
      Set<String> buckets,
      Set<String> providers,
      Set<String> volumes,
      long directoryGeneration,
      int maxEvents) {
    public Scope {
      buckets = Set.copyOf(buckets);
      providers = Set.copyOf(providers);
      volumes = Set.copyOf(volumes);
      if (buckets.contains("shared")
          || buckets.size() > 16
          || providers.size() > 16
          || volumes.size() > 16
          || maxEvents < 1
          || maxEvents > 1024)
        throw new IllegalArgumentException("bounded scope required; shared is reserved");
    }
  }

  // Null numeric metadata means unknown; zero remains a measured zero. No content field exists.
  public sealed interface Observation permits PrivateNode, SafNode, Volume, Gap {}

  public record PrivateNode(
      String bucket, long directoryGeneration, Inode identity, Long logical, Long blocks512)
      implements Observation {
    public PrivateNode {
      Objects.requireNonNull(bucket);
      Objects.requireNonNull(identity);
    }
  }

  public record SafNode(String provider, String documentId, Long logical) implements Observation {
    public SafNode {
      Objects.requireNonNull(provider);
      Objects.requireNonNull(documentId);
    }
  }

  public record Volume(String id, Long available) implements Observation {
    public Volume {
      Objects.requireNonNull(id);
    }
  }

  /** A gap conservatively marks every private/SAF ledger partial; volumes are independent. */
  public record Gap(Problem problem) implements Observation {
    public Gap {
      Objects.requireNonNull(problem);
    }
  }

  public record Amount(OptionalLong observed, boolean partial) {}

  public record PrivateAmount(Amount logical, Amount allocatedEstimate) {}

  public record Report(
      Map<String, PrivateAmount> privateByBucket,
      Map<String, Amount> safByProvider,
      Map<String, Amount> volumeAvailable,
      Set<String> reasons,
      int examinedEvents) {}

  private record SafId(String provider, String documentId) {}

  private static final class FileValue {
    final Set<String> buckets = new HashSet<>();
    Long logical, blocks;
    boolean logicalConflict, blocksConflict;

    FileValue(PrivateNode n) {
      logical = n.logical();
      blocks = n.blocks512();
      buckets.add(n.bucket());
    }

    void merge(PrivateNode n) {
      buckets.add(n.bucket());
      logicalConflict |= !Objects.equals(logical, n.logical());
      blocksConflict |= !Objects.equals(blocks, n.blocks512());
    }
  }

  private static final class Sum {
    long sum;
    int known;
    boolean partial;

    void add(Long value) {
      if (value == null || value < 0) {
        partial = true;
        return;
      }
      try {
        long next = Math.addExact(sum, value);
        sum = next;
        known++;
      } catch (ArithmeticException overflow) {
        partial = true;
      }
    }

    Amount finish(boolean gap) {
      boolean incomplete = partial || gap;
      return new Amount(
          incomplete && known == 0 ? OptionalLong.empty() : OptionalLong.of(sum), incomplete);
    }
  }

  private static Long allocated(Long blocks) {
    if (blocks == null || blocks < 0) return null;
    try {
      return Math.multiplyExact(blocks, 512L);
    } catch (ArithmeticException overflow) {
      return null;
    }
  }

  /** Processes only the first maxEvents synthetic observations. Memory and loop are scope-bounded. */
  public static Report inspect(Scope scope, List<? extends Observation> observations) {
    Map<Inode, FileValue> files = new LinkedHashMap<>();
    Map<SafId, Long> documents = new LinkedHashMap<>();
    Set<SafId> conflicts = new HashSet<>();
    Map<String, Amount> volumes = new LinkedHashMap<>();
    scope.volumes().forEach(v -> volumes.put(v, new Amount(OptionalLong.empty(), true)));
    Set<String> reasons = new LinkedHashSet<>();
    Set<String> seenVolumes = new HashSet<>(), conflictingVolumes = new HashSet<>();
    boolean safRevoked = false;
    int limit = Math.min(scope.maxEvents(), observations.size());
    for (int i = 0; i < limit; i++) {
      Observation o = Objects.requireNonNull(observations.get(i));
      if (o instanceof PrivateNode n) {
        if (!scope.buckets().contains(n.bucket())) {
          reasons.add("OUT_OF_SCOPE");
          continue;
        }
        if (n.directoryGeneration() != scope.directoryGeneration()) {
          reasons.add("DIRECTORY_CHANGED");
          continue;
        }
        // Metadata of the node itself only: no target identity or traversal capability exists.
        FileValue previous = files.get(n.identity());
        if (previous == null) files.put(n.identity(), new FileValue(n));
        else previous.merge(n);
      } else if (o instanceof SafNode n) {
        if (safRevoked) continue; // conservative all-provider cutoff; no retry or compensation
        if (!scope.providers().contains(n.provider())) {
          reasons.add("OUT_OF_SCOPE");
          continue;
        }
        SafId key = new SafId(n.provider(), n.documentId());
        if (documents.containsKey(key) && !Objects.equals(documents.get(key), n.logical()))
          conflicts.add(key);
        else documents.put(key, n.logical());
      } else if (o instanceof Volume n) {
        if (!scope.volumes().contains(n.id())) {
          reasons.add("OUT_OF_SCOPE");
          continue;
        }
        Amount value =
            n.available() == null || n.available() < 0
                ? new Amount(OptionalLong.empty(), true)
                : new Amount(OptionalLong.of(n.available()), false);
        // One snapshot per volume; disagreement is unknown, never a sum or a guessed latest value.
        if (!seenVolumes.add(n.id()) && !volumes.get(n.id()).equals(value))
          conflictingVolumes.add(n.id());
        if (conflictingVolumes.contains(n.id())) value = new Amount(OptionalLong.empty(), true);
        volumes.put(n.id(), value);
      } else if (o instanceof Gap g) {
        reasons.add(g.problem().name());
        if (g.problem() == Problem.REVOKED) safRevoked = true;
      }
    }
    if (observations.size() > limit) reasons.add("BUDGET");
    boolean gap = !reasons.isEmpty();
    Map<String, Sum> logical = new LinkedHashMap<>(),
        allocated = new LinkedHashMap<>(),
        saf = new LinkedHashMap<>();
    scope
        .buckets()
        .forEach(
            b -> {
              logical.put(b, new Sum());
              allocated.put(b, new Sum());
            });
    logical.put("shared", new Sum());
    allocated.put("shared", new Sum());
    scope.providers().forEach(p -> saf.put(p, new Sum()));
    files.forEach(
        (identity, f) -> {
          String bucket = f.buckets.size() > 1 ? "shared" : f.buckets.iterator().next();
          // Directory st_size is not logical content, even when absent or inconsistent.
          if (identity.type() != Type.DIRECTORY)
            logical.get(bucket).add(f.logicalConflict ? null : f.logical);
          allocated.get(bucket).add(f.blocksConflict ? null : allocated(f.blocks));
        });
    documents.forEach(
        (key, value) -> saf.get(key.provider()).add(conflicts.contains(key) ? null : value));
    Map<String, PrivateAmount> privateResult = new LinkedHashMap<>();
    logical.forEach(
        (b, sum) ->
            privateResult.put(b, new PrivateAmount(sum.finish(gap), allocated.get(b).finish(gap))));
    Map<String, Amount> safResult = new LinkedHashMap<>();
    saf.forEach((p, sum) -> safResult.put(p, sum.finish(gap)));
    return new Report(
        Map.copyOf(privateResult),
        Map.copyOf(safResult),
        Map.copyOf(volumes),
        Set.copyOf(reasons),
        limit);
  }
}
