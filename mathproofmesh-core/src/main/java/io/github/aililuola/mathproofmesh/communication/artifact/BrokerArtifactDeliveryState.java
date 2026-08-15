package io.github.aililuola.mathproofmesh.communication.artifact;

public enum BrokerArtifactDeliveryState {
  QUEUED,
  PROMPT_CONSUMED,
  RECEIPTED,
  INVALIDATED,
  EXPIRED
}
