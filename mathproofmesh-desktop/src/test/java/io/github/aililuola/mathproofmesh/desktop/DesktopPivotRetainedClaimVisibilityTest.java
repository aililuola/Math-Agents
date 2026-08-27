package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofcontrol.PivotClaimUseChange;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotClaimUsageAction;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopPivotRetainedClaimVisibilityTest {
  @Test
  void verifiedFactKeepsAuthorityAndAppearsInTheNewEpochPrompt(@TempDir Path directory)
      throws Exception {
    try (DesktopSemanticPivotTestHarness harness =
        DesktopSemanticPivotTestHarness.open(directory, "pivot-retained-fact")) {
      String claimId = harness.registerVerifiedFact(71);
      String claimHashBefore = harness.state().claimHash();
      var delta =
          harness.validDelta(
              1,
              List.of(
                  new PivotClaimUseChange(
                      claimId,
                      harness.authoritativeClaimStatementHash(claimId),
                      PivotClaimUsageAction.RETAIN_AS_VERIFIED_FACT,
                      "This independently admitted equivalence remains usable.")));
      harness.apply(delta);

      assertThat(harness.verifiedFactVisible(claimId)).isTrue();
      assertThat(harness.state().claimHash()).isEqualTo(claimHashBefore);
      assertThat(harness.routePromptContext().get("retained_verified_claim_ids").toString())
          .contains(claimId);
      assertThat(harness.routePromptContext().get("active_semantic_pivot_delta"))
          .isEqualTo(delta);
    }
  }
}
