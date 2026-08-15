package io.github.aililuola.mathproofmesh.communication.artifact;

import java.util.List;

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
