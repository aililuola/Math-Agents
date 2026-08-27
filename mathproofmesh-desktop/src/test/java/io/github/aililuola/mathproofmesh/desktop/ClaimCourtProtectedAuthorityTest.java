package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClaimCourtProtectedAuthorityTest {
  @TempDir Path temporaryDirectory;

  @Test
  void claimCourtChangesOnlyItsOwnedAuthorityProjection() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("protected-authority"),
            "claim-court-protected-authority")) {
      harness.freezeAndCreateRoute();
      harness.runSingleLegacyClaimRound(
          0, "protected-verified", "VALID_PROTECTED: a finite identity map is bijective.");
      DesktopSolveCheckpoint before = harness.checkpointRoundTrip();
      String rootHash = harness.rootGoal().sourceStatementHash();
      String negativeHash = harness.permanentNegativeHash();

      harness.runSingleLegacyClaimRound(
          1,
          "protected-bad-proof",
          "UNREPAIRABLE_PROTECTED: a finite tree has two leaves.");
      harness.runSingleLegacyClaimRound(
          2,
          "protected-refuted",
          "REFUTED_PROTECTED: every connected finite graph is Hamiltonian.");
      DesktopSolveCheckpoint after = harness.checkpointRoundTrip();

      assertThat(harness.rootGoal().sourceStatementHash()).isEqualTo(rootHash);
      assertThat(harness.exactStatement()).isEqualTo(DesktopClaimSalvageTestHarness.SOURCE);
      assertThat(harness.permanentNegativeHash()).isEqualTo(negativeHash);
      assertThat(status(harness, "protected-verified")).isEqualTo(ClaimStatus.VERIFIED);
      assertThat(status(harness, "protected-bad-proof")).isEqualTo(ClaimStatus.UNCERTAIN);
      assertThat(status(harness, "protected-refuted")).isEqualTo(ClaimStatus.REJECTED);
      assertThat(harness.proofGraph().getObligation("main-goal").status()).isEqualTo("open");

      assertThat(hash(before.researchCheckpoints()))
          .isEqualTo(hash(after.researchCheckpoints()));
      assertThat(hash(before.proofGraph().canonicalization()))
          .isEqualTo(hash(after.proofGraph().canonicalization()));
      assertThat(hash(before.proofGraphConvergence()))
          .isEqualTo(hash(after.proofGraphConvergence()));
      assertThat(hash(before.semanticPivots())).isEqualTo(hash(after.semanticPivots()));
      assertThat(
              hash(
                  List.of(
                      before.strategyCandidates(),
                      before.strategyMechanisms(),
                      before.strategyPreflights(),
                      before.strategyPortfolios(),
                      before.portfolioReplenishments())))
          .isEqualTo(
              hash(
                  List.of(
                      after.strategyCandidates(),
                      after.strategyMechanisms(),
                      after.strategyPreflights(),
                      after.strategyPortfolios(),
                      after.portfolioReplenishments())));
    }
  }

  private static ClaimStatus status(
      DesktopClaimSalvageTestHarness harness, String claimId) {
    return harness.lemmaMemory().claims().stream()
        .filter(claim -> claim.claimId().equals(claimId))
        .findFirst()
        .orElseThrow()
        .status();
  }

  private static String hash(Object value) {
    return CanonicalJson.stableHash(value);
  }
}
