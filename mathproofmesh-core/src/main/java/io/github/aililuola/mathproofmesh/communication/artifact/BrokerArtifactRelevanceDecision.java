package io.github.aililuola.mathproofmesh.communication.artifact;

import java.util.List;

public record BrokerArtifactRelevanceDecision(
    String artifactId, String routeId, boolean relevant, int priority, List<String> reasons) {
  public BrokerArtifactRelevanceDecision {
    artifactId = BrokerArtifactValues.required(artifactId, "artifactId");
    routeId = BrokerArtifactValues.required(routeId, "routeId");
    reasons = BrokerArtifactValues.list(reasons);
  }
}
