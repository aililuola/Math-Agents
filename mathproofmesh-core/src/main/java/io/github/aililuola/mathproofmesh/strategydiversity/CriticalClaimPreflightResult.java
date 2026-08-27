package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.List;

public record CriticalClaimPreflightResult(
    String claimId,
    CriticalClaimSemanticKey key,
    String necessity,
    CriticalClaimPreflightStatus status,
    List<CriticalClaimPreflightEvidence> evidence,
    String detail) {
  public CriticalClaimPreflightResult {
    claimId = StrategySemanticNormalizer.require(claimId, "claimId");
    key = java.util.Objects.requireNonNull(key, "key");
    necessity = StrategySemanticNormalizer.require(necessity, "necessity");
    status = java.util.Objects.requireNonNull(status, "status");
    evidence = evidence == null ? List.of() : List.copyOf(evidence);
    detail = detail == null ? "" : detail.strip();
  }

  @Override
  public List<CriticalClaimPreflightEvidence> evidence() {
    return List.copyOf(evidence);
  }
}
