package io.github.aililuola.mathproofmesh.persistence;

@FunctionalInterface
public interface ArtifactMetadataSink {
  void register(ArtifactMetadata metadata);

  static ArtifactMetadataSink noOp() {
    return ignored -> {};
  }
}
