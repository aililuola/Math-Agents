package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Objects;

public record ResearchWorkResultArtifact(
    String artifactRef, ResearchWorkResultEnvelope envelope) {
  public ResearchWorkResultArtifact {
    artifactRef = Objects.requireNonNull(artifactRef, "artifactRef").strip();
    envelope = Objects.requireNonNull(envelope, "envelope");
    if (artifactRef.isEmpty()) {
      throw new IllegalArgumentException("artifactRef must not be blank");
    }
  }
}
