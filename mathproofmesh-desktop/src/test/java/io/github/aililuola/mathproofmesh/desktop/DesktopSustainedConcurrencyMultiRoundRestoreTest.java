package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.concurrency.ConcurrencyTelemetryLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkResultEnvelope;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkResultStatus;
import io.github.aililuola.mathproofmesh.provider.ChatMessage;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopSustainedConcurrencyMultiRoundRestoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void twentyRoundsKeepFrozenAuthorityAndSustainedProviderOverlap() throws Exception {
    List<ResearchWorkKind> stages =
        List.of(
            ResearchWorkKind.ROUTE_EXPLORATION,
            ResearchWorkKind.ROUTE_REVIEW,
            ResearchWorkKind.CLAIM_PROOF_AUDIT,
            ResearchWorkKind.FOCUSED_PROVER,
            ResearchWorkKind.SYNTHESIS_REVIEW);
    String authorityHash =
        DesktopResearchConcurrencyTestSupport.snapshot("authority-probe")
            .authority()
            .stableHash();
    String epochHashBeforeRestore = null;
    String taskHashBeforeRestore = null;
    String resultHashBeforeRestore = null;
    String leaseHashBeforeRestore = null;
    String telemetryHashBeforeRestore = null;
    String epochHashAfterRestore = null;
    String taskHashAfterRestore = null;
    String resultHashAfterRestore = null;
    String leaseHashAfterRestore = null;
    String telemetryHashAfterRestore = null;
    int providerWorkItems = 0;
    int postRestoreLosses = 0;
    DesktopComputationIssue010CoordinatorHarness harness =
        DesktopComputationIssue010CoordinatorHarness.openForConcurrency(
            temporaryDirectory.resolve("sustained"), "issue-012-sustained");
    DesktopSolveCheckpoint finalCheckpoint;
    try {
      for (int round = 0; round < 20; round++) {
        var frozen = DesktopResearchConcurrencyTestSupport.snapshot("epoch-round-" + round);
        assertThat(frozen.authority().stableHash()).isEqualTo(authorityHash);
        var items =
            DesktopResearchConcurrencyTestSupport.items(
                frozen, stages.get(round % stages.size()), 4);
        List<ResearchWorkResultEnvelope> settled =
            harness
                .coordinator()
                .executeFrozenResearchEpoch(
                    frozen,
                    items,
                    (snapshot, item, lease) -> {
                      lease.call(
                          ProviderRequest.json(
                              List.of(new ChatMessage("user", item.workItemId())), 64, false));
                      return new ResearchWorkResultEnvelope(
                          item.workItemId(),
                          snapshot.epochId(),
                          snapshot.snapshotHash(),
                          lease.agent().id(),
                          "request-" + item.workItemId(),
                          ResearchWorkResultStatus.SUCCEEDED,
                          Map.of("round", item.stableOrdinal()),
                          List.of(),
                          List.of(),
                          List.of());
                    });
        providerWorkItems += settled.size();
        if (round > 10 && settled.size() != 4) {
          postRestoreLosses++;
        }
        assertThat(settled).hasSize(4);

        if (round == 10) {
          DesktopSolveCheckpoint before = harness.checkpointRoundTrip();
          epochHashBeforeRestore = CanonicalJson.stableHash(before.researchEpochs());
          taskHashBeforeRestore = CanonicalJson.stableHash(before.researchTasks());
          resultHashBeforeRestore = CanonicalJson.stableHash(before.researchResults());
          leaseHashBeforeRestore = CanonicalJson.stableHash(before.agentLeases());
          telemetryHashBeforeRestore = CanonicalJson.stableHash(before.concurrencyTelemetry());

          DesktopComputationIssue010CoordinatorHarness restored = harness.restored(before);
          harness.close();
          harness = restored;
          DesktopSolveCheckpoint after = harness.checkpointRoundTrip();
          epochHashAfterRestore = CanonicalJson.stableHash(after.researchEpochs());
          taskHashAfterRestore = CanonicalJson.stableHash(after.researchTasks());
          resultHashAfterRestore = CanonicalJson.stableHash(after.researchResults());
          leaseHashAfterRestore = CanonicalJson.stableHash(after.agentLeases());
          telemetryHashAfterRestore = CanonicalJson.stableHash(after.concurrencyTelemetry());

          assertThat(epochHashAfterRestore).isEqualTo(epochHashBeforeRestore);
          assertThat(taskHashAfterRestore).isEqualTo(taskHashBeforeRestore);
          assertThat(resultHashAfterRestore).isEqualTo(resultHashBeforeRestore);
          assertThat(leaseHashAfterRestore).isEqualTo(leaseHashBeforeRestore);
          assertThat(telemetryHashAfterRestore).isEqualTo(telemetryHashBeforeRestore);
        }
      }
      finalCheckpoint = harness.checkpointRoundTrip();
    } finally {
      harness.close();
    }

    ConcurrencyTelemetryLedger telemetry = new ConcurrencyTelemetryLedger();
    telemetry.restore(finalCheckpoint.concurrencyTelemetry());
    int maximum = telemetry.metrics(4, 5).maxActiveProviderCalls();
    assertThat(providerWorkItems).isEqualTo(80);
    assertThat(maximum).isEqualTo(4);
    assertThat(postRestoreLosses).isZero();
    assertThat(epochHashAfterRestore).isEqualTo(epochHashBeforeRestore);
    assertThat(taskHashAfterRestore).isEqualTo(taskHashBeforeRestore);
    assertThat(resultHashAfterRestore).isEqualTo(resultHashBeforeRestore);
    assertThat(leaseHashAfterRestore).isEqualTo(leaseHashBeforeRestore);
    assertThat(telemetryHashAfterRestore).isEqualTo(telemetryHashBeforeRestore);
    assertThat(finalCheckpoint.researchEpochs().epochs()).hasSize(20);
    assertThat(finalCheckpoint.researchTasks().tasks()).hasSize(80);
    assertThat(finalCheckpoint.researchResults().artifacts()).hasSize(80);

    System.out.println("SUSTAINED MULTI-KEY CONCURRENCY DIAGNOSTIC");
    System.out.println("ROUNDS=20");
    System.out.println("RESTORE_ROUND=10");
    System.out.println("ENABLED_AGENTS=5");
    System.out.println("RESEARCH_SLOTS=4");
    System.out.println("COORDINATION_SLOTS=1");
    System.out.println("PROVIDER_WORK_ITEMS=" + providerWorkItems);
    System.out.println("MAX_ACTIVE_PROVIDER_CALLS=" + maximum);
    System.out.println("POST_RESTORE_TASK_LOSSES=" + postRestoreLosses);
    System.out.println("EPOCH_HASH_BEFORE_RESTORE=" + epochHashBeforeRestore);
    System.out.println("EPOCH_HASH_AFTER_RESTORE=" + epochHashAfterRestore);
    System.out.println("TASK_HASH_BEFORE_RESTORE=" + taskHashBeforeRestore);
    System.out.println("TASK_HASH_AFTER_RESTORE=" + taskHashAfterRestore);
    System.out.println("RESULT_HASH_BEFORE_RESTORE=" + resultHashBeforeRestore);
    System.out.println("RESULT_HASH_AFTER_RESTORE=" + resultHashAfterRestore);
    System.out.println("LEASE_HASH_BEFORE_RESTORE=" + leaseHashBeforeRestore);
    System.out.println("LEASE_HASH_AFTER_RESTORE=" + leaseHashAfterRestore);
    System.out.println("TELEMETRY_HASH_BEFORE_RESTORE=" + telemetryHashBeforeRestore);
    System.out.println("TELEMETRY_HASH_AFTER_RESTORE=" + telemetryHashAfterRestore);
    System.out.println("ROOT_HASH_CHANGES=0");
    System.out.println("RESULT=PASS");
  }
}
