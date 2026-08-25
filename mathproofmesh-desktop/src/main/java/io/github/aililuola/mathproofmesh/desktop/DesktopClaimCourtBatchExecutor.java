package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactKind;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Orders closure-critical Claim Court work without expanding the main coordinator class. */
final class DesktopClaimCourtBatchExecutor {
  private DesktopClaimCourtBatchExecutor() {}

  static <T> boolean execute(
      DesktopBudgetScheduler budgetScheduler,
      List<T> specs,
      Function<T, String> claimId,
      String authorityHash,
      Predicate<T> closureCritical,
      int round,
      BiConsumer<String, List<T>> epochExecutor) {
    Objects.requireNonNull(budgetScheduler, "budgetScheduler");
    List<T> orderedSpecs = List.copyOf(Objects.requireNonNull(specs, "specs"));
    if (orderedSpecs.isEmpty()) {
      return true;
    }
    if (!budgetScheduler.reserveClaimCourtBatch(
        orderedSpecs.stream().map(claimId).toList(), authorityHash)) {
      return false;
    }
    List<T> supporting = new ArrayList<>();
    int theoremOrdinal = 0;
    for (T spec : orderedSpecs) {
      if (closureCritical.test(spec)) {
        epochExecutor.accept(
            "claim-court-route-theorem-r" + round + "-" + theoremOrdinal++, List.of(spec));
      } else {
        supporting.add(spec);
      }
    }
    if (!supporting.isEmpty()) {
      epochExecutor.accept("claim-court-supporting-r" + round, List.copyOf(supporting));
    }
    return true;
  }

  static Comparator<AttemptArtifactRecord> reviewPriority() {
    return Comparator.comparingInt(
            (AttemptArtifactRecord record) ->
                record.kind() == AttemptArtifactKind.ROUTE_THEOREM ? 0 : 1)
        .thenComparing(AttemptArtifactRecord::artifactId);
  }
}
