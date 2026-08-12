package io.github.aililuola.mathproofmesh.communication;

@FunctionalInterface
public interface ArtifactCatalog {
  boolean exists(String artifactReference);

  static ArtifactCatalog allowRunScopedReferences() {
    return ignored -> true;
  }
}
