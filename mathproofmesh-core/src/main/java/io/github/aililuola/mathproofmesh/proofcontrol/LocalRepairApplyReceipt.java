package io.github.aililuola.mathproofmesh.proofcontrol;

public record LocalRepairApplyReceipt(
    String repairId,
    String sourceStrategyId,
    String revisedStrategyId,
    String exactFocusedObligationId,
    int appliedRound,
    boolean applied) {
  public LocalRepairApplyReceipt {
    repairId = PivotValues.required(repairId, "repairId");
    sourceStrategyId = PivotValues.required(sourceStrategyId, "sourceStrategyId");
    revisedStrategyId = PivotValues.required(revisedStrategyId, "revisedStrategyId");
    exactFocusedObligationId =
        PivotValues.required(exactFocusedObligationId, "exactFocusedObligationId");
    if (appliedRound < 0) {
      throw new IllegalArgumentException("appliedRound must be nonnegative");
    }
  }
}
