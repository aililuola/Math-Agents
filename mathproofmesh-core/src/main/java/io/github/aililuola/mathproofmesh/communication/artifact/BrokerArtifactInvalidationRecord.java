package io.github.aililuola.mathproofmesh.communication.artifact;

import java.util.List;

public record BrokerArtifactInvalidationRecord(
    String invalidationId,
    String artifactId,
    String sourceAuthorityId,
    String reason,
    int roundInvalidated,
    List<String> affectedDeliveryIds,
    List<String> downstreamLineageIds,
    String revalidationTaskId) {
  public BrokerArtifactInvalidationRecord {
    invalidationId = BrokerArtifactValues.required(invalidationId, "invalidationId");
    artifactId = BrokerArtifactValues.required(artifactId, "artifactId");
    sourceAuthorityId = BrokerArtifactValues.required(sourceAuthorityId, "sourceAuthorityId");
    reason = BrokerArtifactValues.required(reason, "reason");
    if (roundInvalidated < 0) throw new IllegalArgumentException("roundInvalidated must be nonnegative");
    affectedDeliveryIds = BrokerArtifactValues.list(affectedDeliveryIds);
    downstreamLineageIds = BrokerArtifactValues.list(downstreamLineageIds);
    revalidationTaskId = BrokerArtifactValues.nullable(revalidationTaskId);
  }
}
