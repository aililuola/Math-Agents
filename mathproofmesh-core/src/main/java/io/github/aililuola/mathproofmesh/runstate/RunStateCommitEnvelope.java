package io.github.aililuola.mathproofmesh.runstate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Single-file authority containing a state and its complete transition projection. */
public record RunStateCommitEnvelope(
    int schemaVersion,
    RunStateSnapshot state,
    RunStateTransitionSnapshot transitions,
    String commitHash) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public RunStateCommitEnvelope {
    if (schemaVersion != CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported run state commit schema");
    }
    state = Objects.requireNonNull(state, "state");
    transitions = Objects.requireNonNull(transitions, "transitions");
    if (!transitions.transitions().isEmpty()
        && !RunStateHashes.equalHash(
            transitions.transitions().getLast().toStateHash(), state.stateHash())) {
      throw new IllegalArgumentException("commit transition frontier does not match state");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", schemaVersion);
    payload.put("stateHash", state.stateHash());
    payload.put("transitionLedgerHash", transitions.stableHash());
    commitHash = RunStateHashes.generatedOrVerified(commitHash, payload, "run state commit");
  }

  public static RunStateCommitEnvelope create(
      RunStateSnapshot state, RunStateTransitionSnapshot transitions) {
    return new RunStateCommitEnvelope(CURRENT_SCHEMA_VERSION, state, transitions, null);
  }
}
