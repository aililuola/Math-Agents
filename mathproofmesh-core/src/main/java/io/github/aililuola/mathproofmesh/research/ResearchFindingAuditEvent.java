package io.github.aililuola.mathproofmesh.research;

public record ResearchFindingAuditEvent(
    long sequence,
    String findingId,
    String action,
    ResearchFindingStatus priorStatus,
    ResearchFindingStatus nextStatus,
    String reason) {

  public ResearchFindingAuditEvent {
    if (sequence < 0L) {
      throw new IllegalArgumentException("sequence must be nonnegative");
    }
    findingId = required(findingId, "findingId");
    action = required(action, "action");
    reason = reason == null ? "" : reason.strip();
  }

  private static String required(String value, String name) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return normalized;
  }
}
