package io.github.aililuola.mathproofmesh.proofgraph;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Non-authoritative prompt projection for one focused-recovery episode. */
public record FocusedRecoveryBrief(
    String rootGoalHash,
    String selectedFamilyId,
    String familyLabel,
    List<String> canonicalMemberIds,
    Map<String, String> representativeStatements,
    Map<String, Set<String>> dependencyPlans,
    List<String> verifiedFacts,
    List<String> exactCounterexamples,
    List<String> permanentNegativeSummary,
    List<String> activeResearchFindings,
    String sharpObstruction,
    List<FocusedRecoveryActionType> allowedActions,
    List<FocusedRecoveryActionType> blockedGenericActions,
    int newTargetQuotaRemaining) {

  public FocusedRecoveryBrief {
    rootGoalHash = require(rootGoalHash, "rootGoalHash");
    selectedFamilyId = normalize(selectedFamilyId);
    familyLabel = normalize(familyLabel);
    canonicalMemberIds = canonicalMemberIds == null ? List.of() : List.copyOf(canonicalMemberIds);
    representativeStatements =
        representativeStatements == null ? Map.of() : Map.copyOf(representativeStatements);
    if (dependencyPlans == null) {
      dependencyPlans = Map.of();
    } else {
      java.util.Map<String, Set<String>> copy = new java.util.LinkedHashMap<>();
      dependencyPlans.forEach((key, value) -> copy.put(key, Set.copyOf(value)));
      dependencyPlans = Map.copyOf(copy);
    }
    verifiedFacts = verifiedFacts == null ? List.of() : List.copyOf(verifiedFacts);
    exactCounterexamples =
        exactCounterexamples == null ? List.of() : List.copyOf(exactCounterexamples);
    permanentNegativeSummary =
        permanentNegativeSummary == null ? List.of() : List.copyOf(permanentNegativeSummary);
    activeResearchFindings =
        activeResearchFindings == null ? List.of() : List.copyOf(activeResearchFindings);
    sharpObstruction = normalize(sharpObstruction);
    allowedActions = allowedActions == null ? List.of() : List.copyOf(allowedActions);
    blockedGenericActions =
        blockedGenericActions == null ? List.of() : List.copyOf(blockedGenericActions);
    if (canonicalMemberIds.isEmpty() || newTargetQuotaRemaining < 0) {
      throw new IllegalArgumentException("focused brief requires members and a valid quota");
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip();
  }

  private static String require(String value, String field) {
    String normalized = normalize(value);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
