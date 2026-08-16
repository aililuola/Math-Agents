package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimStatementAssessment;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimStatementAuthorityService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimStatementAuthorityPolarityIsolationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void claimCourtDoesNotApplyPositiveCounterexampleToNegativePolarityClaim() throws Exception {
    try (var harness =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory, "claim-authority-polarity-isolation")) {
      harness.initializeRoute();
      var fixture =
          DesktopComputationSemanticContextTestSupport.bind(
              harness,
              DesktopComputationIssue010Support.graphCounterexample(
                  "claim-authority-polarity-isolation", 62),
              "claim-authority-polarity-claim");
      harness.runComputation(fixture.spec());

      var positive =
          DesktopNegativeKnowledgePolarityTestSupport.frozen(
              fixture.binding(), "positive");
      var negative =
          DesktopNegativeKnowledgePolarityTestSupport.frozen(
              fixture.binding(), "negative");
      ClaimStatementAuthorityService authority = new ClaimStatementAuthorityService();
      var positiveResult =
          authority.assess(
              positive,
              DesktopNegativeKnowledgePolarityTestSupport.noCounterexample(positive),
              harness.typedMemory().negativeKnowledgeRegistry(),
              0,
              List.of());
      var negativeResult =
          authority.assess(
              negative,
              DesktopNegativeKnowledgePolarityTestSupport.noCounterexample(negative),
              harness.typedMemory().negativeKnowledgeRegistry(),
              0,
              List.of());

      int positiveExactBlocks =
          positiveResult.assessment()
                  == ClaimStatementAssessment.REFUTED_BY_VERIFIED_EVIDENCE
              ? 1
              : 0;
      int negativePolarityFalseBlocks =
          negativeResult.assessment()
                  == ClaimStatementAssessment.REFUTED_BY_VERIFIED_EVIDENCE
              ? 1
              : 0;
      assertThat(positiveExactBlocks).isOne();
      assertThat(negativePolarityFalseBlocks).isZero();
      assertThat(negativeResult.assessment())
          .isEqualTo(ClaimStatementAssessment.OPEN_NO_VERIFIED_REFUTATION);
      System.out.println("CLAIM_COURT_POSITIVE_EXACT_REFUTATIONS=" + positiveExactBlocks);
      System.out.println("CLAIM_COURT_NEGATIVE_POLARITY_FALSE_REFUTATIONS="
          + negativePolarityFalseBlocks);
    }
  }
}
