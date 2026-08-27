package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopBlindAdjudicationIsolationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void blindPromptContainsNoPriorVerdictOrRoleIdentity() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("blind-isolation"), "claim-court-blind-isolation")) {
      harness.freezeAndCreateRoute();
      harness.runSingleLegacyClaimRound(
          0,
          "blind-valid-claim",
          "VALID_BLIND: A finite injective self-map is surjective, with a complete proof.");

      ProviderRequest request =
          harness.claimReviewRequests().stream()
              .filter(candidate -> candidate.schemaName().equals("ClaimBlindAdjudicationBatch"))
              .findFirst()
              .orElseThrow();
      String prompt = request.messages().getLast().content();
      assertThat(prompt)
          .doesNotContain("authorAgentId")
          .doesNotContain("falsifierAgentId")
          .doesNotContain("auditorAgentId")
          .doesNotContain("repairerAgentId")
          .doesNotContain("prior_verdict")
          .doesNotContain("proof_audit");
    }
  }
}
