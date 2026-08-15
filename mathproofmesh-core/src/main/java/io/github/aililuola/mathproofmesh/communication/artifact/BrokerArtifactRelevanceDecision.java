package io.github.aililuola.mathproofmesh.communication.artifact;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor stores immutable defensive copies.")
public record BrokerArtifactRelevanceDecision(
    String artifactId, String routeId, boolean relevant, int priority, List<String> reasons) {
  public BrokerArtifactRelevanceDecision {
    artifactId = BrokerArtifactValues.required(artifactId, "artifactId");
    routeId = BrokerArtifactValues.required(routeId, "routeId");
    reasons = BrokerArtifactValues.list(reasons);
  }
}
