package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.proofcontrol.ControlActionDispatcher.Action;
import io.github.aililuola.mathproofmesh.proofcontrol.ExecutableTaskController.Snapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Immutable deterministic persistence document for proof-control state. */
public record ProofControlState(
    int schemaVersion,
    String runId,
    String problemHash,
    ProofControlModels.Mode mode,
    List<Action> actions,
    List<Snapshot> tasks,
    List<String> riskIds,
    List<String> audit) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public ProofControlState {
    if (schemaVersion < 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported proof-control schema version");
    }
    runId = ProofControlModels.required(runId, "runId");
    problemHash = ProofControlModels.required(problemHash, "problemHash");
    mode = java.util.Objects.requireNonNull(mode, "mode");
    actions = sortedActions(actions);
    tasks = sortedTasks(tasks);
    riskIds = sortedStrings(riskIds);
    audit = audit == null ? List.of() : List.copyOf(audit);
  }

  @Override
  public List<Action> actions() {
    return List.copyOf(actions);
  }

  @Override
  public List<Snapshot> tasks() {
    return List.copyOf(tasks);
  }

  @Override
  public List<String> riskIds() {
    return List.copyOf(riskIds);
  }

  @Override
  public List<String> audit() {
    return List.copyOf(audit);
  }

  public String canonicalJson() {
    return CanonicalJson.canonicalize(this);
  }

  public String stateHash() {
    return CanonicalJson.stableHash(this);
  }

  public ProofControlState withUnknownLegacyRecord(String recordType) {
    List<String> nextAudit = new ArrayList<>(audit);
    nextAudit.add(
        "legacy record skipped:"
            + ProofControlModels.required(recordType, "recordType")
            + ":unknown schema payload");
    return new ProofControlState(
        schemaVersion, runId, problemHash, mode, actions, tasks, riskIds, nextAudit);
  }

  public static ProofControlState empty(
      String runId, String problemHash, ProofControlModels.Mode mode) {
    return new ProofControlState(
        CURRENT_SCHEMA_VERSION,
        runId,
        problemHash,
        mode,
        List.of(),
        List.of(),
        List.of(),
        List.of("initialized"));
  }

  public Map<String, Object> identityEnvelope() {
    return Map.of(
        "schema_version", schemaVersion,
        "run_id", runId,
        "problem_hash", problemHash,
        "mode", mode.name().toLowerCase(java.util.Locale.ROOT),
        "state_hash", stateHash());
  }

  private static List<Action> sortedActions(List<Action> values) {
    return (values == null ? List.<Action>of() : values).stream()
        .sorted(Comparator.comparing(Action::actionKey))
        .toList();
  }

  private static List<Snapshot> sortedTasks(List<Snapshot> values) {
    return (values == null ? List.<Snapshot>of() : values).stream()
        .sorted(Comparator.comparing(Snapshot::id))
        .toList();
  }

  private static List<String> sortedStrings(List<String> values) {
    return (values == null ? List.<String>of() : values).stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::strip)
        .distinct()
        .sorted()
        .toList();
  }
}
