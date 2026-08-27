package io.github.aililuola.mathproofmesh.desktop;

import java.time.Instant;
import java.util.Set;

/** Durable desktop lifecycle metadata; mathematical state remains in the server run store. */
public record DesktopRunMetadata(
    String runId,
    String profile,
    String lifecycle,
    Instant createdAt,
    Instant updatedAt,
    String mode,
    String error,
    long processId) {
  private static final Set<String> LIFECYCLES =
      Set.of(
          "queued",
          "running",
          "awaiting_confirmation",
          "completed",
          "failed",
          "cancelled",
          "interrupted");

  public DesktopRunMetadata {
    runId = DesktopApiModel.safeRunId(runId);
    profile = DesktopApiModel.safeProfile(profile);
    if (!LIFECYCLES.contains(lifecycle)) {
      throw new IllegalArgumentException("invalid desktop run lifecycle");
    }
    if (!Set.of("solve", "resume").contains(mode)) {
      throw new IllegalArgumentException("invalid desktop run mode");
    }
    if (createdAt == null || updatedAt == null || processId < 0) {
      throw new IllegalArgumentException("invalid desktop run metadata");
    }
    error = error == null ? null : String.valueOf(DesktopApiModel.redact(error));
  }

  public DesktopRunMetadata withLifecycle(String next, String nextError) {
    return new DesktopRunMetadata(
        runId, profile, next, createdAt, Instant.now(), mode, nextError, processId);
  }
}
