package io.github.aililuola.mathproofmesh.orchestration;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persists and restores only committed hierarchical checkpoints. */
public final class ResumePhaseService {
  private final Map<String, ResumeSnapshot> snapshots = new LinkedHashMap<>();

  public synchronized void persist(String runId, ResumeSnapshot snapshot) {
    String id = required(runId, "runId");
    java.util.Objects.requireNonNull(snapshot, "snapshot");
    if (!snapshot.checkpoint().committed()) {
      throw new IllegalArgumentException("working deltas cannot become restart authority");
    }
    snapshots.put(id, snapshot);
  }

  public synchronized ResumeSnapshot resume(String runId) {
    ResumeSnapshot snapshot = snapshots.get(required(runId, "runId"));
    if (snapshot == null || !snapshot.checkpoint().committed()) {
      throw new IllegalStateException("no committed checkpoint is available");
    }
    return snapshot;
  }

  public record ResumeSnapshot(
      ContinuationFunctions.Checkpoint checkpoint,
      int round,
      boolean graphFrozen,
      Map<String, String> componentState) {
    public ResumeSnapshot {
      java.util.Objects.requireNonNull(checkpoint, "checkpoint");
      if (round < 0) {
        throw new IllegalArgumentException("round must be nonnegative");
      }
      componentState = componentState == null ? Map.of() : Map.copyOf(componentState);
    }

    @Override
    public Map<String, String> componentState() {
      return Map.copyOf(componentState);
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
