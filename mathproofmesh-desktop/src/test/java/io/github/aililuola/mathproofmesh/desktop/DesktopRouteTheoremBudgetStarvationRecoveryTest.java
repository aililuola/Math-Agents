package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimReviewBatch;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactKind;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopRouteTheoremBudgetStarvationRecoveryTest {
  @TempDir Path tempDir;

  @Test
  void closureCriticalRouteTheoremCannotBeStarvedByOptionalLocalClaims() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(tempDir.resolve("run"), "route-theorem-budget")) {
      harness.freezeAndCreateRoute();
      assertThat(harness.reserveInitialExplorationBudget()).isTrue();
      int optionalClaimCount = 4;
      harness.installVerifiedAttemptWithOptionalClaims(1, optionalClaimCount);

      harness.integrateInstalledRound();

      var routeTheorem =
          harness.attemptArtifacts().records().stream()
              .filter(record -> record.kind() == AttemptArtifactKind.ROUTE_THEOREM)
              .findFirst()
              .orElseThrow();
      long routeTheoremPromotions =
          harness.attemptArtifacts().records().stream()
              .filter(record -> record.kind() == AttemptArtifactKind.ROUTE_THEOREM)
              .filter(record -> record.status() == AttemptArtifactStatus.PROMOTED_FACT)
              .count();
      long mainGoalClosures =
          "closed".equals(harness.proofGraph().getObligation("main-goal").status()) ? 1L : 0L;
      String theoremClaimId = routeTheorem.claimId();
      var requestClaimIds = harness.claimCourtRequestClaimIds();
      long optionalClaimPreemptions =
          requestClaimIds.isEmpty() || theoremClaimId.equals(requestClaimIds.getFirst()) ? 0L : 1L;

      assertThat(requestClaimIds).isNotEmpty();
      assertThat(requestClaimIds.getFirst()).isEqualTo(theoremClaimId);
      assertThat(routeTheoremPromotions).isEqualTo(1L);
      assertThat(mainGoalClosures).isEqualTo(1L);
      assertThat(harness.typedMemory().facts())
          .anySatisfy(fact -> assertThat(fact.statement()).isEqualTo(routeTheorem.statement()));

      System.out.println("ROUTE THEOREM BUDGET STARVATION DIAGNOSTIC");
      System.out.println("INITIAL_FIXED_ENVELOPE_RESERVED=1");
      System.out.println("OPTIONAL_LOCAL_CLAIMS=" + optionalClaimCount);
      System.out.println("ROUTE_THEOREM_COURT_FIRST=" + (optionalClaimPreemptions == 0L ? 1 : 0));
      System.out.println("OPTIONAL_CLAIM_PREEMPTIONS=" + optionalClaimPreemptions);
      System.out.println("ROUTE_THEOREM_PROMOTIONS=" + routeTheoremPromotions);
      System.out.println("MAIN_GOAL_CLOSURES=" + mainGoalClosures);
      System.out.println("RESULT=PASS");
    }
  }

  @Test
  void closureCriticalRouteTheoremCannotBeCrowdedOutOfFullReviewBatch() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(tempDir.resolve("batch"), "route-theorem-batch")) {
      harness.freezeAndCreateRoute();
      harness.installVerifiedAttemptWithOptionalClaims(1, ClaimReviewBatch.MAX_DECISIONS);

      var reviewable = harness.prepareClaimCourtWorkItemForInstalledRoute();

      assertThat(reviewable).hasSize(ClaimReviewBatch.MAX_DECISIONS);
      assertThat(reviewable.getFirst().kind()).isEqualTo(AttemptArtifactKind.ROUTE_THEOREM);
      assertThat(reviewable)
          .filteredOn(record -> record.kind() == AttemptArtifactKind.ROUTE_THEOREM)
          .hasSize(1);

      System.out.println("ROUTE THEOREM BATCH CROWDING DIAGNOSTIC");
      System.out.println("OPTIONAL_LOCAL_CLAIMS=" + ClaimReviewBatch.MAX_DECISIONS);
      System.out.println("CLAIM_REVIEW_BATCH_LIMIT=" + ClaimReviewBatch.MAX_DECISIONS);
      System.out.println("ROUTE_THEOREMS_RETAINED=1");
      System.out.println("ROUTE_THEOREMS_CROWDED_OUT=0");
      System.out.println("RESULT=PASS");
    }
  }
}
