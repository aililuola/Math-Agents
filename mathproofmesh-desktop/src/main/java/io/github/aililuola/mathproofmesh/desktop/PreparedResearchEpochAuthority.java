package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.concurrency.ResearchAuthorityAnchor;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointRecord;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointSnapshot;
import io.github.aililuola.mathproofmesh.research.ResearchFindingRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

final class PreparedResearchEpochAuthority {
  private PreparedResearchEpochAuthority() {}

  static boolean equivalentAcrossRestore(
      ResearchAuthorityAnchor frozen,
      ResearchAuthorityAnchor restored,
      ResearchCheckpointSnapshot researchCheckpoints) {
    ResearchAuthorityAnchor before = Objects.requireNonNull(frozen, "frozen");
    ResearchAuthorityAnchor after = Objects.requireNonNull(restored, "restored");
    ResearchCheckpointSnapshot checkpoints =
        Objects.requireNonNull(researchCheckpoints, "researchCheckpoints");
    if (!equivalentIgnoringResearchCheckpoints(before, after)) {
      return false;
    }
    return hashEquals(before.researchCheckpointHash(), after.researchCheckpointHash())
        || projectionIsProblemBound(checkpoints, after.problemHash());
  }

  private static boolean equivalentIgnoringResearchCheckpoints(
      ResearchAuthorityAnchor frozen, ResearchAuthorityAnchor restored) {
    return hashEquals(frozen.problemHash(), restored.problemHash())
        && hashEquals(frozen.rootGoalHash(), restored.rootGoalHash())
        && hashEquals(frozen.negativeRegistryHash(), restored.negativeRegistryHash())
        && hashEquals(
            frozen.attemptArtifactLedgerHash(), restored.attemptArtifactLedgerHash())
        && hashEquals(frozen.claimLifecycleHash(), restored.claimLifecycleHash())
        && hashEquals(frozen.proofGraphHash(), restored.proofGraphHash())
        && hashEquals(frozen.convergenceHash(), restored.convergenceHash())
        && hashEquals(frozen.semanticPivotHash(), restored.semanticPivotHash())
        && hashEquals(frozen.strategyPortfolioHash(), restored.strategyPortfolioHash())
        && hashEquals(frozen.claimCourtHash(), restored.claimCourtHash())
        && hashEquals(frozen.brokerHash(), restored.brokerHash())
        && hashEquals(frozen.computationHash(), restored.computationHash());
  }

  private static boolean projectionIsProblemBound(
      ResearchCheckpointSnapshot snapshot, String expectedProblemHash) {
    for (ResearchCheckpointRecord checkpoint : snapshot.checkpoints().values()) {
      if (!hashEquals(checkpoint.problemHash(), expectedProblemHash)) {
        return false;
      }
      for (String findingId : checkpoint.findingIds()) {
        ResearchFindingRecord finding = snapshot.findings().get(findingId);
        if (finding == null
            || !finding.checkpointId().equals(checkpoint.checkpointId())
            || !finding.routeId().equals(checkpoint.routeId())
            || !finding.providerCallId().equals(checkpoint.providerCallId())) {
          return false;
        }
      }
    }
    for (ResearchFindingRecord finding : snapshot.findings().values()) {
      ResearchCheckpointRecord checkpoint = snapshot.checkpoints().get(finding.checkpointId());
      if (!hashEquals(finding.problemHash(), expectedProblemHash)
          || checkpoint == null
          || !checkpoint.findingIds().contains(finding.findingId())) {
        return false;
      }
    }
    return snapshot.audit().stream()
        .allMatch(event -> snapshot.findings().containsKey(event.findingId()));
  }

  private static boolean hashEquals(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }
}
