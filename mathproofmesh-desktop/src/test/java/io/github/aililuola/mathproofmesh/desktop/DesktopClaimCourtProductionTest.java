package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimCourtProductionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void validLocalClaimTraversesRealCourtAndFactProjection() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("valid-production"), "claim-court-valid-production")) {
      harness.freezeAndCreateRoute();
      harness.runSingleLegacyClaimRound(
          0,
          "valid-linear-claim",
          "VALID_LINEAR: A linear map with zero kernel is injective, with a complete proof.");

      assertThat(harness.claimCourt().records()).hasSize(1);
      assertThat(harness.claimCourt().records().getFirst().outcome())
          .isEqualTo(ClaimCourtOutcome.VERIFIED);
      assertThat(harness.lemmaMemory().claims())
          .filteredOn(claim -> claim.claimId().equals("valid-linear-claim"))
          .extracting(claim -> claim.status())
          .containsExactly(ClaimStatus.VERIFIED);
      assertThat(harness.typedMemory().facts())
          .anyMatch(fact -> fact.messageId().equals("valid-linear-claim"));
      assertThat(harness.callsForSchema("ClaimStatementFalsificationBatch")).isEqualTo(1);
      assertThat(harness.callsForSchema("ClaimProofAuditBatch")).isEqualTo(1);
      assertThat(harness.callsForSchema("ClaimMinimalRepairBatch")).isZero();
      assertThat(harness.callsForSchema("ClaimBlindAdjudicationBatch")).isEqualTo(1);
    }
  }
}
