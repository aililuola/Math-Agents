package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import java.util.List;

public record BrokerArtifactPublishResult(
    BrokerArtifactEnvelope artifact,
    BrokerArtifactPublicationRecord publication,
    List<BrokerArtifactDelivery> deliveries,
    List<BrokerArtifactRelevanceDecision> relevanceDecisions) {
  public BrokerArtifactPublishResult {
    artifact = java.util.Objects.requireNonNull(artifact, "artifact");
    publication = java.util.Objects.requireNonNull(publication, "publication");
    deliveries = BrokerArtifactValues.list(deliveries);
    relevanceDecisions = BrokerArtifactValues.list(relevanceDecisions);
  }
}
