package io.github.aililuola.mathproofmesh.persistence;

import java.util.Objects;

public record ArtifactMetadata(
    String runId,
    String contentHash,
    long sizeBytes,
    String mediaType,
    String storagePath,
    String provenanceSource,
    String retentionPolicy,
    String purpose
) {
  public ArtifactMetadata {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(contentHash, "contentHash");
    Objects.requireNonNull(mediaType, "mediaType");
    Objects.requireNonNull(storagePath, "storagePath");
    Objects.requireNonNull(provenanceSource, "provenanceSource");
    Objects.requireNonNull(retentionPolicy, "retentionPolicy");
    Objects.requireNonNull(purpose, "purpose");
  }
}
