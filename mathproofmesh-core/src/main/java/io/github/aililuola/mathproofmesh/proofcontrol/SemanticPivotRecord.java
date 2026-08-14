package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewDecision;
import java.util.List;

public record SemanticPivotRecord(
    String pivotId,
    PivotDelta delta,
    PivotDeltaStatus status,
    String proposerAgentId,
    String reviewerAgentId,
    PivotDeltaAudit deterministicAudit,
    SemanticPivotReviewDecision reviewDecision,
    SemanticPivotApplyReceipt applyReceipt,
    ProofControlModels.MetaPivotEffect effect,
    long version,
    List<String> history) {
  public SemanticPivotRecord {
    pivotId = PivotValues.required(pivotId, "pivotId");
    delta = java.util.Objects.requireNonNull(delta, "delta");
    status = java.util.Objects.requireNonNull(status, "status");
    proposerAgentId = PivotValues.required(proposerAgentId, "proposerAgentId");
    reviewerAgentId = PivotValues.normalize(reviewerAgentId);
    history = PivotValues.copy(history);
    if (!pivotId.equals(delta.pivotId()) || version < 0L) {
      throw new IllegalArgumentException("invalid semantic pivot record identity or version");
    }
  }

  @Override
  public List<String> history() {
    return List.copyOf(history);
  }
}
