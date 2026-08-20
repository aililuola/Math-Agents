package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.concurrency.ResearchAuthorityCommitProtocol;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochStatus;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopV20PreparedEpochModernCommitProtocolTest {
  @TempDir Path temporaryDirectory;

  @Test
  void replayedVersion20PreparedEpochCommitsUnderReceiptProtocolAndFailsClosedWithoutReceipts()
      throws Exception {
    String runId = "v20-prepared-epoch-modern-commit-protocol";
    Path runDirectory = temporaryDirectory.resolve("run");
    DesktopSolveCheckpoint prepared;
    try (DesktopClaimSalvageTestHarness source =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      source.prepareIndependentClaimCourtBatch(2);
      source.setAuthoritativeConcurrencyFailurePoint(
          DesktopSolveCoordinator.AuthoritativeConcurrencyFailurePoint
              .AFTER_RESULTS_DURABLE_BEFORE_COMMIT);

      assertThatThrownBy(source::integrateInstalledRound)
          .isInstanceOf(
              DesktopSolveCoordinator.SimulatedAuthoritativeConcurrencyProcessTermination.class);
      prepared = source.readPersistedCheckpoint();
    }

    DesktopSolveCheckpoint version20 = asVersion20WithoutReceiptMetadata(prepared);
    long legacyPreparedEpochs =
        version20.researchEpochs().epochs().stream()
            .filter(epoch -> epoch.status() == ResearchEpochStatus.MERGE_PREPARED)
            .filter(epoch -> epoch.authorityCommitProtocol() == null)
            .count();

    DesktopSolveCheckpoint modernCommitted;
    int providerCallReplays;
    try (DesktopClaimSalvageTestHarness replayed =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      replayed.restore(version20);
      replayed.integrateInstalledRound();
      providerCallReplays = replayed.providerCallCount();
      modernCommitted = replayed.readPersistedCheckpoint();
    }
    assertThat(modernCommitted.schemaVersion()).isEqualTo(21);

    long modernReplayedCommits =
        modernCommitted.researchEpochs().epochs().stream()
            .filter(epoch -> epoch.status() == ResearchEpochStatus.COMMITTED)
            .count();
    long receiptVersionOneProtocols =
        modernCommitted.researchEpochs().epochs().stream()
            .filter(
                epoch ->
                    epoch.authorityCommitProtocol()
                        == ResearchAuthorityCommitProtocol.RECEIPT_V1)
            .count();
    int mutationReceipts =
        modernCommitted.researchAuthorityMutations().authorityMutations().size();
    int mergeReceipts = modernCommitted.researchAuthorityMutations().mergeReceipts().size();

    try (DesktopClaimSalvageTestHarness restored =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory.resolve("committed"), runId)) {
      restored.restore(modernCommitted);
      assertThat(restored.providerCallCount()).isZero();
    }

    DesktopSolveCheckpoint missingReceipts = withoutReceipts(modernCommitted);
    long replayedModernCommittedEpochsWithoutReceipts =
        missingReceipts.researchEpochs().epochs().stream()
            .filter(epoch -> epoch.status() == ResearchEpochStatus.COMMITTED)
            .filter(
                epoch ->
                    epoch.authorityCommitProtocol()
                        == ResearchAuthorityCommitProtocol.RECEIPT_V1)
            .count();
    long missingReceiptQuarantines = 0L;
    long legacyFailOpenAccepts = 0L;
    try (DesktopClaimSalvageTestHarness restored =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory.resolve("missing"), runId)) {
      try {
        restored.restore(missingReceipts);
        legacyFailOpenAccepts++;
      } catch (IllegalStateException exception) {
        assertThat(exception).hasMessageContaining("QUARANTINED_PARTIAL_AUTHORITY_COMMIT");
        missingReceiptQuarantines++;
      }
    }

    System.out.println("V20 PREPARED EPOCH MODERN-COMMIT PROTOCOL DIAGNOSTIC");
    System.out.println("LEGACY_PREPARED_EPOCHS=" + legacyPreparedEpochs);
    System.out.println("MODERN_REPLAYED_COMMITS=" + modernReplayedCommits);
    System.out.println("POST_COMMIT_PROTOCOL_RECEIPT_V1=" + receiptVersionOneProtocols);
    System.out.println("POST_COMMIT_MUTATION_RECEIPTS=" + mutationReceipts);
    System.out.println("POST_COMMIT_MERGE_RECEIPTS=" + mergeReceipts);
    System.out.println("PROVIDER_CALL_REPLAYS=" + providerCallReplays);
    System.out.println(
        "REPLAYED_MODERN_COMMITTED_EPOCHS_WITHOUT_RECEIPTS="
            + replayedModernCommittedEpochsWithoutReceipts);
    System.out.println("MISSING_RECEIPT_QUARANTINES=" + missingReceiptQuarantines);
    System.out.println("LEGACY_FAIL_OPEN_ACCEPTS=" + legacyFailOpenAccepts);

    assertThat(legacyPreparedEpochs).isEqualTo(1L);
    assertThat(modernReplayedCommits).isEqualTo(1L);
    assertThat(receiptVersionOneProtocols).isEqualTo(1L);
    assertThat(mutationReceipts).isEqualTo(1);
    assertThat(mergeReceipts).isEqualTo(1);
    assertThat(providerCallReplays).isZero();
    assertThat(replayedModernCommittedEpochsWithoutReceipts).isEqualTo(1L);
    assertThat(missingReceiptQuarantines).isEqualTo(1L);
    assertThat(legacyFailOpenAccepts).isZero();
    System.out.println("RESULT=PASS");
  }

  private static DesktopSolveCheckpoint asVersion20WithoutReceiptMetadata(
      DesktopSolveCheckpoint checkpoint) {
    ObjectNode json =
        (ObjectNode) ContractObjectMapper.parseTree(ContractObjectMapper.write(checkpoint));
    json.put("schemaVersion", 20);
    json.remove("researchAuthorityMutations");
    ObjectNode epochs = (ObjectNode) json.get("researchEpochs");
    ArrayNode records = (ArrayNode) epochs.get("epochs");
    records.forEach(
        value -> {
          if (value instanceof ObjectNode record) {
            record.remove("authorityCommitProtocol");
            record.remove("authorityHashAfterCommit");
          }
        });
    return ContractObjectMapper.read(json.toString(), DesktopSolveCheckpoint.class);
  }

  private static DesktopSolveCheckpoint withoutReceipts(DesktopSolveCheckpoint checkpoint) {
    ObjectNode json =
        (ObjectNode) ContractObjectMapper.parseTree(ContractObjectMapper.write(checkpoint));
    json.remove("researchAuthorityMutations");
    return ContractObjectMapper.read(json.toString(), DesktopSolveCheckpoint.class);
  }
}
