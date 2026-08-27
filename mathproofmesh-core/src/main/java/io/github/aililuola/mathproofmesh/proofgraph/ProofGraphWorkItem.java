package io.github.aililuola.mathproofmesh.proofgraph;

import java.util.Set;

/** One schedulable unit without conflating family membership with mathematical equivalence. */
public record ProofGraphWorkItem(
    ProofTaskScope scope,
    String workItemId,
    String representativeCanonicalTargetId,
    Set<String> canonicalTargetIds,
    Set<String> routeIds) {
  public ProofGraphWorkItem {
    scope = java.util.Objects.requireNonNull(scope, "scope");
    workItemId = require(workItemId, "workItemId");
    representativeCanonicalTargetId =
        require(representativeCanonicalTargetId, "representativeCanonicalTargetId");
    canonicalTargetIds = canonicalTargetIds == null ? Set.of() : Set.copyOf(canonicalTargetIds);
    routeIds = routeIds == null ? Set.of() : Set.copyOf(routeIds);
    if (!canonicalTargetIds.contains(representativeCanonicalTargetId)) {
      throw new IllegalArgumentException("representative target must belong to the work item");
    }
  }

  private static String require(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
