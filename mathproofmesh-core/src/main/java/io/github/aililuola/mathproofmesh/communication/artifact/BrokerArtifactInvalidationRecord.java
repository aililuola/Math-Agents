package io.github.aililuola.mathproofmesh.communication.artifact;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor stores immutable defensive copies.")
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
