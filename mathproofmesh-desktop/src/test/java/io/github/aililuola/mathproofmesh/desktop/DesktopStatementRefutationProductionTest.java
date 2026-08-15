package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopStatementRefutationProductionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void exactIndependentlyCheckedWitnessRefutesWithoutRepair() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("refutation"), "claim-court-refutation")) {
      harness.freezeAndCreateRoute();
      harness.runSingleLegacyClaimRound(
          0,
          "false-connected-graph",
          "FALSE_LOCAL_REFUTED: Every connected finite graph has a Hamiltonian cycle; P4 refutes it.");

      assertThat(harness.claimCourt().records().getFirst().outcome())
          .isEqualTo(ClaimCourtOutcome.REFUTED);
      assertThat(harness.claimCourt().records().getFirst().refutationEvidenceIds()).hasSize(1);
      assertThat(harness.lemmaMemory().claims())
          .filteredOn(claim -> claim.claimId().equals("false-connected-graph"))
          .extracting(claim -> claim.status())
          .containsExactly(ClaimStatus.REJECTED);
      assertThat(harness.callsForSchema("ClaimCounterexampleWitnessReviewBatch")).isEqualTo(1);
      assertThat(harness.callsForSchema("ClaimProofAuditBatch")).isZero();
      assertThat(harness.callsForSchema("ClaimMinimalRepairBatch")).isZero();
      assertThat(harness.callsForSchema("ClaimBlindAdjudicationBatch")).isZero();
    }
  }
}
