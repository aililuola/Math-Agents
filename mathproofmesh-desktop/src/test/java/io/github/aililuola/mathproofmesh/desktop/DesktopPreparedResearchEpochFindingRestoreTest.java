package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochStatus;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ResearchCheckpointFrame;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointLedger;
import io.github.aililuola.mathproofmesh.research.ResearchCheckpointSnapshot;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopPreparedResearchEpochFindingRestoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void mergePreparedEpochReplaysWhenOnlyProblemBoundResearchSidecarAdvanced()
      throws Exception {
    String runId = "prepared-epoch-research-sidecar";
    Path runDirectory = temporaryDirectory.resolve("run");
    DesktopSolveCheckpoint prepared;
    String rootHash;
    String negativeHash;
    int providerCallsBeforeCrash;

    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      harness.prepareMixedClaimCourtBatch();
      harness.setClaimCourtDelays(
          java.util.Map.of(
              "crash-claim-0", 0L, "crash-claim-1", 0L, "crash-claim-2", 0L));
      rootHash = harness.rootGoal().sourceStatementHash();
      negativeHash = harness.negativeRegistryHash();
      harness.setAuthoritativeConcurrencyFailurePoint(
          DesktopSolveCoordinator.AuthoritativeConcurrencyFailurePoint
              .AFTER_RESULTS_DURABLE_BEFORE_COMMIT);

      assertThatThrownBy(harness::integrateInstalledRound)
          .isInstanceOf(
              DesktopSolveCoordinator.SimulatedAuthoritativeConcurrencyProcessTermination.class);
      providerCallsBeforeCrash = harness.providerCallCount();
      prepared = harness.readPersistedCheckpoint();
    }

    assertThat(prepared.researchEpochs().epochs())
        .singleElement()
        .satisfies(epoch -> assertThat(epoch.status()).isEqualTo(ResearchEpochStatus.MERGE_PREPARED));

    ResearchCheckpointLedger sidecar =
        ResearchCheckpointLedger.restore(prepared.researchCheckpoints());
    sidecar.appendEnvelopeFrame(
        prepared.problemHash(),
        "route-prepared",
        "independent_exploration",
        "provider-call-durable-sidecar",
        new ResearchCheckpointFrame(
            0, "Durable non-authoritative finding frontier captured before restore.", List.of()));
    DesktopSolveCheckpoint advanced = withResearchCheckpoints(prepared, sidecar.snapshot());

    assertThat(sidecar.ledgerHash())
        .isNotEqualTo(ResearchCheckpointLedger.restore(prepared.researchCheckpoints()).ledgerHash());

    DesktopSolveCheckpoint committed;
    int replayProviderCalls;
    try (DesktopClaimSalvageTestHarness restored =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      restored.restore(advanced);
      assertThat(restored.rootGoal().sourceStatementHash()).isEqualTo(rootHash);
      assertThat(restored.negativeRegistryHash()).isEqualTo(negativeHash);

      restored.integrateInstalledRound();
      replayProviderCalls = restored.providerCallCount();
      committed = restored.readPersistedCheckpoint();

      assertThat(restored.rootGoal().sourceStatementHash()).isEqualTo(rootHash);
    }

    long committedEpochs =
        committed.researchEpochs().epochs().stream()
            .filter(epoch -> epoch.status() == ResearchEpochStatus.COMMITTED)
            .count();
    assertThat(providerCallsBeforeCrash).isPositive();
    assertThat(replayProviderCalls).isZero();
    assertThat(committedEpochs).isOne();
    assertThat(committed.researchAuthorityMutations().authorityMutations()).hasSize(1);
    assertThat(committed.researchAuthorityMutations().mergeReceipts()).hasSize(1);
    assertThat(committed.researchCheckpoints().checkpoints())
        .containsKey(
            sidecar.checkpointsForRoute("route-prepared").getFirst().checkpointId());

    System.out.println("PREPARED RESEARCH EPOCH SIDECAR RESTORE DIAGNOSTIC");
    System.out.println("PREPARED_EPOCHS=1");
    System.out.println("DURABLE_RESEARCH_CHECKPOINTS=1");
    System.out.println("RESTORE_FAILURES=0");
    System.out.println("DUPLICATE_PROVIDER_CALLS=" + replayProviderCalls);
    System.out.println("COMMITTED_EPOCHS=" + committedEpochs);
    System.out.println("ROOT_HASH_CHANGES=0");
    System.out.println("NEGATIVE_REGISTRY_HASH_CHANGES=0");
    System.out.println("RESULT=PASS");
  }

  @Test
  void mergePreparedEpochStillQuarantinesCrossProblemResearchSidecar() throws Exception {
    String runId = "prepared-epoch-cross-problem-sidecar";
    Path runDirectory = temporaryDirectory.resolve("cross-problem");
    DesktopSolveCheckpoint prepared;
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      harness.prepareMixedClaimCourtBatch();
      harness.setClaimCourtDelays(
          java.util.Map.of(
              "crash-claim-0", 0L, "crash-claim-1", 0L, "crash-claim-2", 0L));
      harness.setAuthoritativeConcurrencyFailurePoint(
          DesktopSolveCoordinator.AuthoritativeConcurrencyFailurePoint
              .AFTER_RESULTS_DURABLE_BEFORE_COMMIT);
      assertThatThrownBy(harness::integrateInstalledRound)
          .isInstanceOf(
              DesktopSolveCoordinator.SimulatedAuthoritativeConcurrencyProcessTermination.class);
      prepared = harness.readPersistedCheckpoint();
    }

    ResearchCheckpointLedger sidecar =
        ResearchCheckpointLedger.restore(prepared.researchCheckpoints());
    sidecar.appendEnvelopeFrame(
        "different-problem-hash",
        "route-prepared",
        "independent_exploration",
        "provider-call-cross-problem-sidecar",
        new ResearchCheckpointFrame(0, "A checkpoint from a different problem.", List.of()));
    DesktopSolveCheckpoint advanced = withResearchCheckpoints(prepared, sidecar.snapshot());

    try (DesktopClaimSalvageTestHarness restored =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      assertThatThrownBy(() -> restored.restore(advanced))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("QUARANTINED_PARTIAL_AUTHORITY_COMMIT");
    }
  }

  private static DesktopSolveCheckpoint withResearchCheckpoints(
      DesktopSolveCheckpoint checkpoint, ResearchCheckpointSnapshot researchCheckpoints) {
    ObjectNode tree = (ObjectNode) ContractObjectMapper.toTree(checkpoint);
    tree.set("researchCheckpoints", ContractObjectMapper.toTree(researchCheckpoints));
    return ContractObjectMapper.read(tree.toString(), DesktopSolveCheckpoint.class);
  }
}
