package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopFailedRouteClaimSalvageProductionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void independentlyValidLocalClaimSurvivesAnOverallFailedRoute() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("failed-route"), "failed-route-claim-salvage")) {
      harness.freezeAndCreateRoute();
      harness.addCounterexampleTargets();
      harness.runFailedRound(0);

      assertThat(harness.lemmaMemory().verified())
          .extracting(ClaimCard::claimId)
          .contains("correct-local-0")
          .doesNotContain("false-local-0", "unsupported-local-0");
      assertThat(harness.typedMemory().facts())
          .anySatisfy(fact -> assertThat(fact.statement()).contains("CORRECT_LOCAL_R0"))
          .noneSatisfy(fact -> assertThat(fact.statement()).contains("FALSE_LOCAL_R0"))
          .noneSatisfy(fact -> assertThat(fact.statement()).contains("UNSUPPORTED_LOCAL_R0"))
          .noneSatisfy(fact -> assertThat(fact.statement()).contains("FALSE_ROUTE_THEOREM_R0"));
      assertThat(harness.typedMemory().negatives())
          .anySatisfy(
              failure ->
                  assertThat(failure.artifactRefs())
                      .contains(
                          "correct-local-0",
                          "counterexample-0",
                          "false-local-0",
                          "unsupported-local-0"));
      assertThat(harness.reviewCalls(DesktopClaimSalvageTestHarness.attemptId(0))).isEqualTo(1);
    }
  }
}
