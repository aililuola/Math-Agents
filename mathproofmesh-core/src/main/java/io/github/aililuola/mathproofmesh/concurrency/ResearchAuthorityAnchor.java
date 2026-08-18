package io.github.aililuola.mathproofmesh.concurrency;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.List;
import java.util.Objects;

public record ResearchAuthorityAnchor(
    String problemHash,
    String rootGoalHash,
    String negativeRegistryHash,
    String attemptArtifactLedgerHash,
    String claimLifecycleHash,
    String researchCheckpointHash,
    String proofGraphHash,
    String canonicalizationHash,
    String convergenceHash,
    String semanticPivotHash,
    String strategyPortfolioHash,
    String claimCourtHash,
    String brokerHash,
    String computationHash,
    String runAuthorityHash) {
  public ResearchAuthorityAnchor {
    problemHash = hash(problemHash, "problemHash");
    rootGoalHash = hash(rootGoalHash, "rootGoalHash");
    negativeRegistryHash = hashOrEmpty(negativeRegistryHash);
    attemptArtifactLedgerHash = hashOrEmpty(attemptArtifactLedgerHash);
    claimLifecycleHash = hashOrEmpty(claimLifecycleHash);
    researchCheckpointHash = hashOrEmpty(researchCheckpointHash);
    proofGraphHash = hashOrEmpty(proofGraphHash);
    canonicalizationHash = hashOrEmpty(canonicalizationHash);
    convergenceHash = hashOrEmpty(convergenceHash);
    semanticPivotHash = hashOrEmpty(semanticPivotHash);
    strategyPortfolioHash = hashOrEmpty(strategyPortfolioHash);
    claimCourtHash = hashOrEmpty(claimCourtHash);
    brokerHash = hashOrEmpty(brokerHash);
    computationHash = hashOrEmpty(computationHash);
    runAuthorityHash = hashOrEmpty(runAuthorityHash);
  }

  public String stableHash() {
    return CanonicalJson.stableHash(
        List.of(
            problemHash,
            rootGoalHash,
            negativeRegistryHash,
            attemptArtifactLedgerHash,
            claimLifecycleHash,
            researchCheckpointHash,
            proofGraphHash,
            canonicalizationHash,
            convergenceHash,
            semanticPivotHash,
            strategyPortfolioHash,
            claimCourtHash,
            brokerHash,
            computationHash,
            runAuthorityHash));
  }

  private static String hash(String value, String label) {
    Objects.requireNonNull(value, label);
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }

  private static String hashOrEmpty(String value) {
    return value == null ? "" : value.strip();
  }
}
