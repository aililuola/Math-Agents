package io.github.aililuola.mathproofmesh.research;

import io.github.aililuola.mathproofmesh.contract.ResearchFindingKind;
import java.util.List;
import java.util.Objects;

/** Durable public finding without proof, fact, or refutation authority. */
public record ResearchFindingRecord(
    String findingId,
    String problemHash,
    String routeId,
    String stage,
    String providerCallId,
    String checkpointId,
    int frameSequence,
    ResearchFindingKind kind,
    String statement,
    String normalizedStatement,
    String rationale,
    List<String> assumptions,
    List<String> scopeLimitations,
    String targetObligationId,
    ResearchFindingStatus status,
    String dispositionReason,
    String supersededByFindingId,
    long version) {

  public ResearchFindingRecord {
    findingId = required(findingId, "findingId");
    problemHash = required(problemHash, "problemHash");
    routeId = required(routeId, "routeId");
    stage = required(stage, "stage");
    providerCallId = required(providerCallId, "providerCallId");
    checkpointId = required(checkpointId, "checkpointId");
    if (frameSequence < 0 || version < 0L) {
      throw new IllegalArgumentException("frameSequence and version must be nonnegative");
    }
    kind = Objects.requireNonNull(kind, "kind");
    statement = required(statement, "statement");
    normalizedStatement = required(normalizedStatement, "normalizedStatement");
    rationale = required(rationale, "rationale");
    assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    scopeLimitations = scopeLimitations == null ? List.of() : List.copyOf(scopeLimitations);
    targetObligationId = normalizeNullable(targetObligationId);
    status = Objects.requireNonNull(status, "status");
    dispositionReason = normalizeNullable(dispositionReason);
    supersededByFindingId = normalizeNullable(supersededByFindingId);
  }

  @Override
  public List<String> assumptions() {
    return List.copyOf(assumptions);
  }

  @Override
  public List<String> scopeLimitations() {
    return List.copyOf(scopeLimitations);
  }

  private static String required(String value, String name) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return normalized;
  }

  private static String normalizeNullable(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.strip();
    return normalized.isEmpty() ? null : normalized;
  }
}
