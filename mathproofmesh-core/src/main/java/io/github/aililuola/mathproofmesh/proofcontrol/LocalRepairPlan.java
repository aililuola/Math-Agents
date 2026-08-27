package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.Map;

/** Same-object, same-target bridge repair that is explicitly not a semantic pivot. */
public record LocalRepairPlan(
    String repairId,
    String sourceStrategyId,
    String exactFocusedObligationId,
    String bridgeStatement,
    String updatedExpectedLemma,
    String updatedFalsificationTest,
    String reason) {
  public LocalRepairPlan {
    sourceStrategyId = PivotValues.required(sourceStrategyId, "sourceStrategyId");
    exactFocusedObligationId =
        PivotValues.required(exactFocusedObligationId, "exactFocusedObligationId");
    bridgeStatement = PivotValues.required(bridgeStatement, "bridgeStatement");
    updatedExpectedLemma = PivotValues.required(updatedExpectedLemma, "updatedExpectedLemma");
    updatedFalsificationTest =
        PivotValues.required(updatedFalsificationTest, "updatedFalsificationTest");
    reason = PivotValues.required(reason, "reason");
    String computed =
        "local_repair_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "source", sourceStrategyId,
                        "obligation", exactFocusedObligationId,
                        "bridge", ProofIdentity.normalizeText(bridgeStatement),
                        "lemma", ProofIdentity.normalizeText(updatedExpectedLemma),
                        "falsification", ProofIdentity.normalizeText(updatedFalsificationTest)))
                .substring(0, 20);
    if (repairId != null && !repairId.isBlank() && !computed.equals(repairId.strip())) {
      throw new IllegalArgumentException("repairId is server-owned");
    }
    repairId = computed;
  }
}
