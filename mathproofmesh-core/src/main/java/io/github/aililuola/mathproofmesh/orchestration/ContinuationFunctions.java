package io.github.aililuola.mathproofmesh.orchestration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded continuation and committed-checkpoint CAS semantics. */
public final class ContinuationFunctions {
  private ContinuationFunctions() {}

  public static Delta boundedDelta(
      Checkpoint parent,
      String authorAgentId,
      String reviewerAgentId,
      int newSteps,
      int newClaims,
      boolean reviewAccepted) {
    if (newSteps < 0 || newSteps > 16 || newClaims < 0 || newClaims > 8) {
      throw new IllegalArgumentException("continuation segment exceeds step or claim limit");
    }
    return new Delta(
        "delta_"
            + CanonicalJson.stableHash(
                    List.of(parent.checkpointId(), parent.segmentIndex() + 1, authorAgentId))
                .substring(0, 12),
        parent.checkpointId(),
        parent.problemHash(),
        parent.pathId(),
        parent.strategyId(),
        parent.segmentIndex() + 1,
        required(authorAgentId, "authorAgentId"),
        required(reviewerAgentId, "reviewerAgentId"),
        newSteps,
        newClaims,
        reviewAccepted);
  }

  public static final class CheckpointLedger {
    private final Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();
    private final Map<String, String> latestByBranch = new LinkedHashMap<>();
    private final List<AuditEvent> audit = new ArrayList<>();
    private long version;

    public synchronized void seed(Checkpoint checkpoint) {
      if (!checkpoint.committed()) {
        throw new IllegalArgumentException("seed checkpoint must be committed");
      }
      boolean changed = checkpoints.putIfAbsent(checkpoint.checkpointId(), checkpoint) == null;
      changed |= latestByBranch.putIfAbsent(checkpoint.branchId(), checkpoint.checkpointId()) == null;
      if (changed) {
        version++;
      }
    }

    public synchronized CommitResult commit(String branchId, Delta delta) {
      String branch = required(branchId, "branchId");
      String latestId = latestByBranch.get(branch);
      Checkpoint parent = checkpoints.get(latestId);
      if (parent == null) {
        throw new IllegalStateException("branch has no committed checkpoint");
      }
      if (!delta.reviewAccepted()) {
        audit.add(new AuditEvent(delta.deltaId(), "rejected", latestId));
        version++;
        return new CommitResult(false, parent, "independent review rejected delta");
      }
      if (delta.authorAgentId().equals(delta.reviewerAgentId())) {
        throw new IllegalArgumentException("delta author cannot be its reviewer");
      }
      if (!delta.parentCheckpointId().equals(latestId)
          || delta.segmentIndex() != parent.segmentIndex() + 1
          || !delta.problemHash().equals(parent.problemHash())
          || !delta.pathId().equals(parent.pathId())
          || !delta.strategyId().equals(parent.strategyId())) {
        throw new IllegalStateException("checkpoint CAS or immutable identity failed");
      }
      String id =
          "checkpoint_"
              + CanonicalJson.stableHash(
                      List.of(
                          latestId,
                          delta.deltaId(),
                          delta.segmentIndex(),
                          delta.problemHash(),
                          delta.pathId(),
                          delta.strategyId(),
                          branch))
                  .substring(0, 16);
      Checkpoint committed =
          new Checkpoint(
              id,
              latestId,
              delta.problemHash(),
              delta.pathId(),
              delta.strategyId(),
              delta.segmentIndex(),
              branch,
              true);
      checkpoints.put(id, committed);
      latestByBranch.put(branch, id);
      audit.add(new AuditEvent(delta.deltaId(), "committed", id));
      version++;
      return new CommitResult(true, committed, "reviewed delta committed");
    }

    public synchronized Checkpoint rollbackAndBranch(
        String parentCheckpointId, String newBranchId) {
      Checkpoint parent = checkpoints.get(parentCheckpointId);
      if (parent == null || !parent.committed()) {
        throw new IllegalArgumentException("rollback parent must be committed");
      }
      String branch = required(newBranchId, "newBranchId");
      latestByBranch.putIfAbsent(branch, parent.checkpointId());
      audit.add(new AuditEvent(parentCheckpointId, "branched", branch));
      version++;
      return parent;
    }

