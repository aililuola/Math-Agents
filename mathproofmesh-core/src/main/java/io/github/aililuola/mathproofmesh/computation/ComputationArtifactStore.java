package io.github.aililuola.mathproofmesh.computation;

import java.util.Optional;

/** Content-addressed storage boundary used by the framework-free execution service. */
public interface ComputationArtifactStore {
  ComputationArtifactRecord write(
      String executionId, ComputationArtifactKind kind, Object value);

  Optional<ComputationArtifactRecord> find(
      String executionId, ComputationArtifactKind kind);

  <T> Optional<T> read(String reference, Class<T> type);

  ComputationArtifactSnapshot snapshot();

  void restore(ComputationArtifactSnapshot snapshot);
}
