package io.github.aililuola.mathproofmesh.compatibility;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record LegacyImportResult(
    String importId,
    String sourceManifestHash,
    String sourceRoot,
    String legacyVersion,
    String targetVersion,
    String targetRunId,
    String status,
    List<LegacyFileEntry> files,
    Map<String, String> migratedDocuments,
    List<QuarantinedLegacyClaim> quarantinedClaims,
    List<String> migrationSteps,
    LegacyResumeDecision resumeDecision) {
  public LegacyImportResult {
    importId = Objects.requireNonNull(importId, "importId");
    sourceManifestHash = Objects.requireNonNull(sourceManifestHash, "sourceManifestHash");
    sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot");
    legacyVersion = Objects.requireNonNull(legacyVersion, "legacyVersion");
    targetVersion = Objects.requireNonNull(targetVersion, "targetVersion");
    targetRunId = Objects.requireNonNull(targetRunId, "targetRunId");
    status = Objects.requireNonNull(status, "status");
    files = List.copyOf(files);
    migratedDocuments = Map.copyOf(migratedDocuments);
    quarantinedClaims = List.copyOf(quarantinedClaims);
    migrationSteps = List.copyOf(migrationSteps);
    resumeDecision = Objects.requireNonNull(resumeDecision, "resumeDecision");
  }
}
