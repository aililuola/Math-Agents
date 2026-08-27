package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactKind;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopRouteTheoremClaimLifecycleProjectionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void rejectedRouteTheoremAuthorityCannotRemainVerifiedOrEnterSynthesis() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("route-theorem-lifecycle"),
            "route-theorem-lifecycle")) {
      harness.freezeAndCreateRoute();
      assertThat(harness.reserveInitialExplorationBudget()).isTrue();
      harness.installVerifiedAttemptWithOversizedRouteTheoremRepair(1);

      harness.integrateInstalledRound();

      var theoremArtifact =
          harness.attemptArtifacts().records().stream()
              .filter(record -> record.kind() == AttemptArtifactKind.ROUTE_THEOREM)
              .findFirst()
              .orElseThrow();
      var theoremCase =
          harness.claimCourt().records().stream()
              .filter(record -> record.frozenClaim().claimId().equals(theoremArtifact.claimId()))
              .findFirst()
              .orElseThrow();

      assertThat(theoremCase.outcome()).isEqualTo(ClaimCourtOutcome.REPAIR_EXHAUSTED);
      assertThat(theoremCase.history())
          .anySatisfy(entry -> assertThat(entry).contains("PATCH_CHANGED_STEP_LIMIT_EXCEEDED"));
      assertThat(theoremArtifact.status()).isEqualTo(AttemptArtifactStatus.UNCERTAIN);
      assertThat(harness.routeStatus()).isEqualTo("unverified");
      assertThat(harness.routeFailureReason())
          .contains("ROUTE_THEOREM_CLAIM_REPAIR_EXHAUSTED");
      assertThat(harness.proofGraph().getObligation("main-goal").status()).isEqualTo("open");
      assertThat(harness.typedMemory().facts())
          .noneSatisfy(fact -> assertThat(fact.messageId()).isEqualTo(theoremArtifact.claimId()));
      assertThat(harness.synthesisGatePassed()).isFalse();

      System.out.println("ROUTE THEOREM CLAIM LIFECYCLE DIAGNOSTIC");
      System.out.println("ROUTE_LEVEL_REVIEWS_PASSED=1");
      System.out.println("ROUTE_THEOREM_REPAIR_EXHAUSTIONS=1");
      System.out.println("OVERSIZED_REPAIR_BYPASSES=0");
      System.out.println("STALE_VERIFIED_ROUTE_PROJECTIONS=0");
      System.out.println("PREMATURE_SYNTHESIS_ADMISSIONS=0");
      System.out.println("MAIN_GOAL_CLOSURES=0");
      System.out.println("RESULT=PASS");
    }
  }

  @Test
  void routeLevelReviewAloneCannotSatisfySynthesisInProductionProfile() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("production-profile-gate"),
            "production-profile-gate",
            "deepseek-v4-pro.yaml")) {
      harness.freezeAndCreateRoute();
      harness.installVerifiedAttemptWithOversizedRouteTheoremRepair(1);

      assertThat(harness.routeStatus()).isEqualTo("verified");
      assertThat(harness.proofGraph().getObligation("main-goal").status()).isEqualTo("open");
      assertThat(harness.synthesisGatePassed()).isFalse();
    }
  }

  @Test
  void failedClaimProjectionRestoresRouteAuthorityBeforeDeterministicRetry() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("projection-rollback"), "projection-rollback")) {
      harness.freezeAndCreateRoute();
      assertThat(harness.reserveInitialExplorationBudget()).isTrue();
      harness.installVerifiedAttemptWithOversizedRouteTheoremRepair(1);
      harness.setFailurePoint(ClaimCourtFailurePoint.AFTER_LEMMA_MEMORY_PROJECTION);

      assertThatThrownBy(harness::integrateInstalledRound)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("simulated Claim Court failure");
      assertThat(harness.routeStatus()).isEqualTo("verified");
      assertThat(harness.routeFailureReason()).isNullOrEmpty();

      harness.integrateInstalledRound();

      assertThat(harness.routeStatus()).isEqualTo("unverified");
      assertThat(harness.routeFailureReason())
          .contains("ROUTE_THEOREM_CLAIM_REPAIR_EXHAUSTED");
      assertThat(harness.proofGraph().getObligation("main-goal").status()).isEqualTo("open");
    }
  }
}
