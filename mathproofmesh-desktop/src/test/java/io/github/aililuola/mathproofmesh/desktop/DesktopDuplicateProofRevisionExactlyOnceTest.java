package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopDuplicateProofRevisionExactlyOnceTest {
  private static final String INVALID_PROOF =
      "UNREPAIRABLE_PROOF: the conclusion is merely asserted.";
  private static final String CORRECTED_PROOF =
      "CORRECTED_PROOF: injectivity permutes a finite set, hence is surjective.";

  @TempDir Path temporaryDirectory;

  @Test
  void duplicateCorrectedProofReusesItsTerminalCourtAndFactProjection() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("duplicate-proof"), "duplicate-proof")) {
      harness.freezeAndCreateRoute();
      harness.installSingleClaimProofRound(
          0,
          DesktopCorrectedProofReopensCourtTest.claimId(),
          DesktopCorrectedProofReopensCourtTest.statement(),
          INVALID_PROOF);
      harness.integrateInstalledRound();
      harness.installSingleClaimProofRound(
          1,
          DesktopCorrectedProofReopensCourtTest.claimId(),
          DesktopCorrectedProofReopensCourtTest.statement(),
          CORRECTED_PROOF);
      harness.integrateInstalledRound();

      int casesBefore = DesktopCorrectedProofReopensCourtTest.casesForClaim(harness).size();
      int revisionsBefore =
          harness
              .claimProofRevisions()
              .recordsForClaim(DesktopCorrectedProofReopensCourtTest.claimId())
              .size();
      int callsBefore = harness.claimReviewRequests().size();
      long factsBefore = DesktopCorrectedProofReopensCourtTest.factsForClaim(harness);

      harness.installSingleClaimProofRound(
          2,
          DesktopCorrectedProofReopensCourtTest.claimId(),
          DesktopCorrectedProofReopensCourtTest.statement(),
          CORRECTED_PROOF);
      harness.integrateInstalledRound();

      long duplicateCases =
          DesktopCorrectedProofReopensCourtTest.casesForClaim(harness).size() - casesBefore;
      long duplicateRevisions =
          harness
                  .claimProofRevisions()
                  .recordsForClaim(DesktopCorrectedProofReopensCourtTest.claimId())
                  .size()
              - revisionsBefore;
      long duplicateProviderCalls = harness.claimReviewRequests().size() - callsBefore;
      long duplicateFactPromotions =
          DesktopCorrectedProofReopensCourtTest.factsForClaim(harness) - factsBefore;

      assertThat(duplicateCases).isZero();
      assertThat(duplicateRevisions).isZero();
      assertThat(duplicateProviderCalls).isZero();
      assertThat(duplicateFactPromotions).isZero();

      System.out.println("DUPLICATE_V2_COURT_CASES=" + duplicateCases);
      System.out.println("DUPLICATE_V2_PROOF_REVISIONS=" + duplicateRevisions);
      System.out.println("DUPLICATE_V2_PROVIDER_CALLS=" + duplicateProviderCalls);
      System.out.println("DUPLICATE_V2_FACT_PROMOTIONS=" + duplicateFactPromotions);
      System.out.println("RESULT=PASS");
    }
  }
}
