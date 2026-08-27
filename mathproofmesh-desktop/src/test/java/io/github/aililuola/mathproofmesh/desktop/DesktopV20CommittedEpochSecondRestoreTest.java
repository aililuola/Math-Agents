package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.concurrency.ResearchAuthorityCommitProtocol;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochStatus;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopV20CommittedEpochSecondRestoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void legacyCommittedEpochSurvivesTwoUpgradedCheckpointRestoresWithoutSyntheticReceipts()
      throws Exception {
    String runId = "v20-committed-epoch-second-restore";
    Path sourceDirectory = temporaryDirectory.resolve("source");
    DesktopSolveCheckpoint committed;
    try (DesktopClaimSalvageTestHarness source =
        DesktopClaimSalvageTestHarness.open(sourceDirectory, runId)) {
      source.prepareMixedClaimCourtBatch();
      source.integrateInstalledRound();
      committed = source.readPersistedCheckpoint();
      assertThat(source.providerCallCount()).isPositive();
    }

    DesktopSolveCheckpoint version20 = asVersion20WithoutReceipts(committed);
    assertThat(version20.researchEpochs().epochs())
        .singleElement()
        .satisfies(epoch -> assertThat(epoch.status()).isEqualTo(ResearchEpochStatus.COMMITTED));
    assertThat(version20.researchAuthorityMutations().authorityMutations()).isEmpty();
    assertThat(version20.researchAuthorityMutations().mergeReceipts()).isEmpty();

    DesktopSolveCheckpoint firstUpgrade;
    String authorityAfterFirstRestore;
    int providerCallsDuringMigration = 0;
    try (DesktopClaimSalvageTestHarness first =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory.resolve("first"), runId)) {
      first.restore(version20);
      firstUpgrade = first.checkpointRoundTrip();
      authorityAfterFirstRestore = first.researchAuthorityAnchor().restoreStableHash();
      providerCallsDuringMigration += first.providerCallCount();
    }

    DesktopSolveCheckpoint secondUpgrade;
    long postSecondRestoreAuthorityChanges;
    try (DesktopClaimSalvageTestHarness second =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory.resolve("second"), runId)) {
      second.restore(firstUpgrade);
      postSecondRestoreAuthorityChanges =
          second.researchAuthorityAnchor().restoreStableHash().equals(authorityAfterFirstRestore)
              ? 0L
              : 1L;
      secondUpgrade = second.checkpointRoundTrip();
      providerCallsDuringMigration += second.providerCallCount();
    }

    try (DesktopClaimSalvageTestHarness third =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory.resolve("third"), runId)) {
      third.restore(secondUpgrade);
      assertThat(third.researchAuthorityAnchor().restoreStableHash())
          .isEqualTo(authorityAfterFirstRestore);
      providerCallsDuringMigration += third.providerCallCount();
    }

    long legacyCommittedEpochs =
        secondUpgrade.researchEpochs().epochs().stream()
            .filter(epoch -> epoch.status() == ResearchEpochStatus.COMMITTED)
            .count();
    long synthesizedReceipts =
        secondUpgrade.researchAuthorityMutations().authorityMutations().size()
            + secondUpgrade.researchAuthorityMutations().mergeReceipts().size();
    long legacyProtocolLosses =
        secondUpgrade.researchEpochs().epochs().stream()
            .filter(
                epoch ->
                    epoch.authorityCommitProtocol()
                        != ResearchAuthorityCommitProtocol.LEGACY_NO_RECEIPT)
            .count();

    assertThat(legacyCommittedEpochs).isEqualTo(1L);
    assertThat(synthesizedReceipts).isZero();
    assertThat(legacyProtocolLosses).isZero();
    assertThat(providerCallsDuringMigration).isZero();
    assertThat(postSecondRestoreAuthorityChanges).isZero();
    assertThat(secondUpgrade.researchEpochs().epochs())
        .singleElement()
        .satisfies(epoch -> assertThat(epoch.status()).isEqualTo(ResearchEpochStatus.COMMITTED));

    System.out.println("LEGACY COMMITTED EPOCH SECOND-RESTORE DIAGNOSTIC");
    System.out.println("LEGACY_COMMITTED_EPOCHS=" + legacyCommittedEpochs);
    System.out.println("FIRST_RESTORE_FAILURES=0");
    System.out.println("SECOND_RESTORE_FAILURES=0");
    System.out.println("THIRD_RESTORE_FAILURES=0");
    System.out.println("LEGACY_EPOCH_PROTOCOL_LOSSES=" + legacyProtocolLosses);
    System.out.println("LEGACY_RECEIPTS_SYNTHESIZED=" + synthesizedReceipts);
    System.out.println("PROVIDER_CALLS_DURING_MIGRATION=" + providerCallsDuringMigration);
    System.out.println("POST_SECOND_RESTORE_EPOCH_STATUS=COMMITTED");
    System.out.println(
        "POST_SECOND_RESTORE_AUTHORITY_CHANGES=" + postSecondRestoreAuthorityChanges);
    System.out.println("RESULT=PASS");
  }

  private static DesktopSolveCheckpoint asVersion20WithoutReceipts(
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
    DesktopSolveCheckpoint migrated =
        ContractObjectMapper.read(json.toString(), DesktopSolveCheckpoint.class);
    return migrated;
  }
}
