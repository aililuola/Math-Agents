package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import java.util.List;
import java.util.Objects;

/** Durable provenance and authority state for one artifact harvested from an attempt. */
public record AttemptArtifactRecord(
    String artifactId,
    String problemHash,
    String routeId,
    String sourceAttemptId,
    AttemptStatus sourceAttemptStatus,
    String sourceDeltaId,
    String sourceRouteStatus,
    AttemptArtifactKind kind,
    String claimId,
    String contentHash,
    String statement,
    String authorAgentId,
    boolean sourceAttemptIncomplete,
    String targetObligationId,
    AttemptArtifactStatus status,
    List<String> reviewIds,
    List<String> evidenceRefs,
    String promotedMessageId,
    long version,
    List<String> history) {

  public AttemptArtifactRecord {
    artifactId = required(artifactId, "artifactId");
    problemHash = required(problemHash, "problemHash");
    routeId = required(routeId, "routeId");
    sourceAttemptId = required(sourceAttemptId, "sourceAttemptId");
    sourceAttemptStatus = Objects.requireNonNull(sourceAttemptStatus, "sourceAttemptStatus");
    sourceDeltaId = normalize(sourceDeltaId);
    sourceRouteStatus = required(sourceRouteStatus, "sourceRouteStatus");
    kind = Objects.requireNonNull(kind, "kind");
    claimId = required(claimId, "claimId");
    contentHash = required(contentHash, "contentHash");
    statement = required(statement, "statement");
    authorAgentId = required(authorAgentId, "authorAgentId");
    targetObligationId = normalize(targetObligationId);
    status = Objects.requireNonNull(status, "status");
    reviewIds = reviewIds == null ? List.of() : List.copyOf(reviewIds);
    evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    promotedMessageId = normalize(promotedMessageId);
    if (version < 0L) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
    history = history == null ? List.of() : List.copyOf(history);
  }

  public boolean terminal() {
    return switch (status) {
      case REJECTED, UNCERTAIN, PROMOTED_FACT, APPLIED_COUNTEREXAMPLE -> true;
      default -> false;
    };
  }

  private static String required(String value, String name) {
    String normalized = normalize(value);
    if (normalized == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return normalized;
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.strip();
    return normalized.isEmpty() ? null : normalized;
  }
}
