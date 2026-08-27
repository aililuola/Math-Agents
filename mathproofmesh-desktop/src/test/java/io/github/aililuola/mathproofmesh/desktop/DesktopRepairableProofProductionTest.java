package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimProofRevisionStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopRepairableProofProductionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void boundedPatchRequiresBlindPassBeforeVerification() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("repair"), "claim-court-repair")) {
      harness.freezeAndCreateRoute();
      harness.runSingleLegacyClaimRound(
          0,
          "finite-surjection",
          "FALSE_LOCAL_REPAIRABLE: An equal-size finite-set surjection is a bijection; the proof omits one counting bridge.");

      assertThat(harness.claimCourt().records().getFirst().outcome())
          .isEqualTo(ClaimCourtOutcome.VERIFIED);
      assertThat(harness.claimCourt().records().getFirst().repairAttempts()).isEqualTo(1);
      assertThat(harness.claimProofRevisions().recordsForClaim("finite-surjection")).hasSize(2);
      assertThat(harness.claimProofRevisions().recordsForClaim("finite-surjection"))
          .anyMatch(revision -> revision.status() == ClaimProofRevisionStatus.BLIND_VERIFIED);
      assertThat(harness.lemmaMemory().claims())
          .filteredOn(claim -> claim.claimId().equals("finite-surjection"))
          .extracting(claim -> claim.status())
          .containsExactly(ClaimStatus.VERIFIED);
      assertThat(harness.callsForSchema("ClaimMinimalRepairBatch")).isEqualTo(1);
      assertThat(harness.callsForSchema("ClaimBlindAdjudicationBatch")).isEqualTo(1);
    }
  }
}
