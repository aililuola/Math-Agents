package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.StrategyPreflightPlan;

final class StrategyPreflightAuthorityGate {
  private StrategyPreflightAuthorityGate() {}

  static boolean hasBoundClaim(StrategyPreflightPlan plan) {
    return plan.claimPlans().stream()
        .anyMatch(
            claim ->
                claim.computationContractId() != null
                    && !claim.computationContractId().isBlank());
  }

  static void requireExact(StrategyPreflightPlan expected, StrategyPreflightPlan candidate) {
    if (!CanonicalJson.stableHash(expected).equals(CanonicalJson.stableHash(candidate))) {
      throw new IllegalArgumentException(
          "strategy preflight plan changed a server-authorized claim binding");
    }
  }
}
