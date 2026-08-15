package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.contract.BrokerPromptArtifact;
import java.util.List;

public record BrokerArtifactPromptBatch(
    String providerRequestId,
    String routeId,
    List<BrokerPromptArtifact> artifacts,
    List<BrokerArtifactDelivery> deliveries,
    boolean replayedRequest,
    String usageInstruction) {
  public BrokerArtifactPromptBatch {
    providerRequestId = BrokerArtifactValues.required(providerRequestId, "providerRequestId");
    routeId = BrokerArtifactValues.required(routeId, "routeId");
    artifacts = BrokerArtifactValues.list(artifacts);
    deliveries = BrokerArtifactValues.list(deliveries);
    usageInstruction = BrokerArtifactValues.required(usageInstruction, "usageInstruction");
  }
}
