package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.List;
import java.util.Map;

/** Durable proof that a reviewed structural delta was atomically applied exactly once. */
public record SemanticPivotApplyReceipt(
    String receiptId,
    String pivotId,
    String structuralDeltaHash,
    String routeId,
    String sourceStrategyId,
    String strategyEpochId,
    List<String> newObligationIds,
    List<String> pendingTaskIds,
    int appliedRound,
    boolean applied) {
  public SemanticPivotApplyReceipt {
    pivotId = PivotValues.required(pivotId, "pivotId");
    structuralDeltaHash = PivotValues.required(structuralDeltaHash, "structuralDeltaHash");
    routeId = PivotValues.required(routeId, "routeId");
    sourceStrategyId = PivotValues.required(sourceStrategyId, "sourceStrategyId");
    strategyEpochId = PivotValues.required(strategyEpochId, "strategyEpochId");
    newObligationIds = PivotValues.copy(newObligationIds);
    pendingTaskIds = PivotValues.copy(pendingTaskIds);
    if (appliedRound < 0) {
      throw new IllegalArgumentException("appliedRound must be nonnegative");
    }
    String computed =
        "pivot_receipt_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "pivot", pivotId,
                        "delta", structuralDeltaHash,
                        "route", routeId,
                        "source", sourceStrategyId,
                        "epoch", strategyEpochId,
                        "obligations", newObligationIds,
                        "tasks", pendingTaskIds,
                        "round", appliedRound,
                        "applied", applied))
                .substring(0, 20);
    if (receiptId != null && !receiptId.isBlank() && !computed.equals(receiptId.strip())) {
      throw new IllegalArgumentException("receiptId is server-owned");
    }
    receiptId = computed;
  }

  public static SemanticPivotApplyReceipt applied(
      PivotDelta delta,
      List<String> newObligationIds,
      List<String> pendingTaskIds,
      int round) {
    return new SemanticPivotApplyReceipt(
        null,
        delta.pivotId(),
        delta.structuralDeltaHash(),
        delta.routeId(),
        delta.sourceStrategyId(),
        delta.proposedStrategyId(),
        newObligationIds,
        pendingTaskIds,
        round,
        true);
  }

  @Override
  public List<String> newObligationIds() {
    return List.copyOf(newObligationIds);
  }

  @Override
  public List<String> pendingTaskIds() {
    return List.copyOf(pendingTaskIds);
  }
}