    /** Opens a revision branch while preserving the prior committed strategy identity. */
    public synchronized Checkpoint branchForStrategy(
        String parentCheckpointId, String newBranchId, String strategyId) {
      Checkpoint parent = checkpoints.get(parentCheckpointId);
      if (parent == null || !parent.committed()) {
        throw new IllegalArgumentException("revision parent must be committed");
      }
      String branch = required(newBranchId, "newBranchId");
      String revisedStrategy = required(strategyId, "strategyId");
      String existingId = latestByBranch.get(branch);
      if (existingId != null) {
        Checkpoint existing = checkpoints.get(existingId);
        if (existing != null && existing.strategyId().equals(revisedStrategy)) {
          return existing;
        }
        // A rejected delta may reserve the branch by pointing it at the committed
        // parent. The first real strategy revision is allowed to advance that
        // placeholder without discarding the rollback audit record.
        if (existing == null || !existing.checkpointId().equals(parent.checkpointId())) {
          throw new IllegalStateException("revision branch identity changed");
        }
      }
      String id =
          "checkpoint_"
              + CanonicalJson.stableHash(
                      List.of(
                          parent.checkpointId(),
                          parent.pathId(),
                          revisedStrategy,
                          parent.segmentIndex(),
                          branch))
                  .substring(0, 16);
      Checkpoint revision =
          new Checkpoint(
              id,
              parent.checkpointId(),
              parent.problemHash(),
              parent.pathId(),
              revisedStrategy,
              parent.segmentIndex(),
              branch,
              true);
      checkpoints.put(id, revision);
      latestByBranch.put(branch, id);
      audit.add(new AuditEvent(parentCheckpointId, "strategy_branched", id));
      version++;
      return revision;
    }

    public synchronized Checkpoint latest(String branchId) {
      return checkpoints.get(latestByBranch.get(branchId));
    }

    public synchronized List<AuditEvent> audit() {
      return List.copyOf(audit);
    }

    public synchronized CheckpointLedgerSnapshot snapshot() {
      return new CheckpointLedgerSnapshot(checkpoints, latestByBranch, audit, version);
    }

    public synchronized void restore(CheckpointLedgerSnapshot snapshot) {
      CheckpointLedgerSnapshot source = Objects.requireNonNull(snapshot, "snapshot");
      checkpoints.clear();
      checkpoints.putAll(source.checkpoints());
      latestByBranch.clear();
      latestByBranch.putAll(source.latestByBranch());
      audit.clear();
      audit.addAll(source.audit());
      version = source.version();
    }
  }

  /** Complete rollback boundary for the globally shared continuation checkpoint ledger. */
  public record CheckpointLedgerSnapshot(
      Map<String, Checkpoint> checkpoints,
      Map<String, String> latestByBranch,
      List<AuditEvent> audit,
      long version) {
    public CheckpointLedgerSnapshot {
      checkpoints = immutableMap(checkpoints);
      latestByBranch = immutableMap(latestByBranch);
      audit = audit == null ? List.of() : List.copyOf(audit);
      if (version < 0L) {
        throw new IllegalArgumentException("version must be nonnegative");
      }
      for (Map.Entry<String, String> entry : latestByBranch.entrySet()) {
        if (!checkpoints.containsKey(entry.getValue())) {
          throw new IllegalArgumentException(
              "latest branch pointer references an unknown checkpoint: " + entry.getKey());
        }
      }
    }

    public String stableHash() {
      return CanonicalJson.stableHash(this);
    }

    @Override
    public Map<String, Checkpoint> checkpoints() {
      return Map.copyOf(checkpoints);
    }

    @Override
    public Map<String, String> latestByBranch() {
      return Map.copyOf(latestByBranch);
    }

    @Override
    public List<AuditEvent> audit() {
      return List.copyOf(audit);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
      if (values == null || values.isEmpty()) {
        return Map.of();
      }
      return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
  }

  public record Checkpoint(
      String checkpointId,
      String parentCheckpointId,
      String problemHash,
      String pathId,
      String strategyId,
      int segmentIndex,
      String branchId,
      boolean committed) {
    public Checkpoint {
      checkpointId = required(checkpointId, "checkpointId");
      parentCheckpointId =
          parentCheckpointId == null ? "" : parentCheckpointId.strip();
      problemHash = required(problemHash, "problemHash");
      pathId = required(pathId, "pathId");
      strategyId = required(strategyId, "strategyId");
      branchId = required(branchId, "branchId");
      if (segmentIndex < 0) {
        throw new IllegalArgumentException("segmentIndex must be nonnegative");
      }
    }
  }

  public record Delta(
      String deltaId,
      String parentCheckpointId,
      String problemHash,
      String pathId,
      String strategyId,
      int segmentIndex,
      String authorAgentId,
      String reviewerAgentId,
      int newSteps,
      int newClaims,
      boolean reviewAccepted) {}

  public record CommitResult(boolean committed, Checkpoint checkpoint, String reason) {}

  public record AuditEvent(String subjectId, String action, String detail) {}

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
