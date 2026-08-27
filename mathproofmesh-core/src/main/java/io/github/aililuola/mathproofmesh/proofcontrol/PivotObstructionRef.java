package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.Objects;

/** Immutable reference to evidence already owned by an existing authority service. */
public record PivotObstructionRef(
    String obstructionId,
    PivotEvidenceAuthority authority,
    String sourceArtifactRef,
    String boundRouteId,
    String boundStrategyId,
    String boundCanonicalTargetId,
    String statementHash) {
  public PivotObstructionRef {
    obstructionId = PivotValues.required(obstructionId, "obstructionId");
    authority = Objects.requireNonNull(authority, "authority");
    sourceArtifactRef = PivotValues.required(sourceArtifactRef, "sourceArtifactRef");
    boundRouteId = PivotValues.required(boundRouteId, "boundRouteId");
    boundStrategyId = PivotValues.required(boundStrategyId, "boundStrategyId");
    boundCanonicalTargetId = PivotValues.normalize(boundCanonicalTargetId);
    statementHash = PivotValues.required(statementHash, "statementHash");
  }
}
