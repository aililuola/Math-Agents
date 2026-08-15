package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimSemanticContextBinding;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopLocalClaimPolarityIsolationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void positiveAndNegativeLocalClaimsHaveDistinctCourtIdentity() throws Exception {
    var positive = freeze("positive", "local-positive", temporaryDirectory.resolve("positive"));
    var negative = freeze("negative", "local-negative", temporaryDirectory.resolve("negative"));

    int falseRefutations =
        positive.claimSemanticHash().equals(negative.claimSemanticHash())
                || positive.statementCaseId().equals(negative.statementCaseId())
            ? 1
            : 0;
    assertThat(falseRefutations).isZero();
    System.out.println("LOCAL_CLAIM_POLARITY_FALSE_REFUTATIONS=" + falseRefutations);
  }

  private static io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.FrozenClaimSnapshot
      freeze(String polarity, String runId, Path directory) throws Exception {
    String claimId = "local-polarity";
    ClaimSemanticContextBinding binding =
        new ClaimSemanticContextBinding(
            claimId,
            "@claim",
            List.of(),
            List.of(),
            List.of(),
            List.of("D"),
            polarity);
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(directory, runId)) {
      harness.freezeAndCreateRoute();
      harness.installModernLocalClaimRound(0, claimId, "P", binding);
      harness.integrateInstalledRound();
      return harness.frozenClaim(claimId);
    }
  }
}
