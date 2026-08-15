package io.github.aililuola.mathproofmesh.communication.artifact;

public record BrokerArtifactDelivery(
    String deliveryId,
    String artifactId,
    String publicationId,
    String targetRouteId,
    int deliveredRound,
    BrokerArtifactDeliveryState state,
    String providerRequestId) {
  public BrokerArtifactDelivery {
    deliveryId = BrokerArtifactValues.required(deliveryId, "deliveryId");
    artifactId = BrokerArtifactValues.required(artifactId, "artifactId");
    publicationId = BrokerArtifactValues.required(publicationId, "publicationId");
    targetRouteId = BrokerArtifactValues.required(targetRouteId, "targetRouteId");
    if (deliveredRound < 0) throw new IllegalArgumentException("deliveredRound must be nonnegative");
    state = java.util.Objects.requireNonNull(state, "state");
    providerRequestId = BrokerArtifactValues.nullable(providerRequestId);
  }

  public BrokerArtifactDelivery consume(String requestId) {
    if (state != BrokerArtifactDeliveryState.QUEUED) return this;
    return new BrokerArtifactDelivery(deliveryId, artifactId, publicationId, targetRouteId,
        deliveredRound, BrokerArtifactDeliveryState.PROMPT_CONSUMED, requestId);
  }

  public BrokerArtifactDelivery transition(BrokerArtifactDeliveryState next) {
    return new BrokerArtifactDelivery(deliveryId, artifactId, publicationId, targetRouteId,
        deliveredRound, next, providerRequestId);
  }
}
