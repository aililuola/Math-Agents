package io.github.aililuola.mathproofmesh.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reference in-process runner used before Temporal becomes authoritative.
 *
 * <p>The coordinator is deliberately database-shaped: a fenced lease owns a run, durable state
 * advances only after committed checkpoints, and resume starts at that committed boundary.
 */
public final class InProcessRunCoordinator {
  private final Map<String, Lease> leases = new LinkedHashMap<>();
  private final Map<String, RunSnapshot> runs = new LinkedHashMap<>();

  public synchronized Lease acquire(String runId, String ownerId) {
    String run = required(runId, "runId");
    String owner = required(ownerId, "ownerId");
    Lease current = leases.get(run);
    if (current != null && current.active()) {
      throw new IllegalStateException("run already has an active coordinator lease");
    }
    long fence = current == null ? 1L : current.fence() + 1L;
    Lease lease = new Lease(run, owner, fence, true);
    leases.put(run, lease);
    return lease;
  }

  public synchronized RunSnapshot execute(
      Lease lease,
      MockOutcome outcome,
      RoutePipelineFunctions.RunStage pauseAfter,
      ContinuationFunctions.Checkpoint committedCheckpoint) {
    assertLease(lease);
    RunSnapshot prior = runs.get(lease.runId());
    RoutePipelineFunctions.RunStage start =
        prior == null || prior.status() == Status.COMPLETED
            ? RoutePipelineFunctions.RunStage.FREEZE_PROBLEM
            : prior.nextStage();
    ArrayList<RoutePipelineFunctions.RunStage> completed =
        new ArrayList<>(prior == null ? List.of() : prior.completedStages());
    ContinuationFunctions.Checkpoint durable =
        prior == null ? null : prior.lastCommittedCheckpoint();

    int startIndex = RoutePipelineFunctions.FIXED_STAGES.indexOf(start);
    for (int index = startIndex; index < RoutePipelineFunctions.FIXED_STAGES.size(); index++) {
      RoutePipelineFunctions.RunStage stage = RoutePipelineFunctions.FIXED_STAGES.get(index);
      if (outcome == MockOutcome.FAIL
          && stage == RoutePipelineFunctions.RunStage.INDEPENDENT_REVIEW) {
        return save(
            lease,
            Status.FAILED,
            stage,
            completed,
            durable,
            "mock independent review failure");
      }
      if (outcome == MockOutcome.BUDGET_EXHAUSTED
          && stage == RoutePipelineFunctions.RunStage.SCHEDULER_DECISION) {
        return save(
            lease,
            Status.BUDGET_EXHAUSTED,
            stage,
            completed,
            durable,
            "protected finalization reserve reached");
      }
      if (stage == RoutePipelineFunctions.RunStage.COMMITTED_CHECKPOINT
          && committedCheckpoint != null) {
        if (!committedCheckpoint.committed()) {
          throw new IllegalArgumentException("working state cannot be restart authority");
        }
        durable = committedCheckpoint;
      }
      completed.add(stage);
      RoutePipelineFunctions.RunStage next =
          index + 1 < RoutePipelineFunctions.FIXED_STAGES.size()
              ? RoutePipelineFunctions.FIXED_STAGES.get(index + 1)
              : stage;
      if (stage == pauseAfter || (outcome == MockOutcome.PARTIAL && durable != null)) {
        return save(lease, Status.PAUSED, next, completed, durable, "run paused");
      }
    }
    return save(
        lease,
        Status.COMPLETED,
        RoutePipelineFunctions.RunStage.BLIND_FINAL_REVIEW,
        completed,
        durable,
        "all fixed stages completed");
  }

  public synchronized RunSnapshot resume(Lease lease, MockOutcome outcome) {
    RunSnapshot snapshot = runs.get(lease.runId());
    if (snapshot == null || snapshot.status() == Status.COMPLETED) {
      throw new IllegalStateException("run is not resumable");
    }
    if (snapshot.lastCommittedCheckpoint() == null) {
      throw new IllegalStateException("resume requires a committed checkpoint");
    }
    return execute(lease, outcome, null, snapshot.lastCommittedCheckpoint());
  }

  public synchronized void release(Lease lease) {
    assertLease(lease);
    leases.put(
        lease.runId(), new Lease(lease.runId(), lease.ownerId(), lease.fence(), false));
  }

  public synchronized RunSnapshot snapshot(String runId) {
    return runs.get(required(runId, "runId"));
  }

  private RunSnapshot save(
      Lease lease,
      Status status,
      RoutePipelineFunctions.RunStage next,
      List<RoutePipelineFunctions.RunStage> completed,
      ContinuationFunctions.Checkpoint checkpoint,
      String reason) {
    RunSnapshot snapshot =
        new RunSnapshot(
            lease.runId(),
            status,
            next,
            completed,
            checkpoint,
            lease.fence(),
            reason);
    runs.put(lease.runId(), snapshot);
    return snapshot;
  }

  private void assertLease(Lease lease) {
    Lease current = leases.get(lease.runId());
    if (current == null
        || !current.active()
        || current.fence() != lease.fence()
        || !current.ownerId().equals(lease.ownerId())) {
      throw new IllegalStateException("stale or inactive coordinator lease");
    }
  }

  public enum MockOutcome {
    COMPLETE,
    FAIL,
    PARTIAL,
    BUDGET_EXHAUSTED
  }

  public enum Status {
    RUNNING,
    PAUSED,
    FAILED,
    BUDGET_EXHAUSTED,
    COMPLETED
  }

  public record Lease(String runId, String ownerId, long fence, boolean active) {}

  public record RunSnapshot(
      String runId,
      Status status,
      RoutePipelineFunctions.RunStage nextStage,
      List<RoutePipelineFunctions.RunStage> completedStages,
      ContinuationFunctions.Checkpoint lastCommittedCheckpoint,
      long fence,
      String reason) {
    public RunSnapshot {
      completedStages = completedStages == null ? List.of() : List.copyOf(completedStages);
    }

    @Override
    public List<RoutePipelineFunctions.RunStage> completedStages() {
      return List.copyOf(completedStages);
    }
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
