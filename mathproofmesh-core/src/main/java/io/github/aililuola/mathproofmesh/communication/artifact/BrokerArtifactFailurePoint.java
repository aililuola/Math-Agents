package io.github.aililuola.mathproofmesh.communication.artifact;

/** Test-only fault boundaries for verifying broker transaction rollback. */
public enum BrokerArtifactFailurePoint {
  NONE,
  AFTER_ARTIFACT_REGISTRY,
  AFTER_PUBLICATION,
  AFTER_DELIVERY,
  AFTER_PROMPT_CONSUMPTION,
  AFTER_USE_RECEIPT,
  AFTER_LINEAGE,
  AFTER_UTILITY
}
