package io.github.aililuola.mathproofmesh.agent;

import io.github.aililuola.mathproofmesh.contract.CriticalClaimPreflightPlan;
import io.github.aililuola.mathproofmesh.contract.StrategyPreflightPlan;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategySemanticNormalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class StrategyPreflightPlanValidator {
  public StrategyPreflightPlan validate(
      StrategyPreflightPlan plan,
      String expectedProblemHash,
      String expectedStrategyId,
      Set<String> expectedClaimIds,
      Set<String> registeredComputationContracts) {
    java.util.Objects.requireNonNull(plan, "plan");
    if (!StrategySemanticNormalizer.hashEquals(plan.problemHash(), expectedProblemHash)
        || !plan.strategyId().equals(expectedStrategyId)) {
      throw new IllegalArgumentException("preflight plan crosses the authoritative problem or strategy");
    }
    Set<String> seen = new HashSet<>();
    for (CriticalClaimPreflightPlan claim : plan.claimPlans()) {
      if (!expectedClaimIds.contains(claim.claimId()) || !seen.add(claim.claimId())) {
        throw new IllegalArgumentException("preflight plan has an unknown or duplicate claim");
      }
      String contractId = claim.computationContractId();
      if (contractId != null
          && !contractId.isBlank()
          && !registeredComputationContracts.contains(contractId)) {
        throw new IllegalArgumentException("unknown computation contract: " + contractId);
      }
      claim.typedInputRefs().forEach(StrategyPreflightPlanValidator::rejectExecutablePayload);
      claim.evidenceRefs().forEach(StrategyPreflightPlanValidator::rejectExecutablePayload);
    }
    return plan;
  }

  private static void rejectExecutablePayload(String value) {
    String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
    if (normalized.contains("import ")
        || normalized.contains("def ")
        || normalized.contains("class ")
        || normalized.contains("#!/")
        || normalized.contains("powershell")
        || normalized.contains("docker ")
        || normalized.contains("system(")
        || normalized.contains("runtime.exec")) {
      throw new IllegalArgumentException("preflight plan cannot carry executable code");
    }
  }
}
