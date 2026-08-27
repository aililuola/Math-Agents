package io.github.aililuola.mathproofmesh.runstate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Verifiable receipt binding a derived file to one authoritative run-state version. */
public record RunProjectionReceipt(
    String authorityHash,
    String reference,
    String artifactHash,
    RunReportStatus status,
    List<String> errors,
    Instant projectedAt) {
  public RunProjectionReceipt {
    authorityHash = Objects.requireNonNull(authorityHash, "authorityHash");
    reference = reference == null ? "" : reference;
    artifactHash = artifactHash == null ? "" : artifactHash;
    status = Objects.requireNonNull(status, "status");
    errors = errors == null ? List.of() : List.copyOf(errors);
    projectedAt = Objects.requireNonNull(projectedAt, "projectedAt");
  }
}
