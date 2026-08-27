package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopClaimCourtBatchProductionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void independentClaimCasesRunConcurrentlyWithoutCrossCaseVisibility() {
    var run =
        DesktopResearchConcurrencyTestSupport.run(
            ResearchWorkKind.CLAIM_PROOF_AUDIT, 4, ignored -> 40L);
    assertThat(run.maximumConcurrency()).isEqualTo(4);
    assertThat(run.results())
        .extracting(result -> result.publicStructuredResult().get("ordinal"))
        .containsExactly(0, 1, 2, 3);
    assertThat(run.results()).allMatch(result -> result.snapshotHash().equals(run.snapshot().snapshotHash()));
  }

  @Test
  void coordinatorRunsIndependentClaimCourtCasesAcrossCredentials() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("claim-court-batch"), "claim-court-batch")) {
      harness.prepareIndependentClaimCourtBatch(4);
      harness.integrateInstalledRound();

      assertThat(harness.maximumConcurrentClaimCourtCalls()).isGreaterThanOrEqualTo(4);
      assertThat(harness.productionState().routeCount()).isEqualTo(4);
    }
  }
}
