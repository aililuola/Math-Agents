package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopRepairableProofNoCourtBlackBoxTest {
  @TempDir Path temporaryDirectory;

  @Test
  void repairableFiniteSetProofDoesNotBecomeAFalseStatement() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("repairable-proof"), "repairable-proof-no-court")) {
      harness.freezeAndCreateRoute();
      String claimId = "finite-surjection-bijection";
      harness.runSingleLegacyClaimRound(
          0,
          claimId,
          "FALSE_LOCAL_REPAIRABLE: If finite sets A and B have equal size, every surjection "
              + "f:A->B is a bijection; the supplied proof omits the counting bridge.");

      ClaimCard claim =
          harness.lemmaMemory().claims().stream()
              .filter(candidate -> candidate.claimId().equals(claimId))
              .findFirst()
              .orElseThrow();
      long minimalRepairCalls =
          harness.claimReviewRequests().stream()
              .filter(request -> "ClaimMinimalRepairBatch".equals(request.schemaName()))
              .count();
      long blindCalls =
          harness.claimReviewRequests().stream()
              .filter(request -> "ClaimBlindAdjudicationBatch".equals(request.schemaName()))
              .count();

      System.out.println(
          "CLAIM_COURT_SCHEMAS="
              + harness.claimReviewRequests().stream().map(request -> request.schemaName()).toList());

      System.out.println("REPAIRABLE_PROOF_CASES=1");
      System.out.println("MINIMAL_REPAIR_CALLS=" + minimalRepairCalls);
      System.out.println("BLIND_ADJUDICATION_CALLS=" + blindCalls);
      System.out.println("CLAIM_STATUS=" + claim.status().name());
      assertThat(claim.status()).isNotEqualTo(ClaimStatus.REJECTED);
      assertThat(claim.status()).isEqualTo(ClaimStatus.VERIFIED);
      assertThat(minimalRepairCalls).isEqualTo(1);
      assertThat(blindCalls).isEqualTo(1);
    }
  }
}
