package io.github.aililuola.mathproofmesh.concurrency;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record ResearchMergePlan(
    String epochId,
    String snapshotHash,
    List<ResearchMergeDecision> decisions,
    String mergePlanHash) {
  public ResearchMergePlan {
    epochId = Objects.requireNonNull(epochId, "epochId").strip();
    snapshotHash = Objects.requireNonNull(snapshotHash, "snapshotHash").strip();
    decisions =
        decisions == null
            ? List.of()
            : decisions.stream()
                .sorted(
                    Comparator.comparingInt(ResearchMergeDecision::stableOrdinal)
                        .thenComparing(ResearchMergeDecision::routeId)
                        .thenComparing(ResearchMergeDecision::claimId)
                        .thenComparing(ResearchMergeDecision::obligationId)
                        .thenComparing(ResearchMergeDecision::workItemId))
                .toList();
    String computed =
        CanonicalJson.stableHash(
            List.of(epochId, snapshotHash, CanonicalJson.stableHash(decisions)));
    mergePlanHash = mergePlanHash == null || mergePlanHash.isBlank() ? computed : mergePlanHash.strip();
    if (epochId.isEmpty() || snapshotHash.isEmpty() || !computed.equals(mergePlanHash)) {
      throw new IllegalArgumentException("invalid deterministic merge plan");
    }
  }

  public ResearchMergePlan(
      String epochId, String snapshotHash, List<ResearchMergeDecision> decisions) {
    this(epochId, snapshotHash, decisions, "");
  }

  @Override
  public List<ResearchMergeDecision> decisions() {
    return List.copyOf(decisions);
  }

  public List<String> acceptedResultHashes() {
    return decisions.stream()
        .filter(ResearchMergeDecision::accepted)
        .map(ResearchMergeDecision::resultHash)
        .toList();
  }
}
