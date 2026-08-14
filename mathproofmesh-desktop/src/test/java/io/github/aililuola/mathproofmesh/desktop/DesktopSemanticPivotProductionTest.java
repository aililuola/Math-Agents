package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofcontrol.PivotDelta;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotDeltaStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopSemanticPivotProductionTest {
  @Test
  void reviewedDeltaCreatesANewEpochAndAtomicRouteProjection(@TempDir Path directory)
      throws Exception {
    try (DesktopSemanticPivotTestHarness harness =
        DesktopSemanticPivotTestHarness.open(directory, "semantic-pivot-production")) {
      DesktopSemanticPivotTestHarness.State before = harness.state();
      PivotDelta delta = harness.validDelta(1);
      String oldObligation = harness.firstRetiredObligation(delta);

      var record = harness.apply(delta);
      DesktopSemanticPivotTestHarness.State after = harness.state();

      assertThat(record.status()).as(record.history().toString()).isEqualTo(PivotDeltaStatus.APPLIED);
      assertThat(after.pivotRecords()).isEqualTo(before.pivotRecords() + 1);
      assertThat(after.strategyEpochs()).isEqualTo(before.strategyEpochs() + 1);
      assertThat(after.activeStrategyId()).isEqualTo(delta.proposedStrategyId());
      assertThat(after.activePivotId()).isEqualTo(delta.pivotId());
      assertThat(after.obligations()).isEqualTo(before.obligations() + 1);
      assertThat(harness.obligationStatus(oldObligation)).isEqualTo("open");
      assertThat(after.rootHash()).isEqualTo(before.rootHash());
      assertThat(after.negativeHash()).isEqualTo(before.negativeHash());
      assertThat(after.claimHash()).isEqualTo(before.claimHash());
      assertThat(after.researchHash()).isEqualTo(before.researchHash());
      assertThat(harness.strategyArchive().originalRetained(delta.sourceStrategyId())).isTrue();
    }
  }

  @Test
  void proposalAndIndependentReviewUseTheRealStructuredProviderChain(@TempDir Path directory)
      throws Exception {
    String runId = "semantic-pivot-provider-chain";
    try (DesktopSemanticPivotProviderTestHarness harness =
        DesktopSemanticPivotProviderTestHarness.open(directory, runId)) {
      var record = harness.runProductionCycle();

      assertThat(record)
          .as(
              "schemas=%s stages=%s events=%s",
              harness.responseSchemas(),
              harness.providerStages(runId),
              harness.progressEvents())
          .isNotNull();
      assertThat(record.status()).isEqualTo(PivotDeltaStatus.APPLIED);
      assertThat(record.applyReceipt()).isNotNull();
      assertThat(record.proposerAgentId()).isNotEqualTo(record.reviewerAgentId());
      assertThat(harness.responseSchemas())
          .containsExactly("SemanticPivotProposal", "SemanticPivotReviewBatch");
      assertThat(harness.providerStages(runId))
          .containsExactly("semantic_pivot_proposal", "semantic_pivot_review");
      assertThat(harness.appliedMetaPivotCount()).isEqualTo(1L);
    }
  }
}
