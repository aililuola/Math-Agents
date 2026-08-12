package io.github.aililuola.mathproofmesh.persistence;

import java.util.List;
import java.util.Optional;

/**
 * Read-only compatibility boundary for legacy file runs.
 *
 * <p>Import is deliberately deferred. This port exposes no mutation method.
 */
public interface LegacyRunStorePort {
  Optional<String> readJson(String legacyRunId, String relativeName);

  Optional<byte[]> readArtifact(String legacyRunId, String artifactReference);

  List<String> listRunIds();
}
