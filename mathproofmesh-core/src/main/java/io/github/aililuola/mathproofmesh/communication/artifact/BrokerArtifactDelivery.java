package io.github.aililuola.mathproofmesh.communication.artifact;

public record BrokerArtifactDelivery(
    String deliveryId,
    String artifactId,
    String publicationId,
    String targetRouteId,
    int deliveredRound,
    BrokerArtifactDeliveryState state,
    String providerRequestId,
    int relevancePriority) {
  public BrokerArtifactDelivery(
      String deliveryId,
      String artifactId,
      String publicationId,
      String targetRouteId,
      int deliveredRound,
      BrokerArtifactDeliveryState state,
      String providerRequestId) {
    this(
        deliveryId,
        artifactId,
        publicationId,
        targetRouteId,
        deliveredRound,
        state,
        providerRequestId,
        0);
  }

  public BrokerArtifactDelivery {
    deliveryId = BrokerArtifactValues.required(deliveryId, "deliveryId");
    artifactId = BrokerArtifactValues.required(artifactId, "artifactId");
    publicationId = BrokerArtifactValues.required(publicationId, "publicationId");
    targetRouteId = BrokerArtifactValues.required(targetRouteId, "targetRouteId");
    if (deliveredRound < 0) throw new IllegalArgumentException("deliveredRound must be nonnegative");
    state = java.util.Objects.requireNonNull(state, "state");
    providerRequestId = BrokerArtifactValues.nullable(providerRequestId);
    if (relevancePriority < 0) {
      throw new IllegalArgumentException("relevancePriority must be nonnegative");
    }
  }

  public BrokerArtifactDelivery consume(String requestId) {
    if (state != BrokerArtifactDeliveryState.QUEUED) return this;
    return new BrokerArtifactDelivery(deliveryId, artifactId, publicationId, targetRouteId,
        deliveredRound, BrokerArtifactDeliveryState.PROMPT_CONSUMED, requestId,
        relevancePriority);
  }

  public BrokerArtifactDelivery transition(BrokerArtifactDeliveryState next) {
    return new BrokerArtifactDelivery(deliveryId, artifactId, publicationId, targetRouteId,
        deliveredRound, next, providerRequestId, relevancePriority);
  }

  public BrokerArtifactDelivery prioritize(int priority) {
    return new BrokerArtifactDelivery(
        deliveryId,
        artifactId,
        publicationId,
        targetRouteId,
        deliveredRound,
        state,
        providerRequestId,
        Math.max(relevancePriority, priority));
  }
}
