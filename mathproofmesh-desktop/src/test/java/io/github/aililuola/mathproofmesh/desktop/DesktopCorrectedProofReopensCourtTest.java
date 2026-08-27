package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtRecord;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopCorrectedProofReopensCourtTest {
  private static final String CLAIM_ID = "corrected-proof-claim";
  private static final String STATEMENT = "A finite injective self-map is surjective.";

  @TempDir Path temporaryDirectory;

  @Test
  void correctedProofGetsANewCourtCaseWithoutChangingStatementIdentity() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("corrected-proof"), "corrected-proof")) {
      harness.freezeAndCreateRoute();
      harness.installSingleClaimProofRound(
          0, CLAIM_ID, STATEMENT, "UNREPAIRABLE_PROOF: the conclusion is merely asserted.");
      harness.integrateInstalledRound();

      long proofAuditsAfterV1 = harness.callsForSchema("ClaimProofAuditBatch");
      long blindCallsAfterV1 = harness.callsForSchema("ClaimBlindAdjudicationBatch");
      harness.installSingleClaimProofRound(
          1,
          CLAIM_ID,
          STATEMENT,
          "CORRECTED_PROOF: injectivity permutes a finite set, hence is surjective.");
      harness.integrateInstalledRound();

      List<ClaimCourtRecord> cases = casesForClaim(harness);
      long statementIdentities =
          cases.stream().map(record -> record.frozenClaim().statementCaseId()).distinct().count();
      long proofRevisions =
          harness.claimProofRevisions().recordsForClaim(CLAIM_ID).stream()
              .map(revision -> revision.revisionId())
              .distinct()
              .count();
      long proofCases = cases.stream().map(ClaimCourtRecord::courtCaseId).distinct().count();
      long correctedProofReviewCalls =
          harness.callsForSchema("ClaimProofAuditBatch") - proofAuditsAfterV1;
      long correctedProofSkipped = correctedProofReviewCalls == 0L ? 1L : 0L;
      long facts = factsForClaim(harness);

      assertThat(cases).hasSize(2);
      assertThat(statementIdentities).isEqualTo(1L);
      assertThat(proofRevisions).isEqualTo(2L);
      assertThat(proofCases).isEqualTo(2L);
      assertThat(cases)
          .extracting(ClaimCourtRecord::outcome)
          .containsExactlyInAnyOrder(
              ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN,
              ClaimCourtOutcome.VERIFIED);
      assertThat(correctedProofReviewCalls).isEqualTo(1L);
      assertThat(correctedProofSkipped).isZero();
      assertThat(harness.callsForSchema("ClaimBlindAdjudicationBatch") - blindCallsAfterV1)
          .isEqualTo(1L);
      assertThat(facts).isEqualTo(1L);
      assertThat(
              harness.lemmaMemory().claims().stream()
                  .filter(claim -> claim.claimId().equals(CLAIM_ID))
                  .findFirst()
                  .orElseThrow()
                  .status())
          .isEqualTo(ClaimStatus.VERIFIED);

      System.out.println("CORRECTED PROOF COURT IDENTITY DIAGNOSTIC");
      System.out.println("STATEMENT_IDENTITIES=" + statementIdentities);
      System.out.println("PROOF_REVISIONS=" + proofRevisions);
      System.out.println("PROOF_COURT_CASES=" + proofCases);
      System.out.println("V1_OUTCOME=" + ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN);
      System.out.println("V2_OUTCOME=" + ClaimCourtOutcome.VERIFIED);
      System.out.println("CORRECTED_PROOF_REVIEW_CALLS=" + correctedProofReviewCalls);
      System.out.println(
          "CORRECTED_PROOF_SKIPPED_BY_OLD_TERMINAL_CASE=" + correctedProofSkipped);
      System.out.println("RESULT=PASS");
    }
  }

  static List<ClaimCourtRecord> casesForClaim(DesktopClaimSalvageTestHarness harness) {
    return harness.claimCourt().records().stream()
        .filter(record -> record.frozenClaim().claimId().equals(CLAIM_ID))
        .toList();
  }

  static long factsForClaim(DesktopClaimSalvageTestHarness harness) {
    return harness.typedMemory().facts().stream()
        .filter(message -> message.messageId().equals(CLAIM_ID))
        .count();
  }

  static String claimId() {
    return CLAIM_ID;
  }

  static String statement() {
    return STATEMENT;
  }
}
