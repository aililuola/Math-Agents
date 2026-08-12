package io.github.aililuola.mathproofmesh.compatibility;

import java.util.Objects;

public record QuarantinedLegacyClaim(
    String claimId, String priorStatus, String reason, String sourceDocument) {
  public QuarantinedLegacyClaim {
    claimId = Objects.requireNonNull(claimId, "claimId");
    priorStatus = Objects.requireNonNull(priorStatus, "priorStatus");
    reason = Objects.requireNonNull(reason, "reason");
    sourceDocument = Objects.requireNonNull(sourceDocument, "sourceDocument");
  }
}
