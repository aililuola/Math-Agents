package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkResultEnvelope;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkResultStatus;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopResearchEpochProductionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void keepsFourResearchCredentialsBusyAndPreparesOneDeterministicMerge() {
    var run =
        DesktopResearchConcurrencyTestSupport.run(
            ResearchWorkKind.ROUTE_EXPLORATION, 8, ignored -> 35L);

    assertThat(run.results()).hasSize(8);
    assertThat(run.maximumConcurrency()).isEqualTo(4);
    assertThat(run.metrics().maxActiveProviderCalls()).isEqualTo(4);
    assertThat(run.mergePlan().decisions()).hasSize(8).allMatch(decision -> decision.accepted());
    assertThat(run.mergePlan().decisions())
        .extracting(decision -> decision.stableOrdinal())
        .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
  }

  @Test
  void coordinatorOwnsTheEpochLedgersAndPersistsTheirProjection() throws Exception {
    try (var harness =
        DesktopComputationIssue010CoordinatorHarness.open(
            temporaryDirectory.resolve("coordinator-epoch"), "coordinator-epoch")) {
      var snapshot = DesktopResearchConcurrencyTestSupport.snapshot("coordinator-owned-epoch");
      var items =
          DesktopResearchConcurrencyTestSupport.items(
              snapshot, ResearchWorkKind.ROUTE_REVIEW, 1);
      var settled =
          harness
              .coordinator()
              .executeFrozenResearchEpoch(
                  snapshot,
                  items,
                  (frozen, item, lease) ->
                      new ResearchWorkResultEnvelope(
                          item.workItemId(),
                          frozen.epochId(),
                          frozen.snapshotHash(),
                          lease.agent().id(),
                          "request-coordinator",
                          ResearchWorkResultStatus.SUCCEEDED,
                          Map.of("path", "coordinator"),
                          List.of(),
                          List.of(),
                          List.of()));
      harness.checkpointRoundTrip();
      DesktopSolveCheckpoint checkpoint = harness.readCheckpoint();

      assertThat(settled).hasSize(1);
      assertThat(checkpoint.researchEpochs().epochs()).hasSize(1);
      assertThat(checkpoint.researchTasks().tasks()).hasSize(1);
      assertThat(checkpoint.researchResults().artifacts()).hasSize(1);
    }
  }
}
