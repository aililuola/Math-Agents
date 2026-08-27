package io.github.aililuola.mathproofmesh.proofgraph;

import io.github.aililuola.mathproofmesh.contract.ObligationFamilyReviewBatch;
import io.github.aililuola.mathproofmesh.contract.ObligationFamilyReviewDecision;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts a bounded provider review into scheduling-only family relations. */
public final class ObligationFamilyReviewService {
  private final double confidenceThreshold;

  public ObligationFamilyReviewService(double confidenceThreshold) {
    if (confidenceThreshold < 0.0d || confidenceThreshold > 1.0d) {
      throw new IllegalArgumentException("confidenceThreshold must be in [0, 1]");
    }
    this.confidenceThreshold = confidenceThreshold;
  }

  public Map<String, BottleneckRelationType> review(
      List<String> expectedCanonicalTargetIds, ObligationFamilyReviewBatch batch) {
    java.util.Objects.requireNonNull(expectedCanonicalTargetIds, "expectedCanonicalTargetIds");
    java.util.Objects.requireNonNull(batch, "batch");
    Map<String, List<ObligationFamilyReviewDecision>> decisions =
        batch.decisions().stream()
            .collect(java.util.stream.Collectors.groupingBy(
                ObligationFamilyReviewDecision::canonicalTargetId,
                LinkedHashMap::new,
                java.util.stream.Collectors.toList()));
    Map<String, BottleneckRelationType> result = new LinkedHashMap<>();
    for (String targetId : expectedCanonicalTargetIds) {
      List<ObligationFamilyReviewDecision> matches = decisions.getOrDefault(targetId, List.of());
      if (matches.size() != 1 || matches.getFirst().confidence() < confidenceThreshold) {
        result.put(targetId, BottleneckRelationType.UNCERTAIN);
        continue;
      }
      result.put(targetId, BottleneckRelationType.valueOf(matches.getFirst().relation()));
    }
    return Map.copyOf(result);
  }
}
