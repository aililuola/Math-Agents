package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.Map;

/** Mode and immutable runtime-limit policy for proof control. */
public record ProofControlPolicy(
    ProofControlModels.Mode mode, int reasoningTokenLimit, int maxActionsPerRound) {
  public ProofControlPolicy {
    mode = java.util.Objects.requireNonNull(mode, "mode");
    if (reasoningTokenLimit <= 0 || maxActionsPerRound <= 0) {
      throw new IllegalArgumentException("proof-control limits must be positive");
    }
  }

  public int preserveReasoningTokenLimit(int configuredRuntimeLimit) {
    if (configuredRuntimeLimit != reasoningTokenLimit) {
      throw new IllegalStateException("proof control cannot change reasoning token limits");
    }
    return configuredRuntimeLimit;
  }

  public boolean mayApplyActions() {
    return mode == ProofControlModels.Mode.ACTIVE;
  }

  public boolean mayMutateBusinessState() {
    return mode == ProofControlModels.Mode.ACTIVE;
  }

  public Map<String, Boolean> authorityBoundary() {
    return Map.of(
        "may_write_fact", false,
        "may_close_obligation", false,
        "may_change_problem_hash", false,
        "may_apply_authorized_action", mayApplyActions());
  }
}
