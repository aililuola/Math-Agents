package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.List;

public record CriticalClaimPreflightEvidence(
    CriticalClaimPreflightStatus status,
    String authority,
    List<String> evidenceRefs,
    String detail) {
  public CriticalClaimPreflightEvidence {
    status = java.util.Objects.requireNonNull(status, "status");
    authority = StrategySemanticNormalizer.require(authority, "authority");
    evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    detail = detail == null ? "" : detail.strip();
  }

  @Override
  public List<String> evidenceRefs() {
    return List.copyOf(evidenceRefs);
  }
}
