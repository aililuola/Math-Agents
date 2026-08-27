package io.github.aililuola.mathproofmesh.verification;

import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.FormalizationCoverageReport;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class FormalizationCoverage {
  private FormalizationCoverage() {}

  public static FormalizationCoverageReport measure(
      List<ProofStep> steps, List<ExperimentResult> results) {
    Set<String> stepIds = new LinkedHashSet<>();
    steps.forEach(step -> stepIds.add(step.stepId()));
    List<String> certified =
        results.stream()
            .filter(item -> stepIds.contains(item.targetClaimId()))
            .filter(item -> item.outcome() == ExperimentOutcome.CERTIFIED)
            .filter(item -> item.evidenceStrength() == EvidenceStrength.FORMAL_CERTIFICATE)
            .filter(ExperimentResult::independentlyVerified)
            .map(ExperimentResult::targetClaimId)
            .distinct()
            .sorted()
            .toList();
    int total = stepIds.size();
    return new FormalizationCoverageReport(
        total == 0 ? 0.0 : (double) certified.size() / total,
        certified,
        total);
  }
}
