package io.github.aililuola.mathproofmesh.communication.artifact;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor stores immutable defensive copies.")
public record BrokerArtifactPublicationRecord(
    String publicationId,
    String artifactId,
    String sourceClaimRevisionId,
    int roundPublished,
    List<String> targetRouteIds) {
  public BrokerArtifactPublicationRecord {
    publicationId = BrokerArtifactValues.required(publicationId, "publicationId");
    artifactId = BrokerArtifactValues.required(artifactId, "artifactId");
    sourceClaimRevisionId = BrokerArtifactValues.required(sourceClaimRevisionId, "sourceClaimRevisionId");
    if (roundPublished < 0) throw new IllegalArgumentException("roundPublished must be nonnegative");
    targetRouteIds = BrokerArtifactValues.list(targetRouteIds);
  }
}
