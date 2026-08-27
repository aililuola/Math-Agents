package io.github.aililuola.mathproofmesh.verification;

import java.util.Collection;

public final class ReviewIsolationPolicy {
  private ReviewIsolationPolicy() {}

  public static void requireIndependent(
      Collection<String> authorAgentIds, String reviewerAgentId) {
    String reviewer =
        java.util.Objects.requireNonNull(reviewerAgentId, "reviewerAgentId").trim();
    if (reviewer.isEmpty()) {
      throw new IllegalArgumentException("reviewer agent ID is required");
    }
    if (authorAgentIds != null
        && authorAgentIds.stream()
            .filter(java.util.Objects::nonNull)
            .map(String::trim)
            .anyMatch(reviewer::equals)) {
      throw new IllegalArgumentException("a reviewer cannot review its own proof chain");
    }
  }
}
