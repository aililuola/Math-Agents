package io.github.aililuola.mathproofmesh.proofgraph;

import java.util.List;

public record ContradictionRecord(
    String contradictionId,
    List<String> messageIds,
    List<String> routeIds,
    String normalizedStatement,
    String reason,
    String status,
    String resolutionMessageId,
    double centrality) {

  public ContradictionRecord {
    if (contradictionId == null || contradictionId.isBlank()) {
      throw new IllegalArgumentException("contradictionId is required");
    }
    messageIds = List.copyOf(messageIds);
    routeIds = List.copyOf(routeIds);
    normalizedStatement =
        normalizedStatement == null ? "" : normalizedStatement;
    reason = reason == null ? "" : reason;
    if (!SetHolder.STATUSES.contains(status)) {
      throw new IllegalArgumentException("unknown contradiction status: " + status);
    }
    if (centrality < 0.0 || centrality > 1.0) {
      throw new IllegalArgumentException("centrality must be in [0, 1]");
    }
  }

  private static final class SetHolder {
    private static final java.util.Set<String> STATUSES =
        java.util.Set.of("open", "resolved");

    private SetHolder() {}
  }
}
